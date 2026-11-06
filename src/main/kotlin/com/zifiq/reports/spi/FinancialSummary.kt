package com.zifiq.reports.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A compact financial snapshot of a period, for the Dashboard and AI (docs/13 §4.2). Cost-derived
 * figures are null when the caller lacks `factory.costing.read` — the same masking the SCI applies.
 */
data class FinancialSnapshot(
    val from: LocalDate,
    val to: LocalDate,
    val revenue: BigDecimal,
    val costOfSales: BigDecimal?,
    val grossProfit: BigDecimal?,
    val operatingExpenses: BigDecimal,
    val netProfit: BigDecimal?,
    val closingStockValue: BigDecimal?,
    val costVisible: Boolean,
)

/** Reports & Analytics' published read interface (docs/13 §5): the SCI figures, for the Dashboard and AI. */
interface FinancialSummary {
    /** The period's figures, company-wide or for one location. Runs in the caller's transaction. */
    fun snapshot(from: LocalDate, to: LocalDate, locationId: UUID? = null): FinancialSnapshot
}
