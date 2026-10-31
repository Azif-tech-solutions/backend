package com.zifiq.history.service

import com.zifiq.cashier.spi.StockItemUsageQueries
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.history.dto.DeletedItemRow
import com.zifiq.history.dto.DeleteProductRequest
import com.zifiq.history.dto.PurgeRequest
import com.zifiq.history.dto.RecycleActionView
import com.zifiq.history.dto.RecycleNoteRequest
import com.zifiq.history.dto.RepriceAndResumeRequest
import com.zifiq.history.dto.ReviewRequest
import com.zifiq.history.dto.SuspendedItemRow
import com.zifiq.history.dto.SuspendProductRequest
import com.zifiq.history.model.RecycleBinLogTable
import com.zifiq.inventory.dto.PriceEntryInput
import com.zifiq.inventory.dto.PricePublishRequest
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockReservationsTable
import com.zifiq.inventory.model.StockTransferLinesTable
import com.zifiq.inventory.model.StockTransfersTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.PriceListService
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * The Recycle Bin (docs/10 §4.9): a product's lifecycle after it leaves active sale — SUSPENDED (held
 * back, review date) or DELETED (in the bin for a retention period, then purge-eligible); PURGED is
 * irreversible and writes off any stock held. stock_items is inventory's (OPEN) table; the writes go
 * through it directly, with the guards the design requires (open reservations, in-transit transfers,
 * live Sales & POS documents). Variants are not modelled, so that blocker does not apply.
 */
