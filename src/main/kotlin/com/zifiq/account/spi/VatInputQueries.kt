package com.zifiq.account.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** One input-VAT-bearing running cost, for Audit's VAT report input side (docs/10 §4.6). */
data class InputVatLineRef(
    val date: LocalDate,
    val ref: String?,
    val counterparty: String,
    val counterpartyTin: String?,
    /** VAT-exclusive base = amount − input VAT (a cost figure). */
    val taxableAmount: BigDecimal,
    val vat: BigDecimal,
)

interface VatInputQueries {
    /** APPROVED running costs at [locationId] with input VAT, paid in [from]..[to]. */
    fun runningCostInputVat(locationId: UUID, from: LocalDate, to: LocalDate): List<InputVatLineRef>
}
