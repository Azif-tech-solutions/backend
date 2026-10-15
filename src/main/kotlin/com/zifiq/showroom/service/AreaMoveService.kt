package com.zifiq.showroom.service

import com.zifiq.inventory.repository.StockSql
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.StockLocations
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.showroom.dto.AreaMoveLineView
import com.zifiq.showroom.dto.AreaMoveRequest
import com.zifiq.showroom.dto.AreaMoveSummary
import com.zifiq.showroom.model.StorageAreaMoveLinesTable
import com.zifiq.showroom.model.StorageAreaMovesTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Storage-area moves (docs/05 §4.7): stock moved between areas of one showroom or factory, e.g.
 * Warehouse → Shop Floor. The goods never leave the premises, so there is no waybill.
 */
@Service
class AreaMoveService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
    private val sequences: DocumentSequenceService,
    private val ledger: StockLedgerRepository,
    private val stockLocations: StockLocations,
    private val domainEvents: DomainEvents,
) {
    fun move(request: AreaMoveRequest): AreaMoveSummary = tx.execute {
        val actor = currentActorProvider.current()
        val from = stockLocations.get(request.fromLocationId!!)
        val to = stockLocations.get(request.toLocationId!!)
        if (from.id == to.id) throw ApiErrors.sameLocation("Move from one area to a different one")
        if (from.rootId != to.rootId) throw ApiErrors.notSameLocation()
        locationAccess.assertAccess(from.rootId)
        stockLocations.requireCanReceive(from.id)
        stockLocations.requireCanReceive(to.id)
        // Quarantined goods were set aside for a reason; releasing them to a selling area needs one too.
        if (from.areaKind == "QUARANTINE" && to.areaKind != "QUARANTINE" && request.note.isNullOrBlank()) {
            throw ApiErrors.validationFailed(mapOf("note" to "Say why stock is leaving quarantine"))
        }
        val movedDate = request.movedDate ?: LocalDate.now(moduleSettings.zone())
        if (movedDate.isAfter(LocalDate.now(moduleSettings.zone()))) throw ApiErrors.validationFailed(mapOf("movedDate" to "cannot be in the future"))

        request.lines.groupBy({ it.stockItemId!! }, { it.quantity!! }).forEach { (itemId, quantities) ->
            val item = StockItemsTable.selectAll().where { StockItemsTable.id eq itemId }.firstOrNull() ?: throw ApiErrors.stockItemNotFound()
            if (item[StockItemsTable.status] != "ACTIVE") throw ApiErrors.stockItemSuspended()
            if (ledger.lockedBalance(actor.tenantId, from.id, itemId) < quantities.fold(BigDecimal.ZERO, BigDecimal::add)) {
                throw ApiErrors.insufficientStockForTransfer(itemId)
            }
        }

        val ref = sequences.next("AM")
        val id = StorageAreaMovesTable.insertAndGetId {
            it[tenantId] = actor.tenantId
            it[StorageAreaMovesTable.ref] = ref
            it[rootLocationId] = from.rootId
            it[fromLocationId] = from.id
            it[toLocationId] = to.id
            it[StorageAreaMovesTable.movedDate] = movedDate
            it[note] = request.note?.trim()?.ifEmpty { null }
            it[createdByUserId] = actor.userId
            it[createdAt] = Instant.now()
        }.value
        request.lines.forEachIndexed { index, line ->
            StorageAreaMoveLinesTable.insert {
                it[tenantId] = actor.tenantId
                it[moveId] = id
                it[lineNo] = index + 1
                it[stockItemId] = line.stockItemId!!
                it[quantity] = line.quantity!!
            }
            ledger.postEntry(actor.tenantId, from.id, line.stockItemId!!, "AREA_MOVE_OUT", ref, qtyOut = line.quantity!!,
                remark = "Moved to ${to.name}", performedByUserId = actor.userId, entryDate = movedDate)
            ledger.postEntry(actor.tenantId, to.id, line.stockItemId, "AREA_MOVE_IN", ref, qtyIn = line.quantity,
                remark = "Moved from ${from.name}", performedByUserId = actor.userId, entryDate = movedDate)
        }
        domainEvents.record(
            type = "showroom.area-move.posted", aggregateType = "area-move", aggregateId = id, aggregateRef = ref,
            summary = "$ref: ${request.lines.size} line(s) moved ${from.name} → ${to.name} at ${from.rootName}",
            payload = mapOf("lines" to request.lines.size, "fromLocationId" to from.id, "toLocationId" to to.id), locationId = from.rootId,
        )
        summary(id)
    }

    /** Moves at a showroom/factory (and its areas), newest first. */
    fun list(locationId: UUID?, from: LocalDate?, to: LocalDate?, page: Int?, size: Int?): PageResult<AreaMoveSummary> = tx.execute {
        val parts = mutableListOf<Op<Boolean>>(StorageAreaMovesTable.tenantId eq tenantContext.requireTenantId())
        locationAccess.resolveFilterSet(locationId)?.let { parts += StorageAreaMovesTable.rootLocationId inList it }
        from?.let { parts += StorageAreaMovesTable.movedDate greaterEq it }
        to?.let { parts += StorageAreaMovesTable.movedDate lessEq it }
        val query = StorageAreaMovesTable.selectAll().where(parts.reduce { a, b -> a and b })
            .orderBy(StorageAreaMovesTable.movedDate to SortOrder.DESC, StorageAreaMovesTable.createdAt to SortOrder.DESC, StorageAreaMovesTable.id to SortOrder.ASC)
        Paging.fetch(query, PageRequest.of(page, size)) { summary(it[StorageAreaMovesTable.id].value) }
    }

    private fun summary(id: UUID): AreaMoveSummary {
        val move = StorageAreaMovesTable.selectAll().where { StorageAreaMovesTable.id eq id }.single()
        val unit = StockSql.unitName(StockItemsTable.unitOfMeasureId)
        val lines = StorageAreaMoveLinesTable
            .join(StockItemsTable, JoinType.INNER, StorageAreaMoveLinesTable.stockItemId, StockItemsTable.id)
            .select(StorageAreaMoveLinesTable.columns + StockItemsTable.code + StockItemsTable.name + unit)
            .where { StorageAreaMoveLinesTable.moveId eq id }
            .orderBy(StorageAreaMoveLinesTable.lineNo to SortOrder.ASC)
            .map { AreaMoveLineView(it[StorageAreaMoveLinesTable.lineNo], it[StorageAreaMoveLinesTable.stockItemId], it[StockItemsTable.code],
                it[StockItemsTable.name], it[StorageAreaMoveLinesTable.quantity], it.getOrNull(unit)) }
        val root = stockLocations.get(move[StorageAreaMovesTable.rootLocationId])
        return AreaMoveSummary(
            id = id, ref = move[StorageAreaMovesTable.ref], rootLocationId = root.id, rootLocationName = root.name,
            fromLocationId = move[StorageAreaMovesTable.fromLocationId], fromLocationName = stockLocations.get(move[StorageAreaMovesTable.fromLocationId]).name,
            toLocationId = move[StorageAreaMovesTable.toLocationId], toLocationName = stockLocations.get(move[StorageAreaMovesTable.toLocationId]).name,
            movedDate = move[StorageAreaMovesTable.movedDate], note = move[StorageAreaMovesTable.note], lines = lines,
            movedByUserId = move[StorageAreaMovesTable.createdByUserId].value, createdAt = move[StorageAreaMovesTable.createdAt],
        )
    }
}
