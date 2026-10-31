package com.zifiq.history.dto

import com.zifiq.platform.directory.Ref
import java.math.BigDecimal
import java.time.LocalDate

/** A VAT rate in force from a date (header context for a mid-period rate change). */
data class VatRateChange(val rate: BigDecimal, val effectiveFrom: LocalDate)

/** One line of the VAT report (docs/10 §4.6). Input lines' taxableAmount is a cost figure (masked without factory.costing.read). */
data class VatReportRow(
    val date: LocalDate,
    val document: String?,
    val counterparty: String,
    val counterpartyTin: String?,
    val description: String,
    /** "Output" or "Input". */
    val type: String,
    val taxableAmount: BigDecimal,
    val rate: BigDecimal,
    val vat: BigDecimal,
)

data class VatReportHeader(
    val location: Ref,
    val vatEnabled: Boolean,
    val currentRate: BigDecimal,
    val rateChanges: List<VatRateChange>,
    val outputVat: BigDecimal,
    val outputBase: BigDecimal,
    val inputVat: BigDecimal,
    val inputBase: BigDecimal,
    /** output − input; negative is a credit carried forward. */
    val netVatPayable: BigDecimal,
    val costVisible: Boolean,
)

data class VatReport(val header: VatReportHeader, val rows: List<VatReportRow>)

/** One location's line of the VAT return summary (docs/10 §4.6). */
data class VatReturnRow(
    val location: Ref?,
    val vatRegistrationNumber: String?,
    val outputBase: BigDecimal,
    val outputVat: BigDecimal,
    val creditNoteVat: BigDecimal,
    val inputVat: BigDecimal,
    val netPayable: BigDecimal,
    val salesCount: Int,
    val zeroRatedSales: BigDecimal,
    val exemptSales: BigDecimal,
)

/** The VAT return for a period: one row per location with activity, plus a company total (location = null). */
data class VatReturn(val period: String, val rows: List<VatReturnRow>, val companyTotal: VatReturnRow)
