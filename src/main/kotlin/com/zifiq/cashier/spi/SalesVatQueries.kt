package com.zifiq.cashier.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** One VAT-bearing sale line, for Audit's VAT report output side (docs/10 §4.6). */
data class SaleVatLineRef(
    val date: LocalDate,
    val saleRef: String,
    val invoiceNumber: String?,
    val customerName: String,
    val taxableAmount: BigDecimal,
    val rate: BigDecimal,
    val vat: BigDecimal,
    /** The line's VAT category code (STANDARD_RATE / ZERO_RATED / EXEMPT / …) — the return separates zero-rated and exempt. */
    val vatCategoryCode: String,
)

interface SalesVatQueries {
    /** Lines of COMPLETED, non-voided sales at [locationId] with a business date in [from]..[to]. */
    fun outputVatLines(locationId: UUID, from: LocalDate, to: LocalDate): List<SaleVatLineRef>
}
