package com.zifiq.cashier.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Which completed sale lines to read for commission allocation (docs/12 §5). */
data class SalesLineFilter(
    /** Sales completed on or before this date (the end of the run's period). */
    val completedTo: LocalDate,
    val locationId: UUID? = null,
)

/**
 * One completed sale line, for commission allocation (docs/12). `receiptsConfirmed` is true when no
 * receipt on the sale is still PENDING or was DECLINED (NOT_REQUIRED / APPROVED only). `invoicePaid`
 * is true when nothing was left on account. The caller (Communication) decides which lines qualify
 * and excludes any it has already allocated — cashier does not read commission tables.
 */
data class SalesLineRef(
    val saleId: UUID,
    val saleLineId: UUID,
    val saleDate: LocalDate,
    val saleRef: String,
    val customerId: UUID?,
    val customerType: String?,
    val correspondentId: UUID?,
    val stockItemId: UUID,
    val itemCode: String,
    val itemName: String,
    val quantity: BigDecimal,
    val lineNet: BigDecimal,
    val receiptsConfirmed: Boolean,
    val invoicePaid: Boolean,
)

interface SalesQueries {
    /** Lines of COMPLETED, non-voided sales matching [filter]. Runs in the caller's transaction. */
    fun linesFor(filter: SalesLineFilter): List<SalesLineRef>
}
