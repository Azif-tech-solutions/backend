package com.zifiq.reports.service

import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesLineRef
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.ref
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.dto.AnalyticsGroup
import com.zifiq.reports.dto.ComparisonCell
import com.zifiq.reports.dto.SalesAnalyticsResult
import com.zifiq.reports.dto.SalesAnalyticsRow
import com.zifiq.reports.dto.SciPeriod
import com.zifiq.reports.spi.SalesAnalytics
import com.zifiq.reports.spi.SalesAnalyticsQuery
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Sales analytics (docs/13 §4.4): completed sale lines grouped by one or two dimensions and an optional
 * time interval, with quantity, revenue, discounts, VAT, COGS and margin. Rows are read through the
 * cashier SPI (index-backed by tenant + date + the pushed-down filters) and aggregated here — the
 * modular equivalent of a `group by`. Cost measures are masked without `factory.costing.read`.
 */
@Service
class SalesAnalyticsService(
    private val support: ReportsSupport,
    private val tx: TenantScopedTransaction,
    private val sales: SalesQueries,
) : SalesAnalytics {

    override fun aggregate(query: SalesAnalyticsQuery): List<SalesAnalyticsRow> =
        run(query.from, query.to, query.groupBy, query.interval, query.locationIds, query.customerTypes, query.top, query.compare).rows

    fun analytics(
        from: LocalDate,
        to: LocalDate,
        groupBy: List<String>,
        interval: String,
        locationIds: Set<UUID>?,
        customerTypes: Set<String>?,
        top: Int,
        compare: String,
    ): SalesAnalyticsResult = run(from, to, groupBy, interval, locationIds, customerTypes, top, compare)

    private fun run(
        from: LocalDate,
        to: LocalDate,
        groupByRaw: List<String>,
        intervalRaw: String,
        locationIds: Set<UUID>?,
        customerTypes: Set<String>?,
        top: Int,
        compareRaw: String,
    ): SalesAnalyticsResult = tx.execute {
        val groupBy = groupByRaw.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (groupBy.isEmpty() || groupBy.size > 2) throw ApiErrors.validationFailed(mapOf("groupBy" to "Choose one or two dimensions"))
        groupBy.forEach { if (it !in DIMENSIONS) throw ApiErrors.validationFailed(mapOf("groupBy" to "Unknown dimension $it")) }
        val interval = intervalRaw.trim().uppercase().ifEmpty { "NONE" }
        if (interval !in INTERVALS) throw ApiErrors.validationFailed(mapOf("interval" to "Must be NONE, DAY, WEEK or MONTH"))
        val compare = compareRaw.trim().uppercase().ifEmpty { "NONE" }
        if (compare !in setOf("NONE", "PRIOR_PERIOD", "SAME_PERIOD_LAST_YEAR")) throw ApiErrors.validationFailed(mapOf("compare" to "Must be NONE, PRIOR_PERIOD or SAME_PERIOD_LAST_YEAR"))
        if (from.isAfter(to)) throw ApiErrors.validationFailed(mapOf("from" to "Must not be after 'to'"))
        if (ChronoUnit.DAYS.between(from, to) + 1 > 366) throw ApiErrors.validationFailed(mapOf("to" to "The period cannot exceed 366 days"))
        val limit = top.coerceIn(1, 200)
        val costVisible = support.costVisible()

        val lines = fetch(from, to, locationIds, customerTypes)
        val buckets = aggregate(lines, groupBy, interval)
        val totalNet = buckets.values.sumOf { it.net }

        // Comparison (interval = NONE only): prior-period net per group key.
        val cmpPeriod: SciPeriod?
        val priorNetByKey: Map<String, BigDecimal>
        if (compare == "NONE" || interval != "NONE") {
            cmpPeriod = null; priorNetByKey = emptyMap()
        } else {
            val (pf, pt) = shiftPeriod(from, to, compare)
            cmpPeriod = SciPeriod(pf, pt, compare)
            // Compared at group level (interval NONE), so key the prior nets by the group part only.
            priorNetByKey = aggregate(fetch(pf, pt, locationIds, customerTypes), groupBy, "NONE").entries.associate { groupKeyOnly(it.key) to it.value.net }
        }

        val rows = buckets.entries
            .sortedByDescending { it.value.net }
            .take(limit)
            .map { (key, acc) -> toRow(acc, totalNet, costVisible, priorNetByKey[groupKeyOnly(key)], cmpPeriod != null) }

        val totalsAcc = Acc().apply {
            groups = emptyList()
            buckets.values.forEach { add(it) }
        }
        val totals = toRow(totalsAcc, totalNet, costVisible, null, false)

        SalesAnalyticsResult(groupBy, interval, SciPeriod(from, to), cmpPeriod, rows, totals, costVisible)
    }

    private fun fetch(from: LocalDate, to: LocalDate, locationIds: Set<UUID>?, customerTypes: Set<String>?): List<SalesLineRef> =
        sales.linesFor(SalesLineFilter(completedTo = to, from = from, locationIds = locationIds, customerTypes = customerTypes, withCost = true))

    private fun aggregate(lines: List<SalesLineRef>, groupBy: List<String>, interval: String): Map<String, Acc> {
        val out = LinkedHashMap<String, Acc>()
        lines.forEach { line ->
            val groups = groupBy.map { dim -> groupOf(dim, line) }
            val bucket = interval(interval, line.saleDate)
            val key = groups.joinToString("¦") { "${it.dimension}=${it.key}" } + "§" + (bucket ?: "")
            val acc = out.getOrPut(key) { Acc().also { it.groups = groups; it.interval = bucket } }
            acc.add(line)
        }
        return out
    }

    /** The grouping part of a bucket key, without the interval — for matching comparison periods. */
    private fun groupKeyOnly(bucketKey: String): String = bucketKey.substringBefore("§")

    private fun toRow(acc: Acc, totalNet: BigDecimal, costVisible: Boolean, priorNet: BigDecimal?, withCmp: Boolean): SalesAnalyticsRow {
        val net = Money.round(acc.net)
        val cogs = if (costVisible) Money.round(acc.cogs) else null
        val margin = if (costVisible) Money.round(acc.net - acc.cogs) else null
        val comparison = if (withCmp) {
            val p = priorNet ?: BigDecimal.ZERO
            ComparisonCell(Money.round(p), if (p.signum() != 0) pct(acc.net - p, p.abs()) else null)
        } else null
        return SalesAnalyticsRow(
            group = acc.groups,
            interval = acc.interval,
            quantity = acc.quantity,
            transactions = acc.txns.size.toLong(),
            gross = Money.round(acc.gross),
            discounts = Money.round(acc.discounts),
            net = net,
            vat = Money.round(acc.vat),
            cogs = cogs,
            grossMargin = margin,
            grossMarginPct = if (costVisible && acc.net.signum() != 0) pct(acc.net - acc.cogs, acc.net) else null,
            avgSellingPrice = if (acc.quantity.signum() != 0) acc.net.divide(acc.quantity, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO,
            shareOfNetPct = if (totalNet.signum() != 0) pct(acc.net, totalNet) else BigDecimal.ZERO,
            comparison = comparison,
        )
    }

    private fun groupOf(dim: String, line: SalesLineRef): AnalyticsGroup = when (dim) {
        "PRODUCT" -> AnalyticsGroup(dim, line.stockItemId.toString(), line.itemName, ref(line.stockItemId, Ref::stockItem))
        "CATEGORY" -> (line.categoryName ?: "Uncategorised").let { AnalyticsGroup(dim, it, it, null) }
        "LOCATION" -> AnalyticsGroup(dim, line.locationId?.toString() ?: "—", null, ref(line.locationId, Ref::location))
        "CUSTOMER_TYPE" -> (line.customerType ?: "UNKNOWN").let { AnalyticsGroup(dim, it, it, null) }
        "CUSTOMER" -> AnalyticsGroup(dim, line.customerId?.toString() ?: "WALK_IN", line.customerName, ref(line.customerId, Ref::customer))
        "CORRESPONDENT" -> AnalyticsGroup(dim, line.correspondentId?.toString() ?: "NONE", null, ref(line.correspondentId, Ref::correspondent))
        "CASHIER" -> AnalyticsGroup(dim, line.cashierUserId?.toString() ?: "—", null, line.cashierUserId?.let { Ref.user(it) })
        "CHANNEL" -> (line.channel ?: "UNKNOWN").let { AnalyticsGroup(dim, it, it, null) }
        else -> throw ApiErrors.validationFailed(mapOf("groupBy" to "Unknown dimension $dim"))
    }

    private fun interval(interval: String, date: LocalDate): String? = when (interval) {
        "DAY" -> date.toString()
        "WEEK" -> "%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        "MONTH" -> "%d-%02d".format(date.year, date.monthValue)
        else -> null
    }

    private fun shiftPeriod(from: LocalDate, to: LocalDate, compare: String): Pair<LocalDate, LocalDate> =
        if (compare == "SAME_PERIOD_LAST_YEAR") from.minusYears(1) to to.minusYears(1)
        else {
            val days = ChronoUnit.DAYS.between(from, to) + 1
            val pt = from.minusDays(1); pt.minusDays(days - 1) to pt
        }

    private fun pct(part: BigDecimal, whole: BigDecimal): BigDecimal =
        part.divide(whole, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)

    private class Acc {
        var quantity: BigDecimal = BigDecimal.ZERO
        val txns = HashSet<UUID>()
        var gross: BigDecimal = BigDecimal.ZERO
        var discounts: BigDecimal = BigDecimal.ZERO
        var net: BigDecimal = BigDecimal.ZERO
        var vat: BigDecimal = BigDecimal.ZERO
        var cogs: BigDecimal = BigDecimal.ZERO
        var groups: List<AnalyticsGroup> = emptyList()
        var interval: String? = null

        fun add(line: SalesLineRef) {
            quantity += line.quantity
            txns += line.saleId
            gross += line.grossAmount
            discounts += line.discountTotal
            net += line.lineNet
            vat += line.vatAmount
            line.landingCostAtSale?.let { cogs += it.multiply(line.quantity) }
        }

        fun add(other: Acc) {
            quantity += other.quantity
            txns += other.txns
            gross += other.gross
            discounts += other.discounts
            net += other.net
            vat += other.vat
            cogs += other.cogs
        }
    }

    companion object {
        private val DIMENSIONS = setOf("PRODUCT", "CATEGORY", "LOCATION", "CUSTOMER_TYPE", "CUSTOMER", "CORRESPONDENT", "CASHIER", "CHANNEL")
        private val INTERVALS = setOf("NONE", "DAY", "WEEK", "MONTH")
    }
}
