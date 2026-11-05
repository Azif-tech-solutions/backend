package com.zifiq.communication.dto

import java.math.BigDecimal

/** One Comms. Report row (docs/12 §4.4): a beneficiary's commission for one approved-run period. */
data class CommsReportRow(
    val agent: String,
    val code: String,
    val region: String?,
    val period: String,
    val sales: BigDecimal,
    val earned: BigDecimal,
    val paid: BigDecimal,
    val outstanding: BigDecimal,
    /** "Unpaid" | "Part paid" | "Settled". */
    val status: String,
)

/** Correspondents Report, by rep (docs/12 §4.5). */
data class CorrespondentRepRow(
    val rep: String,
    val repCode: String,
    val region: String?,
    val status: String,
    val customers: Int,
    val newCustomers: Int,
    val transactions: Int,
    val units: BigDecimal,
    val revenue: BigDecimal,
    val sharePct: BigDecimal,
    val pendingConfirmationValue: BigDecimal,
)

/** Correspondents Report, by customer. */
data class CorrespondentCustomerRow(
    val rep: String,
    val repCode: String,
    val customer: String,
    val customerCode: String?,
    val newCustomer: Boolean,
    val transactions: Int,
    val units: BigDecimal,
    val revenue: BigDecimal,
    val pendingConfirmationValue: BigDecimal,
)

data class CorrespondentsReport(
    val view: String,
    val reps: List<CorrespondentRepRow>?,
    val customers: List<CorrespondentCustomerRow>?,
    val stats: Map<String, Any?>,
)
