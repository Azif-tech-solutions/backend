package com.zifiq.communication.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/** A commission payment against a run (docs/12 §2.3). Partial payments allowed; outstanding = earned − Σ PAID. */
object CommissionPaymentsTable : UUIDTable("commission_payments") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val runId = uuid("run_id")
    val beneficiaryType = varchar("beneficiary_type", 14)
    val beneficiaryId = uuid("beneficiary_id")
    val beneficiaryName = varchar("beneficiary_name", 200)
    val amount = decimal("amount", 14, 2)
    val method = varchar("method", 10)
    val cashAccountId = uuid("cash_account_id").nullable()
    val bankAccountId = uuid("bank_account_id").nullable()
    val paidVia = varchar("paid_via", 120).nullable()
    val accountCashTransactionId = uuid("account_cash_transaction_id").nullable()
    val accountBankTransactionId = uuid("account_bank_transaction_id").nullable()
    val customerLedgerEntryId = uuid("customer_ledger_entry_id").nullable()
    val paidAt = timestamp("paid_at")
    val paidByUserId = reference("paid_by_user_id", AppUsersTable)
    val note = varchar("note", 300).nullable()
    val status = varchar("status", 10)
    val reversedReason = varchar("reversed_reason", 300).nullable()
    val reversedByUserId = reference("reversed_by_user_id", AppUsersTable).nullable()
}
