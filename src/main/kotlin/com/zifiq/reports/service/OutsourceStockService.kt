package com.zifiq.reports.service

import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.common.money.Money
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.ref
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.dto.OutsourceStockReport
import com.zifiq.reports.dto.OutsourceStockRow
import com.zifiq.showroom.spi.ReturnFilter
import com.zifiq.showroom.spi.ShowroomQueries
import com.zifiq.showroom.spi.StockEntryFilter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.springframework.stereotype.Service

/**
 * The Out-Source Stock Report (docs/13 §4.3): sell-through of outsourced consignments. One row per
 * outsource receipt line (showroom stock entries of OUTSOURCED products). Sales of each item at each
 * showroom consume receipt lines oldest-first (FIFO), so a line's `qtySold` is the portion of lifetime
 * units sold attributed to it; `sellingPrice` is the item's average realised net unit price at that
 * showroom and `marginRealised` the revenue attributed to the line less its cost. Cost figures are
 * hidden without `factory.costing.read` (doc 03 §14). Runs in a tenant-scoped transaction.
 */
@Service
class OutsourceStockService(
    private val support: ReportsSupport,
    private val tx: TenantScopedTransaction,
    private val showroom: ShowroomQueries,
    private val sales: SalesQueries,
) {
    fun report(manufacturerId: UUID?, locationId: UUID?, status: String?, from: LocalDate?, to: LocalDate?): OutsourceStockReport = tx.execute {
        val costVisible = support.costVisible()
        val wantStatus = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val locSet = locationId?.let { setOf(it) }

        // All outsource receipts (lifetime), so FIFO allocation of lifetime sales is correct even when
        // the display window (from..to on receipt date) hides the earliest lines that absorbed sales.
        var receipts = showroom.stockEntries(StockEntryFilter(kind = "OUTSOURCE_RECEIPT", locationIds = locSet))
        if (manufacturerId != null) receipts = receipts.filter { it.manufacturerId == manufacturerId }
        if (receipts.isEmpty()) return@execute OutsourceStockReport(emptyList(), stats(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, costVisible), costVisible)

        val itemIds = receipts.map { it.stockItemId }.toSet()
        val statusById = StockItemsTable.select(StockItemsTable.id, StockItemsTable.status)
            .where { (StockItemsTable.tenantId eq support.tenantId()) and (StockItemsTable.id inList itemIds) }
            .associate { it[StockItemsTable.id].value to it[StockItemsTable.status] }

        // Lifetime units sold and net revenue per (item, showroom), net of posted returns.
        val soldUnits = HashMap<Pair<UUID, UUID>, BigDecimal>()
        val soldNet = HashMap<Pair<UUID, UUID>, BigDecimal>()
        sales.linesFor(SalesLineFilter(completedTo = support.today(), locationId = locationId)).forEach { l ->
            val loc = l.locationId ?: return@forEach
            if (l.stockItemId !in itemIds) return@forEach
            val k = l.stockItemId to loc
            soldUnits.merge(k, l.quantity, BigDecimal::add)
            soldNet.merge(k, l.lineNet, BigDecimal::add)
        }
        showroom.returns(ReturnFilter(locationIds = locSet, status = "POSTED")).forEach { r ->
            if (r.stockItemId !in itemIds) return@forEach
            val k = r.stockItemId to r.locationId
            soldUnits.merge(k, r.quantity.negate(), BigDecimal::add)
            soldNet.merge(k, r.lineValue.negate(), BigDecimal::add)
        }

        val rows = ArrayList<OutsourceStockRow>()
        var cogSourced = BigDecimal.ZERO
        var revenue = BigDecimal.ZERO
        var margin = BigDecimal.ZERO
        var unsold = BigDecimal.ZERO

        receipts.groupBy { it.stockItemId to it.locationId }.forEach { (k, lines) ->
            val (itemId, loc) = k
            val totalSold = (soldUnits[k] ?: BigDecimal.ZERO).max(BigDecimal.ZERO)
            val totalNet = (soldNet[k] ?: BigDecimal.ZERO).max(BigDecimal.ZERO)
            val avgPrice = if (totalSold.signum() > 0) totalNet.divide(totalSold, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
            var toAllocate = totalSold
            lines.sortedWith(compareBy({ it.receivedDate }, { it.entryId })).forEach { line ->
                val qSold = toAllocate.min(line.quantity).max(BigDecimal.ZERO)
                toAllocate -= qSold
                val remaining = line.quantity - qSold
                val rowStatus = when {
                    statusById[itemId] == "SUSPENDED" -> "SUSPENDED"
                    remaining.signum() == 0 -> "SOLD_OUT"
                    else -> "IN_STOCK"
                }
                val inWindow = (from == null || !line.receivedDate.isBefore(from)) && (to == null || !line.receivedDate.isAfter(to))
                if (!inWindow || (wantStatus != null && rowStatus != wantStatus)) return@forEach

                val revAlloc = qSold.multiply(avgPrice)
                val marginAlloc = revAlloc - qSold.multiply(line.unitCost)
                rows += OutsourceStockRow(
                    date = line.receivedDate, ref = line.ref,
                    product = ref(itemId, Ref::stockItem), vendor = ref(line.manufacturerId, Ref::manufacturer), showroom = ref(loc, Ref::location),
                    qtySourced = line.quantity, qtySold = qSold, remaining = remaining,
                    sellThroughPct = if (line.quantity.signum() > 0) qSold.divide(line.quantity, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP) else BigDecimal.ZERO,
                    unitCost = if (costVisible) line.unitCost else null,
                    sellingPrice = Money.round(avgPrice),
                    marginRealised = if (costVisible) Money.round(marginAlloc) else null,
                    status = rowStatus,
                )
                cogSourced += line.quantity.multiply(line.unitCost)
                revenue += revAlloc
                margin += marginAlloc
                unsold += remaining
            }
        }

        rows.sortWith(compareByDescending<OutsourceStockRow> { it.date }.thenBy { it.ref })
        OutsourceStockReport(rows, stats(cogSourced, revenue, margin, unsold, costVisible), costVisible)
    }

    private fun stats(cogSourced: BigDecimal, revenue: BigDecimal, margin: BigDecimal, unsold: BigDecimal, costVisible: Boolean): Map<String, Any?> = mapOf(
        "costOfGoodsSourced" to if (costVisible) Money.round(cogSourced) else null,
        "revenueRealised" to Money.round(revenue),
        "marginRealised" to if (costVisible) Money.round(margin) else null,
        "marginPctOfRevenue" to if (costVisible && revenue.signum() != 0) margin.divide(revenue, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP) else null,
        "unitsUnsold" to unsold,
    )
}
