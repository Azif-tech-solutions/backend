package com.zifiq.reports.dto

import com.zifiq.platform.directory.Ref
import java.math.BigDecimal

/** One grouping dimension value: a stable [key], a display [label] when known, and a [ref] for id dimensions. */
data class AnalyticsGroup(val dimension: String, val key: String, val label: String?, val ref: Ref?)

/** The comparison period's net for the same group, with the change. */
data class ComparisonCell(val net: BigDecimal, val changePct: BigDecimal?)

/**
 * One aggregated sales row (docs/13 §4.4). [group] holds one entry per `groupBy` dimension; [interval]
 * is the bucket (e.g. "2026-08") or null. Cost measures (`cogs`, `grossMargin`, `grossMarginPct`) are
 * null without `factory.costing.read`.
 */
data class SalesAnalyticsRow(
    val group: List<AnalyticsGroup>,
    val interval: String?,
    val quantity: BigDecimal,
    val transactions: Long,
    val gross: BigDecimal,
    val discounts: BigDecimal,
    val net: BigDecimal,
    val vat: BigDecimal,
    val cogs: BigDecimal?,
    val grossMargin: BigDecimal?,
    val grossMarginPct: BigDecimal?,
    val avgSellingPrice: BigDecimal,
    val shareOfNetPct: BigDecimal,
    val comparison: ComparisonCell?,
)

data class SalesAnalyticsResult(
    val groupBy: List<String>,
    val interval: String,
    val period: SciPeriod,
    val comparison: SciPeriod?,
    val rows: List<SalesAnalyticsRow>,
    val totals: SalesAnalyticsRow,
    val costVisible: Boolean,
)
