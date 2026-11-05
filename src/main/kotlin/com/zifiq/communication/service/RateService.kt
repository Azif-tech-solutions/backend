package com.zifiq.communication.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.communication.dto.BulkRateRequest
import com.zifiq.communication.dto.BulkRateResult
import com.zifiq.communication.dto.CommissionRateHistoryRow
import com.zifiq.communication.dto.CommissionRateRow
import com.zifiq.communication.dto.RateChangePreview
import com.zifiq.communication.dto.SetRateRequest
import com.zifiq.communication.dto.SkippedRow
import com.zifiq.communication.dto.UpdateRateRequest
import com.zifiq.communication.model.CommissionRatesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.StockSql
import com.zifiq.platform.directory.Names
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * The Comms. Guide (docs/12 §4.1): effective-dated commission rates per product × beneficiary type.
 * The rate in force on a date is the row with the greatest effective_from on or before it; changing a
 * rate never alters an earlier one (rates are effective-dated and never backdated). Commission rates
 * per tenant are small (one per product), so the in-force / next-change rows for a page are resolved
 * with two bounded, index-backed reads rather than a correlated subquery per row.
 */
@Service
class RateService(
    private val support: CommunicationSupport,
    private val tx: TenantScopedTransaction,
) {
    private data class InForce(val id: UUID, val rateType: String, val rate: BigDecimal, val status: String, val effectiveFrom: LocalDate)

    fun list(
        beneficiaryType: String, categoryId: UUID?, rateType: String?, status: String?, q: String?, includeUnrated: Boolean, paging: PageRequest,
    ): PageResult<CommissionRateRow> = tx.execute {
        val bt = beneficiaryType.trim().uppercase()
        val today = support.today()
        val unit = StockSql.unitName()
        val category = StockSql.categoryName()
        val price = if (bt == "DEALER") StockSql.dealerPriceAt(today) else StockSql.retailPriceAt(today)
        val inForce = inForceRates(bt, today)
        val statusFilter = status?.trim()?.uppercase()
        val rateTypeFilter = rateType?.trim()?.uppercase()

        val query = StockItemsTable.select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, unit, category, price)
            .andWhere { (StockItemsTable.tenantId eq support.tenantId()) and (StockItemsTable.itemType inList SELLABLE) and (StockItemsTable.status eq "ACTIVE") }
        categoryId?.let { c -> query.andWhere { StockItemsTable.categoryId eq c } }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { term ->
            val t = term.lowercase()
            query.andWhere { (StockItemsTable.code.lowerCase() like "$t%") or (StockItemsTable.name.lowerCase() like "%$t%") }
        }
        // Rate-attribute filters restrict to the (small) matching item sets; "Not set" excludes rated items.
        val matching = inForce.filter { (_, r) ->
            (statusFilter == null || statusFilter == "NOT_SET" || r.status == statusFilter) && (rateTypeFilter == null || r.rateType == rateTypeFilter)
        }.keys
        when {
            statusFilter == "NOT_SET" -> query.andWhere { StockItemsTable.id notInList inForce.keys.ifEmpty { listOf(ZERO_UUID) } }
            statusFilter != null || rateTypeFilter != null -> query.andWhere { StockItemsTable.id inList matching.ifEmpty { listOf(ZERO_UUID) } }
            !includeUnrated -> query.andWhere { StockItemsTable.id inList inForce.keys.ifEmpty { listOf(ZERO_UUID) } }
        }

        val page = Paging.fetch(query.orderBy(StockItemsTable.name to SortOrder.ASC, StockItemsTable.id to SortOrder.ASC), paging) { it }
        val next = nextChanges(bt, today, page.items.map { it[StockItemsTable.id].value })
        page.withItems(page.items.map { r ->
            val id = r[StockItemsTable.id].value
            val f = inForce[id]
            val sellingPrice = r[price]?.let(Money::round)
            CommissionRateRow(
                stockItem = Ref.stockItem(id), code = r[StockItemsTable.code], product = r[StockItemsTable.name], group = r[category], unit = r[unit] ?: "Unit",
                sellingPrice = sellingPrice, rateType = f?.rateType, rate = f?.rate, commissionPerUnit = f?.let { perUnit(it, sellingPrice) },
                effectiveFrom = f?.effectiveFrom, status = statusLabel(f), nextChange = next[id],
            )
        }).copy(summary = summary(inForce))
    }

    fun history(stockItemId: UUID, beneficiaryType: String): List<CommissionRateHistoryRow> = tx.execute {
        val rows = CommissionRatesTable.selectAll()
            .where { (CommissionRatesTable.tenantId eq support.tenantId()) and (CommissionRatesTable.stockItemId eq stockItemId) and (CommissionRatesTable.beneficiaryType eq beneficiaryType.trim().uppercase()) }
            .orderBy(CommissionRatesTable.effectiveFrom to SortOrder.DESC).toList()
        rows.map { toHistoryRow(it) }
    }

    fun setRate(request: SetRateRequest): CommissionRateHistoryRow = tx.execute {
        val bt = request.beneficiaryType.uppercase()
        val from = request.effectiveFrom!!
        if (from.isBefore(support.today())) throw ApiErrors.rateCannotBeBackdated()
        requireSellable(request.stockItemId!!)
        if (rateOn(request.stockItemId, bt, from) != null) throw ApiErrors.rateAlreadySetForDate(from.toString())
        supersede(request.stockItemId, bt, from)
        val id = insertRate(request.stockItemId, bt, request.rateType, request.rate!!, request.referencePrice, request.status, from, request.note)
        support.record("communication.rate.set", "commission_rate", id, null, "Commission rate set for ${bt.lowercase()}", mapOf("beneficiaryType" to bt, "rateType" to request.rateType))
        row(id)
    }

    fun bulk(request: BulkRateRequest): BulkRateResult = tx.execute {
        val bt = request.beneficiaryType.uppercase()
        val from = request.effectiveFrom!!
        if (from.isBefore(support.today())) throw ApiErrors.rateCannotBeBackdated()
        var created = 0
        val skipped = mutableListOf<SkippedRow>()
        request.rows.forEachIndexed { i, r ->
            runCatching {
                requireSellable(r.stockItemId!!)
                if (rateOn(r.stockItemId, bt, from) != null) throw ApiErrors.rateAlreadySetForDate(from.toString())
                supersede(r.stockItemId, bt, from)
                insertRate(r.stockItemId, bt, r.rateType, r.rate!!, null, r.status, from, null)
                created++
            }.onFailure { skipped += SkippedRow(i, it.message ?: "skipped") }
        }
        if (created > 0) support.record("communication.rate.set", "commission_rate", support.tenantId(), null, "$created commission rate(s) set in bulk", mapOf("beneficiaryType" to bt, "count" to created))
        BulkRateResult(created, skipped)
    }

    fun update(id: UUID, request: UpdateRateRequest): CommissionRateHistoryRow = tx.execute {
        val existing = rateForUpdate(id)
        if (!existing[CommissionRatesTable.effectiveFrom].isAfter(support.today())) throw ApiErrors.rateNotEditable()
        val newFrom = request.effectiveFrom ?: existing[CommissionRatesTable.effectiveFrom]
        if (newFrom.isBefore(support.today())) throw ApiErrors.rateCannotBeBackdated()
        if (newFrom != existing[CommissionRatesTable.effectiveFrom] &&
            rateOn(existing[CommissionRatesTable.stockItemId], existing[CommissionRatesTable.beneficiaryType], newFrom) != null
        ) throw ApiErrors.rateAlreadySetForDate(newFrom.toString())
        CommissionRatesTable.update({ CommissionRatesTable.id eq id }) {
            request.rateType?.let { v -> it[rateType] = v }
            request.rate?.let { v -> it[rate] = v }
            request.status?.let { v -> it[status] = v }
            request.note?.let { v -> it[note] = v.trim().ifEmpty { null } }
            it[effectiveFrom] = newFrom
        }
        row(id)
    }

    fun withdraw(id: UUID) = tx.execute {
        val existing = rateForUpdate(id)
        if (!existing[CommissionRatesTable.effectiveFrom].isAfter(support.today())) throw ApiErrors.rateNotEditable()
        // Reopen the predecessor this future row had closed, then delete the future row.
        val predecessorClosedTo = existing[CommissionRatesTable.effectiveFrom].minusDays(1)
        CommissionRatesTable.update({
            (CommissionRatesTable.tenantId eq support.tenantId()) and (CommissionRatesTable.stockItemId eq existing[CommissionRatesTable.stockItemId]) and
                (CommissionRatesTable.beneficiaryType eq existing[CommissionRatesTable.beneficiaryType]) and (CommissionRatesTable.effectiveTo eq predecessorClosedTo)
        }) { it[effectiveTo] = null }
        CommissionRatesTable.deleteWhere { CommissionRatesTable.id eq id }
        support.record("communication.rate.withdrawn", "commission_rate", id, null, "Future commission rate withdrawn", emptyMap())
    }

    // ---- internals ----

    private fun inForceRates(bt: String, today: LocalDate): Map<UUID, InForce> {
        val map = HashMap<UUID, InForce>()
        CommissionRatesTable.selectAll()
            .where { (CommissionRatesTable.tenantId eq support.tenantId()) and (CommissionRatesTable.beneficiaryType eq bt) and (CommissionRatesTable.effectiveFrom lessEq today) }
            .orderBy(CommissionRatesTable.effectiveFrom to SortOrder.DESC)
            .forEach { r ->
                val item = r[CommissionRatesTable.stockItemId]
                if (item !in map) map[item] = InForce(r[CommissionRatesTable.id].value, r[CommissionRatesTable.rateType], r[CommissionRatesTable.rate], r[CommissionRatesTable.status], r[CommissionRatesTable.effectiveFrom])
            }
        return map
    }

    private fun nextChanges(bt: String, today: LocalDate, itemIds: List<UUID>): Map<UUID, RateChangePreview> {
        if (itemIds.isEmpty()) return emptyMap()
        val map = HashMap<UUID, RateChangePreview>()
        CommissionRatesTable.selectAll()
            .where { (CommissionRatesTable.tenantId eq support.tenantId()) and (CommissionRatesTable.beneficiaryType eq bt) and (CommissionRatesTable.stockItemId inList itemIds) and (CommissionRatesTable.effectiveFrom greater today) }
            .orderBy(CommissionRatesTable.effectiveFrom to SortOrder.ASC)
            .forEach { r ->
                val item = r[CommissionRatesTable.stockItemId]
                if (item !in map) map[item] = RateChangePreview(r[CommissionRatesTable.effectiveFrom], r[CommissionRatesTable.rateType], r[CommissionRatesTable.rate], statusWord(r[CommissionRatesTable.status]))
            }
        return map
    }

    private fun perUnit(f: InForce, sellingPrice: BigDecimal?): BigDecimal? = when {
        f.status != "ACTIVE" -> null
        f.rateType == "PER_UNIT" -> f.rate
        sellingPrice != null -> sellingPrice.multiply(f.rate).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        else -> null
    }

    private fun statusLabel(f: InForce?): String = when (f?.status) { "ACTIVE" -> "Active"; "INACTIVE" -> "Inactive"; else -> "Not set" }
    private fun statusWord(s: String): String = if (s == "ACTIVE") "Active" else "Inactive"

    private fun summary(inForce: Map<UUID, InForce>): Map<String, Any?> {
        val active = inForce.values.filter { it.status == "ACTIVE" }
        val perUnit = active.filter { it.rateType == "PER_UNIT" }
        val avg = if (perUnit.isEmpty()) BigDecimal.ZERO else perUnit.fold(BigDecimal.ZERO) { a, r -> a + r.rate }.divide(BigDecimal(perUnit.size), 2, RoundingMode.HALF_UP)
        return mapOf("productsRated" to inForce.size, "activeRates" to active.size, "perUnitRates" to perUnit.size, "averagePerUnit" to avg)
    }

    private fun requireSellable(stockItemId: UUID) {
        val item = StockItemsTable.select(StockItemsTable.itemType)
            .where { (StockItemsTable.id eq stockItemId) and (StockItemsTable.tenantId eq support.tenantId()) }.firstOrNull() ?: throw ApiErrors.stockItemNotFound()
        if (item[StockItemsTable.itemType] !in SELLABLE) throw ApiErrors.validationFailed(mapOf("stockItemId" to "not a product that earns commission"))
    }

    private fun rateOn(stockItemId: UUID, bt: String, from: LocalDate): ResultRow? =
        CommissionRatesTable.selectAll()
            .where { (CommissionRatesTable.tenantId eq support.tenantId()) and (CommissionRatesTable.stockItemId eq stockItemId) and (CommissionRatesTable.beneficiaryType eq bt) and (CommissionRatesTable.effectiveFrom eq from) }
            .firstOrNull()

    private fun supersede(stockItemId: UUID, bt: String, from: LocalDate) {
        CommissionRatesTable.update({
            (CommissionRatesTable.tenantId eq support.tenantId()) and (CommissionRatesTable.stockItemId eq stockItemId) and (CommissionRatesTable.beneficiaryType eq bt) and
                (CommissionRatesTable.effectiveFrom less from) and (CommissionRatesTable.effectiveTo.isNull())
        }) { it[effectiveTo] = from.minusDays(1) }
    }

    private fun insertRate(stockItemId: UUID, bt: String, rateType: String, rate: BigDecimal, referencePrice: BigDecimal?, status: String, from: LocalDate, note: String?): UUID =
        CommissionRatesTable.insertAndGetId {
            it[tenantId] = support.tenantId(); it[CommissionRatesTable.stockItemId] = stockItemId; it[beneficiaryType] = bt
            it[CommissionRatesTable.rateType] = rateType; it[CommissionRatesTable.rate] = rate; it[CommissionRatesTable.referencePrice] = referencePrice
            it[CommissionRatesTable.status] = status; it[effectiveFrom] = from; it[CommissionRatesTable.note] = note?.trim()?.ifEmpty { null }
            it[setByUserId] = support.userId(); it[createdAt] = Instant.now()
        }.value

    private fun rateForUpdate(id: UUID): ResultRow =
        CommissionRatesTable.selectAll().where { (CommissionRatesTable.id eq id) and (CommissionRatesTable.tenantId eq support.tenantId()) }.forUpdate().firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("id" to "rate not found"))

    private fun row(id: UUID): CommissionRateHistoryRow = toHistoryRow(CommissionRatesTable.selectAll().where { CommissionRatesTable.id eq id }.single())

    private fun toHistoryRow(r: ResultRow) = CommissionRateHistoryRow(
        id = r[CommissionRatesTable.id].value, beneficiaryType = r[CommissionRatesTable.beneficiaryType], rateType = r[CommissionRatesTable.rateType],
        rate = r[CommissionRatesTable.rate], referencePrice = r[CommissionRatesTable.referencePrice], status = r[CommissionRatesTable.status],
        effectiveFrom = r[CommissionRatesTable.effectiveFrom], effectiveTo = r[CommissionRatesTable.effectiveTo], note = r[CommissionRatesTable.note],
        setBy = UserRef.of(r[CommissionRatesTable.setByUserId].value), createdAt = r[CommissionRatesTable.createdAt],
    )

    private companion object {
        val SELLABLE = listOf("FINISHED_PRODUCT", "OUTSOURCED", "DIRECT_ENTRY", "IN_HOUSE_RECIPE")
        val ZERO_UUID: UUID = UUID(0, 0)
    }
}
