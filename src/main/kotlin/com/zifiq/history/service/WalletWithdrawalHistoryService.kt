package com.zifiq.history.service

import com.zifiq.account.spi.WalletWithdrawalQueries
import com.zifiq.account.spi.WithdrawalAuditRow
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.WalletWithdrawalHistoryRow
import com.zifiq.history.model.AuditReviewsTable
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.tenancy.TenantScopedTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Wallet Withdrawal History (docs/10 §4.5): the account module's withdrawals (through its SPI),
 * joined in memory to this module's audit reviews, with the OTP and breach flags computed here.
 */
@Service
class WalletWithdrawalHistoryService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
    private val withdrawals: WalletWithdrawalQueries,
) {
    fun list(request: ListSearchRequest): PageResult<WalletWithdrawalHistoryRow> = tx.execute {
        val page = withdrawals.withdrawalsForAudit(request)
        val ids = page.items.map { it.id }
        val reviews = if (ids.isEmpty()) emptyMap() else AuditReviewsTable.selectAll()
            .where { (AuditReviewsTable.tenantId eq support.tenantId()) and (AuditReviewsTable.recordType eq WALLET_WITHDRAWAL) and (AuditReviewsTable.recordId inList ids) }
            .associate { it[AuditReviewsTable.recordId] to it[AuditReviewsTable.status] }
        val underReview = AuditReviewsTable.selectAll()
            .where { (AuditReviewsTable.tenantId eq support.tenantId()) and (AuditReviewsTable.recordType eq WALLET_WITHDRAWAL) and (AuditReviewsTable.status inList listOf("UNDER_REVIEW", "FLAGGED")) }
            .count()
        val summary = page.summary?.plus("underReview" to underReview)
        page.withItems(page.items.map { toRow(it, reviews[it.id]) }).copy(summary = summary)
    }

    fun filterMetadata(): FilterMetadata = withdrawals.withdrawalAuditFilters()

    private fun toRow(r: WithdrawalAuditRow, reviewStatus: String?) = WalletWithdrawalHistoryRow(
        ref = r.ref,
        date = r.date,
        dealer = Ref.customer(r.customerId),
        dealerCode = r.customerCode,
        initiatedBy = r.initiatedBy,
        reason = r.reason,
        balanceBefore = r.balanceBefore,
        amount = r.amount,
        balanceAfter = r.balanceAfter,
        payoutKind = r.payoutKind,
        paidVia = r.paidVia,
        otp = when {
            r.otpVerified -> "Verified"
            r.initiatedBy == "DEALER" -> "Missing"
            else -> "Not required — Management authorised"
        },
        processedBy = UserRef(r.processedByUserId),
        authorisedBy = UserRef.of(r.authorisedByUserId),
        status = r.status,
        auditReview = reviewStatus?.let(AuditReviewService::statusLabel),
        breach = r.balanceAfter.signum() < 0 || (r.initiatedBy == "DEALER" && !r.otpVerified),
    )

    companion object {
        const val WALLET_WITHDRAWAL = "WALLET_WITHDRAWAL"
    }
}
