package com.zifiq.reports.dto

import com.zifiq.platform.directory.Ref
import java.math.BigDecimal
import java.time.LocalDate

/** Stock value grouped by location, category or item type (docs/13 §4.5 VALUE). Cost fields are null without cost visibility. */
data class StockValueRow(
    val group: AnalyticsGroup,
    val quantity: BigDecimal,
    val value: BigDecimal?,
    val shareOfValuePct: BigDecimal?,
)

/** An item×location whose stock is not moving (docs/13 §4.5 SLOW_MOVING). `classification` is SLOW or DEAD. */
data class SlowMovingRow(
    val item: Ref?,
    val location: Ref?,
    val onHand: BigDecimal,
    val value: BigDecimal?,
    val lastOutboundAt: LocalDate?,
    val daysSinceOutbound: Long?,
    val classification: String,
)

/** Days of cover per item×location from the last 30 days' sales (docs/13 §4.5 DAYS_OF_COVER). */
data class DaysOfCoverRow(
    val item: Ref?,
    val location: Ref?,
    val available: BigDecimal,
    val avgDailySales: BigDecimal,
    val daysOfCover: BigDecimal?,
    val reorderLevel: BigDecimal,
    val belowReorder: Boolean,
)

data class StockAnalyticsResult(
    val view: String,
    val asOf: LocalDate,
    val value: List<StockValueRow>? = null,
    val slowMoving: List<SlowMovingRow>? = null,
    val daysOfCover: List<DaysOfCoverRow>? = null,
    val costVisible: Boolean,
)
