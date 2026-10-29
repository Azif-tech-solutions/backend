package com.zifiq.history.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Wallet Withdrawal History row (docs/10 §4.5): the account module's withdrawal, with the audit
 * module's review badge and the integrity breach flag overlaid. References, never bare ids.
 */
data class WalletWithdrawalHistoryRow(
    val ref: String,
    val date: LocalDate,
    val dealer: Ref,
    val dealerCode: String,
    val initiatedBy: String,
    val reason: String,
    val balanceBefore: BigDecimal,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val payoutKind: String,
    val paidVia: String?,
    /** "Verified", "Missing" (a dealer request with no OTP — a breach) or "Not required — Management authorised". */
    val otp: String,
    val processedBy: UserRef,
    val authorisedBy: UserRef?,
    val status: String,
    /** "Under review" | "Reviewed" | "Flagged" | null. */
    val auditReview: String?,
    /** balanceAfter < 0, or a dealer-initiated withdrawal without a verified OTP (§4.5, §4.10). */
    val breach: Boolean,
)
