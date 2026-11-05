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
