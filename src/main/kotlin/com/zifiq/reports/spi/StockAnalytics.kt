package com.zifiq.reports.spi

import com.zifiq.reports.dto.DaysOfCoverRow
import com.zifiq.reports.dto.SlowMovingRow
import com.zifiq.reports.dto.StockValueRow
import java.time.LocalDate
import java.util.UUID

/**
 * Reports & Analytics' published stock aggregation (docs/13 §5): stock value, slow-moving/dead stock
 * and days of cover for the Dashboard and AI. Value is masked by the caller's `factory.costing.read`.
 * Runs in the caller's transaction.
 */
interface StockAnalytics {
    /** Stock value as at [asOf], grouped by `LOCATION`, `CATEGORY` or `ITEM_TYPE`. */
    fun valueBy(groupBy: String, asOf: LocalDate, locationIds: Set<UUID>? = null): List<StockValueRow>

    /** Item×location with stock on hand not moved within the slow/dead thresholds, as at [asOf]. */
    fun slowMoving(asOf: LocalDate, locationIds: Set<UUID>? = null): List<SlowMovingRow>

    /** Days of cover from the last 30 days' sales, per item×location, as at [asOf]. */
    fun daysOfCover(asOf: LocalDate, locationIds: Set<UUID>? = null): List<DaysOfCoverRow>
}
