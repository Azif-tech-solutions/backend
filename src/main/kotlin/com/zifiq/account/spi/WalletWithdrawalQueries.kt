package com.zifiq.account.spi

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A wallet withdrawal / deposit refund, for Audit's Wallet Withdrawal History (docs/10 §4.5). The
 * same rows and filters as `GET /api/v1/account/wallet-withdrawals`, as plain audit data; the audit
 * module overlays its own review state and the breach flags. Ids are plain here — the audit module
 * wraps them in references for its response.
 */
data class WithdrawalAuditRow(
    val id: UUID,
    val ref: String,
    val date: LocalDate,
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val initiatedBy: String,
    val reason: String,
    val balanceBefore: BigDecimal,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val payoutKind: String,
    val paidVia: String?,
    val otpVerified: Boolean,
    val processedByUserId: UUID,
    val authorisedByUserId: UUID?,
    val status: String,
    val locationId: UUID?,
)

interface WalletWithdrawalQueries {
    /** Page of withdrawals for the audit view; summary carries totalWithdrawn / dealerInitiated / companyInitiated. */
    fun withdrawalsForAudit(request: ListSearchRequest): PageResult<WithdrawalAuditRow>

    fun withdrawalAuditFilters(): FilterMetadata
}
