package com.zifiq.reports.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.search.RawExpression
import com.zifiq.common.sql.Aggregates
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.ref
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.dto.AnalyticsGroup
import com.zifiq.reports.dto.DaysOfCoverRow
import com.zifiq.reports.dto.SlowMovingRow
import com.zifiq.reports.dto.StockAnalyticsResult
import com.zifiq.reports.dto.StockValueRow
import com.zifiq.reports.spi.StockAnalytics
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.springframework.stereotype.Service

/**
 * Stock analytics (docs/13 §4.5): value, slow-moving/dead stock and days of cover, read from the shared
 * inventory ledger (OPEN). On-hand, last-outbound and last-30-days-sold are a single grouped SQL
 * aggregation per item×location (never a row-by-row scan); item attributes come from the master in one
 * read. Stock value is masked without `factory.costing.read`. NEAR_EXPIRY (§4.5) needs batch/expiry
 * data from the receiving modules and is deferred to slice 4.
 */
@Service
class StockAnalyticsService(
    private val support: ReportsSupport,
    private val tx: TenantScopedTransaction,
) : StockAnalytics {

    fun analytics(view: String, asOf: LocalDate, groupBy: String?, locationIds: Set<UUID>?): StockAnalyticsResult = tx.execute {
        val v = view.trim().uppercase().ifEmpty { "VALUE" }
        val costVisible = support.costVisible()
        when (v) {
            "VALUE" -> StockAnalyticsResult(v, asOf, value = valueRows(groupBy ?: "LOCATION", asOf, locationIds, costVisible), costVisible = costVisible)
            "SLOW_MOVING" -> StockAnalyticsResult(v, asOf, slowMoving = slowMovingRows(asOf, locationIds, costVisible), costVisible = costVisible)
            "DAYS_OF_COVER" -> StockAnalyticsResult(v, asOf, daysOfCover = daysOfCoverRows(asOf, locationIds), costVisible = costVisible)
            else -> throw ApiErrors.validationFailed(mapOf("view" to "Must be VALUE, SLOW_MOVING or DAYS_OF_COVER (NEAR_EXPIRY is not yet available)"))
        }
    }

    override fun valueBy(groupBy: String, asOf: LocalDate, locationIds: Set<UUID>?): List<StockValueRow> =
        tx.execute { valueRows(groupBy, asOf, locationIds, support.costVisible()) }

    override fun slowMoving(asOf: LocalDate, locationIds: Set<UUID>?): List<SlowMovingRow> =
        tx.execute { slowMovingRows(asOf, locationIds, support.costVisible()) }

    override fun daysOfCover(asOf: LocalDate, locationIds: Set<UUID>?): List<DaysOfCoverRow> =
        tx.execute { daysOfCoverRows(asOf, locationIds) }

    // ---- computation ----

    private data class Balance(val itemId: UUID, val locationId: UUID, val onHand: BigDecimal, val lastOutbound: LocalDate?, val sold30: BigDecimal)
    private data class Item(val landingCost: BigDecimal?, val reorderLevel: BigDecimal, val categoryId: UUID?, val itemType: String)

    /** One grouped aggregation of the ledger per (item, location): on-hand, last outbound, last-30-days units sold. */
    private fun balances(asOf: LocalDate, locationIds: Set<UUID>?): List<Balance> {
        val onHand = RawExpression<BigDecimal>({ +"coalesce(sum("; +StockLedgerEntriesTable.qtyIn; +" - "; +StockLedgerEntriesTable.qtyOut; +"), 0)" })
        val lastOut = RawExpression<String?>({ +"to_char(max("; +StockLedgerEntriesTable.entryDate; +") filter (where "; +StockLedgerEntriesTable.qtyOut; +" > 0), 'YYYY-MM-DD')" })
        val sold30 = Aggregates.sum(
            StockLedgerEntriesTable.qtyOut,
            where = (StockLedgerEntriesTable.sourceType inList SALE_SOURCE_TYPES) and (StockLedgerEntriesTable.entryDate greaterEq asOf.minusDays(29)),
        )
        return StockLedgerEntriesTable
            .select(StockLedgerEntriesTable.stockItemId, StockLedgerEntriesTable.locationId, onHand, lastOut, sold30)
            .where {
                var c = (StockLedgerEntriesTable.tenantId eq support.tenantId()) and (StockLedgerEntriesTable.entryDate lessEq asOf)
                if (locationIds != null) c = c and (StockLedgerEntriesTable.locationId inList locationIds)
                c
            }
            .groupBy(StockLedgerEntriesTable.stockItemId, StockLedgerEntriesTable.locationId)
            .map {
                Balance(
                    itemId = it[StockLedgerEntriesTable.stockItemId].value, locationId = it[StockLedgerEntriesTable.locationId],
                    onHand = it[onHand], lastOutbound = it[lastOut]?.let(LocalDate::parse), sold30 = it[sold30],
                )
            }
    }

    private fun items(ids: Set<UUID>): Map<UUID, Item> =
        if (ids.isEmpty()) emptyMap()
        else StockItemsTable.select(StockItemsTable.id, StockItemsTable.landingCost, StockItemsTable.reorderLevel, StockItemsTable.categoryId, StockItemsTable.itemType)
            .where { (StockItemsTable.tenantId eq support.tenantId()) and (StockItemsTable.id inList ids) }
            .associate { it[StockItemsTable.id].value to Item(it[StockItemsTable.landingCost], it[StockItemsTable.reorderLevel], it[StockItemsTable.categoryId], it[StockItemsTable.itemType]) }

    private fun valueRows(groupByRaw: String, asOf: LocalDate, locationIds: Set<UUID>?, costVisible: Boolean): List<StockValueRow> {
        val groupBy = groupByRaw.trim().uppercase()
        if (groupBy !in setOf("LOCATION", "CATEGORY", "ITEM_TYPE")) throw ApiErrors.validationFailed(mapOf("groupBy" to "Must be LOCATION, CATEGORY or ITEM_TYPE"))
        val balances = balances(asOf, locationIds).filter { it.onHand.signum() > 0 }
        val items = items(balances.map { it.itemId }.toSet())

        data class Agg(var qty: BigDecimal, var value: BigDecimal, val group: AnalyticsGroup)
        val groups = LinkedHashMap<String, Agg>()
        balances.forEach { b ->
            val item = items[b.itemId]
            val value = b.onHand.multiply(item?.landingCost ?: BigDecimal.ZERO)
            val g = when (groupBy) {
                "LOCATION" -> AnalyticsGroup("LOCATION", b.locationId.toString(), null, ref(b.locationId, Ref::location))
                "CATEGORY" -> item?.categoryId?.let { AnalyticsGroup("CATEGORY", it.toString(), null, ref(it, Ref::category)) }
                    ?: AnalyticsGroup("CATEGORY", "UNCATEGORISED", "Uncategorised", null)
                else -> (item?.itemType ?: "UNKNOWN").let { AnalyticsGroup("ITEM_TYPE", it, it, null) }
            }
            val agg = groups.getOrPut(g.key) { Agg(BigDecimal.ZERO, BigDecimal.ZERO, g) }
            agg.qty += b.onHand
            agg.value += value
        }
        val total = groups.values.sumOf { it.value }
        return groups.values
            .sortedByDescending { it.value }
            .map {
                StockValueRow(
                    group = it.group, quantity = it.qty,
                    value = if (costVisible) Money.round(it.value) else null,
                    shareOfValuePct = if (costVisible && total.signum() != 0) it.value.divide(total, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP) else null,
                )
            }
    }

    private fun slowMovingRows(asOf: LocalDate, locationIds: Set<UUID>?, costVisible: Boolean): List<SlowMovingRow> {
        val slowDays = support.settings().int(ReportsSettings.SLOW_MOVING_DAYS).toLong()
        val deadDays = support.settings().int(ReportsSettings.DEAD_STOCK_DAYS).toLong()
        val balances = balances(asOf, locationIds).filter { it.onHand.signum() > 0 }
        val items = items(balances.map { it.itemId }.toSet())
        return balances.mapNotNull { b ->
            val daysSince = b.lastOutbound?.let { ChronoUnit.DAYS.between(it, asOf) }
            val dead = daysSince == null || daysSince > deadDays
            val slow = daysSince == null || daysSince > slowDays
            if (!slow) return@mapNotNull null
            SlowMovingRow(
                item = ref(b.itemId, Ref::stockItem), location = ref(b.locationId, Ref::location),
                onHand = b.onHand,
                value = if (costVisible) Money.round(b.onHand.multiply(items[b.itemId]?.landingCost ?: BigDecimal.ZERO)) else null,
                lastOutboundAt = b.lastOutbound, daysSinceOutbound = daysSince,
                classification = if (dead) "DEAD" else "SLOW",
            )
        }.sortedByDescending { it.daysSinceOutbound ?: Long.MAX_VALUE }
    }

    private fun daysOfCoverRows(asOf: LocalDate, locationIds: Set<UUID>?): List<DaysOfCoverRow> {
        val balances = balances(asOf, locationIds).filter { it.onHand.signum() > 0 }
        val items = items(balances.map { it.itemId }.toSet())
        return balances.map { b ->
            val avgDaily = b.sold30.divide(BigDecimal(30), 4, RoundingMode.HALF_UP)
            val reorder = items[b.itemId]?.reorderLevel ?: BigDecimal.ZERO
            DaysOfCoverRow(
                item = ref(b.itemId, Ref::stockItem), location = ref(b.locationId, Ref::location),
                available = b.onHand, avgDailySales = avgDaily,
                daysOfCover = if (avgDaily.signum() > 0) b.onHand.divide(avgDaily, 1, RoundingMode.HALF_UP) else null,
                reorderLevel = reorder, belowReorder = b.onHand < reorder,
            )
        }.sortedBy { it.daysOfCover ?: BigDecimal(Long.MAX_VALUE) }
    }

    companion object {
        private val SALE_SOURCE_TYPES = listOf("POS_SALE", "CUSTOMER_WAYBILL")
    }
}
