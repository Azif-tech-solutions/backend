package com.zifiq.account.service

import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.BankTransactionsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.CashTransactionsTable
import com.zifiq.account.model.PaymentReceiptsTable
import com.zifiq.account.model.WalletWithdrawalsTable
import com.zifiq.common.integrity.IntegrityChecks
import com.zifiq.common.integrity.IntegrityFindingRef
import com.zifiq.platform.tenancy.TenantScopedTransaction
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Finance & Accounting integrity checks (docs/10 §4.10): each cash/bank account's stored balance
 * agrees with its transaction ledger, no dealer withdrawal is missing its OTP, and every confirmed
 * monetary receipt has its cash/bank posting. Convention-agnostic: the stored balance is compared
 * to the newest transaction's running `balance_after` (opening if there are none).
 */
@Service
class AccountIntegrityChecks(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
) : IntegrityChecks {
    override fun runIntegrityChecks(): List<IntegrityFindingRef> = tx.execute {
        buildList {
            addAll(cashAccounts())
            addAll(bankAccounts())
            addAll(dealerWithdrawalsWithoutOtp())
            addAll(unpostedReceipts())
        }
    }

    private fun cashAccounts(): List<IntegrityFindingRef> =
        CashAccountsTable.select(CashAccountsTable.id, CashAccountsTable.code, CashAccountsTable.openingBalance, CashAccountsTable.currentBalance)
            .where { CashAccountsTable.tenantId eq support.tenantId() }
            .mapNotNull { acct ->
                val id = acct[CashAccountsTable.id].value
                val latest = CashTransactionsTable.select(CashTransactionsTable.balanceAfter).where { CashTransactionsTable.cashAccountId eq id }
                    .orderBy(CashTransactionsTable.createdAt to SortOrder.DESC).limit(1).firstOrNull()?.get(CashTransactionsTable.balanceAfter)
                val expected = latest ?: acct[CashAccountsTable.openingBalance]
                val actual = acct[CashAccountsTable.currentBalance]
                if (expected.compareTo(actual) != 0) finding("CASH_ACCOUNT_MATCHES_LEDGER", "CRITICAL", "cash_account", acct[CashAccountsTable.code], expected, actual,
                    "Cash account ${acct[CashAccountsTable.code]} balance disagrees with its ledger") else null
            }

    private fun bankAccounts(): List<IntegrityFindingRef> =
        BankAccountsTable.select(BankAccountsTable.id, BankAccountsTable.code, BankAccountsTable.openingBalance, BankAccountsTable.currentBalance)
            .where { BankAccountsTable.tenantId eq support.tenantId() }
            .mapNotNull { acct ->
                val id = acct[BankAccountsTable.id].value
                val latest = BankTransactionsTable.select(BankTransactionsTable.balanceAfter).where { BankTransactionsTable.bankAccountId eq id }
                    .orderBy(BankTransactionsTable.createdAt to SortOrder.DESC).limit(1).firstOrNull()?.get(BankTransactionsTable.balanceAfter)
                val expected = latest ?: acct[BankAccountsTable.openingBalance]
                val actual = acct[BankAccountsTable.currentBalance]
                if (expected.compareTo(actual) != 0) finding("BANK_ACCOUNT_MATCHES_LEDGER", "CRITICAL", "bank_account", acct[BankAccountsTable.code], expected, actual,
                    "Bank account ${acct[BankAccountsTable.code]} balance disagrees with its ledger") else null
            }

    private fun dealerWithdrawalsWithoutOtp(): List<IntegrityFindingRef> =
        WalletWithdrawalsTable.select(WalletWithdrawalsTable.ref)
            .where {
                (WalletWithdrawalsTable.tenantId eq support.tenantId()) and (WalletWithdrawalsTable.initiatedBy eq "DEALER") and
                    (WalletWithdrawalsTable.auditStatus neq "REVERSED") and WalletWithdrawalsTable.otpChallengeId.isNull()
            }
            .map { finding("WITHDRAWAL_OTP_MISSING", "CRITICAL", "wallet_withdrawal", it[WalletWithdrawalsTable.ref], "verified OTP", "none", "Dealer withdrawal ${it[WalletWithdrawalsTable.ref]} has no verified OTP") }

    private fun unpostedReceipts(): List<IntegrityFindingRef> =
        PaymentReceiptsTable.select(PaymentReceiptsTable.ref)
            .where {
                (PaymentReceiptsTable.tenantId eq support.tenantId()) and (PaymentReceiptsTable.status eq "ISSUED") and
                    (PaymentReceiptsTable.confirmationStatus inList listOf("APPROVED", "NOT_REQUIRED")) and
                    (PaymentReceiptsTable.channel inList listOf("CASH", "POS", "FUND_TRANSFER", "CHEQUE")) and
                    PaymentReceiptsTable.cashTransactionId.isNull() and PaymentReceiptsTable.bankTransactionId.isNull()
            }
            .map { finding("RECEIPT_POSTED", "WARNING", "payment_receipt", it[PaymentReceiptsTable.ref], "a cash/bank posting", "none", "Receipt ${it[PaymentReceiptsTable.ref]} is confirmed but not posted") }

    private fun finding(code: String, severity: String, type: String, ref: String, expected: Any?, actual: Any?, message: String) =
        IntegrityFindingRef(code, severity, type, ref, expected?.toString(), actual?.toString(), message)
}
