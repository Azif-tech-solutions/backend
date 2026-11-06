package com.zifiq.cashier.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Which completed sale lines to read (docs/12 §5 for commission allocation, docs/13 §4.4 for sales
 * analytics). The analytics-only fields default to "no extra narrowing"; [withCost] asks for each
 * line's `landingCostAtSale` (an extra ledger read the commission path does not need).
 */
data class SalesLineFilter(
    /** Sales completed on or before this date (the end of the run's period). */
    val completedTo: LocalDate,
    val locationId: UUID? = null,
    /** Sales completed on or after this date (analytics range lower bound). */
    val from: LocalDate? = null,
    /** Any of these selling locations (analytics); applied together with [locationId] when both are set. */
    val locationIds: Set<UUID>? = null,
    /** Any of these customer types (analytics). */
    val customerTypes: Set<String>? = null,
    /** One sales channel (analytics). */
    val channel: String? = null,
    /** Populate `landingCostAtSale` from the POS_SALE ledger cost (analytics COGS). */
    val withCost: Boolean = false,
)

/**
 * One completed sale line. `receiptsConfirmed` is true when no receipt on the sale is still PENDING or
 * was DECLINED (NOT_REQUIRED / APPROVED only); `invoicePaid` is true when nothing was left on account.
 * The commission caller decides which lines qualify and excludes any it has already allocated.
 * `landingCostAtSale` is the POS_SALE ledger unit cost captured at checkout (docs/09 §5), populated only
 * when the filter's `withCost` is set, and masked by the consuming report — cashier never masks it.
 */
data class SalesLineRef(
    val saleId: UUID,
    val saleLineId: UUID,
    val saleDate: LocalDate,
    val saleRef: String,
    val customerId: UUID?,
    val customerCode: String?,
    val customerName: String,
    val customerType: String?,
    val correspondentId: UUID?,
    val stockItemId: UUID,
    val itemCode: String,
    val itemName: String,
    val quantity: BigDecimal,
    val lineNet: BigDecimal,
    val receiptsConfirmed: Boolean,
    val invoicePaid: Boolean,
    /** The selling location of the sale (for the SCI revenue split by location type). */
    val locationId: UUID? = null,
    // ---- analytics dimensions/measures (docs/13 §4.4); defaults keep the commission path unchanged ----
    val cashierUserId: UUID? = null,
    val channel: String? = null,
    val categoryName: String? = null,
    val grossAmount: BigDecimal = BigDecimal.ZERO,
    val discountTotal: BigDecimal = BigDecimal.ZERO,
    val vatAmount: BigDecimal = BigDecimal.ZERO,
    val landingCostAtSale: BigDecimal? = null,
)

interface SalesQueries {
    /** Lines of COMPLETED, non-voided sales matching [filter]. Runs in the caller's transaction. */
    fun linesFor(filter: SalesLineFilter): List<SalesLineRef>

    /** The earliest COMPLETED sale date for each of [customerIds] — for "brought in" detection (docs/12 §4.5). */
    fun firstCompletedSaleDates(customerIds: Collection<UUID>): Map<UUID, LocalDate>
}
