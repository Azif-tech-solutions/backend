package com.zifiq.reports.spi

import com.zifiq.reports.dto.SalesAnalyticsRow
import java.time.LocalDate
import java.util.UUID

/** What to aggregate (docs/13 §4.4). [groupBy] is one or two of the dimension names; see SalesAnalyticsService. */
data class SalesAnalyticsQuery(
    val from: LocalDate,
    val to: LocalDate,
    val groupBy: List<String>,
    val interval: String = "NONE",
    val locationIds: Set<UUID>? = null,
    val customerTypes: Set<String>? = null,
    val top: Int = 20,
    val compare: String = "NONE",
)

/**
 * Reports & Analytics' published sales aggregation (docs/13 §5): grouped sales rows for the Dashboard
 * and AI. Cost measures are masked by the caller's own `factory.costing.read`. Runs in the caller's
 * transaction.
 */
interface SalesAnalytics {
    fun aggregate(query: SalesAnalyticsQuery): List<SalesAnalyticsRow>
}
