package com.zifiq.account.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

// Exposed mappings of V11_2 (docs/07 §2.7, §2.10, §2.12).

object PaymentReceiptsTable : UUIDTable("payment_receipts") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val kind = varchar("kind", 10)
    val locationId = uuid("location_id").nullable()
    val cashAccountId = uuid("cash_account_id").nullable()
    val cashierUserId = uuid("cashier_user_id")
    val customerId = uuid("customer_id").nullable()
    val customerName = varchar("customer_name", 200).nullable()
    val channel = varchar("channel", 20)
    val amount = decimal("amount", 14, 2)
    val sourceModule = varchar("source_module", 30)
    val sourceType = varchar("source_type", 30)
    val sourceId = uuid("source_id")
    val sourceRef = varchar("source_ref", 40)
    val incomeCategory = varchar("income_category", 20).nullable()
    val description = varchar("description", 300).nullable()
    val receivingBankAccountId = uuid("receiving_bank_account_id").nullable()
    val terminalRef = varchar("terminal_ref", 40).nullable()
    val payerReference = varchar("payer_reference", 80).nullable()
    val customerLedgerEntryId = uuid("customer_ledger_entry_id").nullable()
    val confirmationStatus = varchar("confirmation_status", 20)
    val reviewedByUserId = uuid("reviewed_by_user_id").nullable()
    val reviewedAt = timestamp("reviewed_at").nullable()
    val declineNote = varchar("decline_note", 300).nullable()
    val cashTransactionId = uuid("cash_transaction_id").nullable()
    val bankTransactionId = uuid("bank_transaction_id").nullable()
    val status = varchar("status", 20)
    val voidReason = varchar("void_reason", 300).nullable()
    val voidedByUserId = uuid("voided_by_user_id").nullable()
    val voidedAt = timestamp("voided_at").nullable()
    val receivedAt = timestamp("received_at")
}

object WalletFundingsTable : UUIDTable("wallet_fundings") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val customerId = uuid("customer_id")
    val customerCode = varchar("customer_code", 20)
    val customerName = varchar("customer_name", 200)
    val balanceType = varchar("balance_type", 10)
    val channel = varchar("channel", 20)
    val amount = decimal("amount", 14, 2)
    val locationId = uuid("location_id").nullable()
    val paymentReceiptId = uuid("payment_receipt_id")
    val customerLedgerEntryId = uuid("customer_ledger_entry_id").nullable()
    val status = varchar("status", 25)
    val createdByUserId = uuid("created_by_user_id")
    val createdAt = timestamp("created_at")
}

object WalletWithdrawalsTable : UUIDTable("wallet_withdrawals") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val customerId = uuid("customer_id")
    val customerCode = varchar("customer_code", 20)
    val customerName = varchar("customer_name", 200)
    val balanceType = varchar("balance_type", 10)
    val reason = varchar("reason", 40)
    val initiatedBy = varchar("initiated_by", 10)
    val amount = decimal("amount", 14, 2)
    val balanceBefore = decimal("balance_before", 14, 2)
    val balanceAfter = decimal("balance_after", 14, 2)
    val payoutKind = varchar("payout_kind", 10)
    val payoutCashAccountId = uuid("payout_cash_account_id").nullable()
    val payoutBankAccountId = uuid("payout_bank_account_id").nullable()
    val paidVia = varchar("paid_via", 120).nullable()
    val otpChallengeId = uuid("otp_challenge_id").nullable()
    val authorisedByUserId = uuid("authorised_by_user_id").nullable()
    val note = varchar("note", 300).nullable()
    val withdrawalDate = date("withdrawal_date")
    val locationId = uuid("location_id").nullable()
    val customerLedgerEntryId = uuid("customer_ledger_entry_id")
    val cashTransactionId = uuid("cash_transaction_id").nullable()
    val bankTransactionId = uuid("bank_transaction_id").nullable()
    val auditStatus = varchar("audit_status", 20)
    val reversalReason = varchar("reversal_reason", 300).nullable()
    val reversedByUserId = uuid("reversed_by_user_id").nullable()
    val reversedAt = timestamp("reversed_at").nullable()
    val createdByUserId = uuid("created_by_user_id")
    val createdAt = timestamp("created_at")
}

object LocationVatSettingsTable : UUIDTable("location_vat_settings") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val vatEnabled = bool("vat_enabled")
    val vatRate = decimal("vat_rate", 5, 2)
    val vatRegistrationNumber = varchar("vat_registration_number", 60).nullable()
    val effectiveFrom = date("effective_from")
    val note = varchar("note", 300).nullable()
    val updatedByUserId = uuid("updated_by_user_id")
    val createdAt = timestamp("created_at")
}