@Service
class RecycleBinService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
    private val usage: StockItemUsageQueries,
    private val stockLedger: StockLedgerRepository,
    private val priceList: PriceListService,
) {
    fun delete(request: DeleteProductRequest): RecycleActionView = tx.execute {
        val item = itemForUpdate(request.stockItemId!!)
        val status = item[StockItemsTable.status]
        if (status !in setOf("ACTIVE", "SUSPENDED")) throw ApiErrors.itemNotRecyclable(status)
        assertNotReserved(item)
        assertNotInTransit(request.stockItemId)
        assertNotInOpenDocuments(request.stockItemId)
        val held = quantityHeld(request.stockItemId)
        val retentionUntil = support.today().plusDays(support.settings().int(HistorySettings.RECYCLE_BIN_RETENTION_DAYS).toLong())
        StockItemsTable.update({ StockItemsTable.id eq request.stockItemId }) {
            it[StockItemsTable.status] = "DELETED"
            it[statusReason] = request.reason!!.trim()
            it[statusChangedAt] = Instant.now()
            it[statusChangedByUserId] = support.userId()
            it[deletedRetentionUntil] = retentionUntil
            it[suspensionReviewOn] = null
        }
        log("DELETED", request.stockItemId, item, request.reason!!.trim(), held, retentionUntil = retentionUntil)
        emit("inventory.stock-item.deleted", request.stockItemId, item, "deleted")
        actionView(request.stockItemId, "DELETED")
    }

    fun restore(stockItemId: UUID, request: RecycleNoteRequest): RecycleActionView = tx.execute {
        val item = itemForUpdate(stockItemId)
        if (item[StockItemsTable.status] != "DELETED") throw ApiErrors.itemNotDeleted()
        StockItemsTable.update({ StockItemsTable.id eq stockItemId }) {
            it[status] = "ACTIVE"
            it[statusReason] = request.note?.trim()?.ifEmpty { null }
            it[statusChangedAt] = Instant.now()
            it[statusChangedByUserId] = support.userId()
            it[deletedRetentionUntil] = null
        }
        log("RESTORED", stockItemId, item, request.note?.trim())
        emit("inventory.stock-item.restored", stockItemId, item, "restored")
        actionView(stockItemId, "ACTIVE")
    }

    fun purge(stockItemId: UUID, request: PurgeRequest): RecycleActionView = tx.execute {
        val item = itemForUpdate(stockItemId)
        if (item[StockItemsTable.status] != "DELETED") throw ApiErrors.itemNotDeleted()
        if (request.confirmCode?.trim() != item[StockItemsTable.code]) throw ApiErrors.purgeConfirmationMismatch()
        val held = quantityHeld(stockItemId)
        if (held.signum() > 0 && !request.writeOffConfirmed) throw ApiErrors.purgeRequiresWriteOff()
        if (held.signum() > 0) writeOff(stockItemId, item)
        val barcode = item[StockItemsTable.barcode]
        StockItemsTable.update({ StockItemsTable.id eq stockItemId }) {
            it[status] = "PURGED"
            it[statusReason] = ("Purged: ${request.reason!!.trim()}" + (barcode?.let { b -> " (released barcode $b)" } ?: "")).take(300)
            it[statusChangedAt] = Instant.now()
            it[statusChangedByUserId] = support.userId()
            it[StockItemsTable.barcode] = null
        }
        log("PURGED", stockItemId, item, request.reason!!.trim(), held, writeOff = if (held.signum() > 0) held else null)
        emit("inventory.stock-item.purged", stockItemId, item, "purged")
        actionView(stockItemId, "PURGED")
    }

    fun suspend(request: SuspendProductRequest): RecycleActionView = tx.execute {
        val item = itemForUpdate(request.stockItemId!!)
        if (item[StockItemsTable.status] != "ACTIVE") throw ApiErrors.itemNotRecyclable(item[StockItemsTable.status])
        assertNotReserved(item)
        assertNotInOpenDocuments(request.stockItemId)   // in-transit transfers may still be received, so not blocked
        StockItemsTable.update({ StockItemsTable.id eq request.stockItemId }) {
            it[status] = "SUSPENDED"
            it[statusReason] = request.reason!!.trim()
            it[statusChangedAt] = Instant.now()
            it[statusChangedByUserId] = support.userId()
            it[suspensionReviewOn] = request.reviewOn
        }
        log("SUSPENDED", request.stockItemId, item, request.reason!!.trim(), reviewOn = request.reviewOn)
        emit("inventory.stock-item.suspended", request.stockItemId, item, "suspended")
        actionView(request.stockItemId, "SUSPENDED")
    }

    fun resume(stockItemId: UUID, request: RecycleNoteRequest): RecycleActionView = tx.execute {
        resumeInternal(stockItemId, request.note?.trim())
        actionView(stockItemId, "ACTIVE")
    }

    fun setReview(stockItemId: UUID, request: ReviewRequest): RecycleActionView = tx.execute {
        val item = itemForUpdate(stockItemId)
        if (item[StockItemsTable.status] != "SUSPENDED") throw ApiErrors.itemNotSuspended()
        StockItemsTable.update({ StockItemsTable.id eq stockItemId }) { it[suspensionReviewOn] = request.reviewOn }
        actionView(stockItemId, "SUSPENDED")
    }

    /** Publish a new price (doc 05 price list) and resume in one transaction (§4.9). */
    fun repriceAndResume(stockItemId: UUID, request: RepriceAndResumeRequest): RecycleActionView = tx.execute {
        val item = itemForUpdate(stockItemId)
        if (item[StockItemsTable.status] != "SUSPENDED") throw ApiErrors.itemNotSuspended()
        priceList.publish(PricePublishRequest(request.effectiveFrom, "Reprice on resume", listOf(PriceEntryInput(stockItemId, request.retailPrice, request.dealerPrice))))
        log("REPRICED", stockItemId, item, "Retail ${request.retailPrice}, dealer ${request.dealerPrice}")
        resumeInternal(stockItemId, "Repriced and resumed")
        actionView(stockItemId, "ACTIVE")
    }

    fun deletedList(q: String?, paging: PageRequest): PageResult<DeletedItemRow> = tx.execute {
        val base = StockItemsTable.selectAll().where(statusCondition("DELETED", q))
            .orderBy(StockItemsTable.deletedRetentionUntil to SortOrder.ASC, StockItemsTable.id to SortOrder.ASC)
        val page = Paging.fetch(base, paging) { it }
        val held = heldByItem(page.items.map { it[StockItemsTable.id].value })
        val visible = support.costVisible()
        val today = support.today()
        page.withItems(page.items.map { r ->
            val qty = held[r[StockItemsTable.id].value] ?: BigDecimal.ZERO
            val cost = r[StockItemsTable.landingCost]
            DeletedItemRow(
                stockItem = Ref.stockItem(r[StockItemsTable.id].value), quantityHeld = qty,
                unitCost = if (visible) cost else BigDecimal.ZERO, valueHeld = if (visible) cost?.multiply(qty) else BigDecimal.ZERO,
                reason = r[StockItemsTable.statusReason], deletedBy = UserRef.of(r[StockItemsTable.statusChangedByUserId]),
                deletedOn = r[StockItemsTable.statusChangedAt], heldUntil = r[StockItemsTable.deletedRetentionUntil],
                purgeEligible = r[StockItemsTable.deletedRetentionUntil]?.let { !it.isAfter(today) } ?: true,
            )
        })
    }

    fun suspendedList(q: String?, paging: PageRequest): PageResult<SuspendedItemRow> = tx.execute {
        val base = StockItemsTable.selectAll().where(statusCondition("SUSPENDED", q))
            .orderBy(StockItemsTable.suspensionReviewOn to SortOrder.ASC, StockItemsTable.id to SortOrder.ASC)
        val page = Paging.fetch(base, paging) { it }
        val held = heldByItem(page.items.map { it[StockItemsTable.id].value })
        val visible = support.costVisible()
        page.withItems(page.items.map { r ->
            val qty = held[r[StockItemsTable.id].value] ?: BigDecimal.ZERO
            val cost = r[StockItemsTable.landingCost]
            SuspendedItemRow(
                stockItem = Ref.stockItem(r[StockItemsTable.id].value), stockHeld = qty,
                unitCost = if (visible) cost else BigDecimal.ZERO, valueHeld = if (visible) cost?.multiply(qty) else BigDecimal.ZERO,
                reason = r[StockItemsTable.statusReason], suspendedOn = r[StockItemsTable.statusChangedAt], reviewOn = r[StockItemsTable.suspensionReviewOn],
            )
        })
    }

    // ---- helpers ----

    private fun resumeInternal(stockItemId: UUID, note: String?) {
        val item = itemForUpdate(stockItemId)
        if (item[StockItemsTable.status] != "SUSPENDED") throw ApiErrors.itemNotSuspended()
        StockItemsTable.update({ StockItemsTable.id eq stockItemId }) {
            it[status] = "ACTIVE"
            it[statusReason] = note
            it[statusChangedAt] = Instant.now()
            it[statusChangedByUserId] = support.userId()
            it[suspensionReviewOn] = null
        }
        log("RESUMED", stockItemId, item, note)
        emit("inventory.stock-item.resumed", stockItemId, item, "resumed")
    }

    private fun writeOff(stockItemId: UUID, item: ResultRow) {
        val cost = item[StockItemsTable.landingCost]
        StockBalancesTable.select(StockBalancesTable.locationId, StockBalancesTable.quantityOnHand)
            .where { (StockBalancesTable.stockItemId eq stockItemId) and (StockBalancesTable.quantityOnHand greater BigDecimal.ZERO) }
            .forEach { bal ->
                stockLedger.postEntry(
                    tenantId = support.tenantId(), locationId = bal[StockBalancesTable.locationId], stockItemId = stockItemId,
                    sourceType = "PURGE_WRITE_OFF", sourceReference = item[StockItemsTable.code],
                    qtyOut = bal[StockBalancesTable.quantityOnHand], unitCost = cost, remark = "Purge write-off",
                    performedByUserId = support.userId(), entryDate = support.today(),
                )
            }
    }

    private fun itemForUpdate(stockItemId: UUID): ResultRow {
        val row = StockItemsTable.selectAll().where { (StockItemsTable.id eq stockItemId) and (StockItemsTable.tenantId eq support.tenantId()) }.forUpdate().firstOrNull()
            ?: throw ApiErrors.stockItemNotFound()
        support.visibleLocations()?.let { if (row[StockItemsTable.locationId] !in it) throw ApiErrors.stockItemNotFound() }
        return row
    }

    private fun quantityHeld(stockItemId: UUID): BigDecimal {
        val total = StockBalancesTable.quantityOnHand.sum()
        return StockBalancesTable.select(total).where { StockBalancesTable.stockItemId eq stockItemId }.firstOrNull()?.get(total) ?: BigDecimal.ZERO
    }

    private fun heldByItem(ids: List<UUID>): Map<UUID, BigDecimal> {
        if (ids.isEmpty()) return emptyMap()
        val total = StockBalancesTable.quantityOnHand.sum()
        return StockBalancesTable.select(StockBalancesTable.stockItemId, total).where { StockBalancesTable.stockItemId inList ids }
            .groupBy(StockBalancesTable.stockItemId)
            .associate { it[StockBalancesTable.stockItemId].value to (it[total] ?: BigDecimal.ZERO) }
    }

    private fun assertNotReserved(item: ResultRow) {
        val open = StockReservationsTable.select(StockReservationsTable.id)
            .where { (StockReservationsTable.stockItemId eq item[StockItemsTable.id].value) and (StockReservationsTable.status eq "OPEN") }
            .limit(1).any()
        if (open) throw ApiErrors.stockReserved(BigDecimal.ZERO)
    }

    private fun assertNotInTransit(stockItemId: UUID) {
        val inTransit = StockTransferLinesTable.join(StockTransfersTable, JoinType.INNER, StockTransferLinesTable.stockTransferId, StockTransfersTable.id)
            .select(StockTransferLinesTable.id)
            .where { (StockTransferLinesTable.stockItemId eq stockItemId) and (StockTransfersTable.status inList listOf("DISPATCHED", "PARTIALLY_RECEIVED")) }
            .limit(1).any()
        if (inTransit) throw ApiErrors.itemInTransit()
    }

    private fun assertNotInOpenDocuments(stockItemId: UUID) {
        if (usage.inOpenDocuments(stockItemId)) throw ApiErrors.itemInOpenDocuments()
    }

    private fun statusCondition(status: String, q: String?): org.jetbrains.exposed.sql.Op<Boolean> {
        var cond: org.jetbrains.exposed.sql.Op<Boolean> = (StockItemsTable.tenantId eq support.tenantId()) and (StockItemsTable.status eq status)
        support.visibleLocations()?.let { cond = cond and (StockItemsTable.locationId inList it) }
        val term = q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (term != null) cond = cond and ((StockItemsTable.code.lowerCase() like "%$term%") or (StockItemsTable.name.lowerCase() like "%$term%"))
        return cond
    }

    private fun log(action: String, stockItemId: UUID, item: ResultRow, reason: String?, quantityHeld: BigDecimal? = null, reviewOn: java.time.LocalDate? = null, retentionUntil: java.time.LocalDate? = null, writeOff: BigDecimal? = null) {
        val cost = item[StockItemsTable.landingCost]
        RecycleBinLogTable.insert {
            it[tenantId] = support.tenantId()
            it[RecycleBinLogTable.stockItemId] = stockItemId
            it[RecycleBinLogTable.action] = action
            it[RecycleBinLogTable.reason] = reason?.take(300)
            it[RecycleBinLogTable.quantityHeld] = quantityHeld
            it[valueHeld] = quantityHeld?.let { q -> cost?.multiply(q) }
            it[RecycleBinLogTable.reviewOn] = reviewOn
            it[RecycleBinLogTable.retentionUntil] = retentionUntil
            it[writeOffQuantity] = writeOff
            it[byUserId] = support.userId()
            it[at] = Instant.now()
        }
    }

    private fun emit(type: String, stockItemId: UUID, item: ResultRow, verb: String) {
        support.record(type, "stock_item", stockItemId, item[StockItemsTable.code], "${item[StockItemsTable.code]} ${item[StockItemsTable.name]} $verb",
            mapOf("code" to item[StockItemsTable.code]), item[StockItemsTable.locationId])
    }

    private fun actionView(stockItemId: UUID, status: String) =
        RecycleActionView(Ref.stockItem(stockItemId), status, status, Instant.now())
}
