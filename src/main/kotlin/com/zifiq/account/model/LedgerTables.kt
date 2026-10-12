package com.zifiq.account.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

// Exposed mappings of V11 (docs/07 §2.1–2.4, §2.11).

object ExpenseCategoriesTable : UUIDTable("expense_categories") {
    val tenantId = reference("tenant_id", TenantsTable)
    val code = varchar("code", 20)
    val name = varchar("name", 160)
    val description = varchar("description", 300).nullable()
    val accountHead = varchar("account_head", 20)
    val monthlyCap = decimal("monthly_cap", 14, 2).nullable()
    val pettyCashAllowed = bool("petty_cash_allowed")
    val status = varchar("status", 20)
    val createdByUserId = uuid("created_by_user_id").nullable()
    val createdAt = timestamp("created_at")
}

object BankAccountsTable : UUIDTable("bank_accounts") {
    val tenantId = reference("tenant_id", TenantsTable)
    val code = varchar("code", 20)
    val bankName = varchar("bank_name", 160)
    val branch = varchar("branch", 120).nullable()
    val accountName = varchar("account_name", 160)
    val accountNumber = varchar("account_number", 30)
    val accountType = varchar("account_type", 20)
    val currencyCode = varchar("currency_code", 3)
    val sortCodeOrBic = varchar("sort_code_or_bic", 40).nullable()
    val authorisedSignatory = varchar("authorised_signatory", 160).nullable()
    val relationshipManager = varchar("relationship_manager", 160).nullable()
    val dateOpened = date("date_opened").nullable()
    val openingBalance = decimal("opening_balance", 14, 2)
    val openingBalanceDate = date("opening_balance_date")
    val currentBalance = decimal("current_balance", 14, 2)
    val locationId = uuid("location_id").nullable()
    val status = varchar("status", 20)
    val createdByUserId = uuid("created_by_user_id")
    val createdAt = timestamp("created_at")
}

object BankTransactionsTable : UUIDTable("bank_transactions") {
    val tenantId = reference("tenant_id", TenantsTable)
    val bankAccountId = uuid("bank_account_id")
    val entryType = varchar("entry_type", 6)
    val amount = decimal("amount", 14, 2)
    val balanceAfter = decimal("balance_after", 14, 2)
    val reference = varchar("reference", 40)
    val description = varchar("description", 300)
    val sourceType = varchar("source_type", 30)
    val sourceId = uuid("source_id").nullable()
    val expenseCategoryId = uuid("expense_category_id").nullable()
    val entryDate = date("entry_date")
    val createdByUserId = uuid("created_by_user_id").nullable()
    val createdAt = timestamp("created_at")
}

object CashAccountsTable : UUIDTable("cash_accounts") {
    val tenantId = reference("tenant_id", TenantsTable)
    val code = varchar("code", 30)
    val name = varchar("name", 120)
    val kind = varchar("kind", 20)
    val locationId = uuid("location_id").nullable()
    val currencyCode = varchar("currency_code", 3)
    val openingBalance = decimal("opening_balance", 14, 2)
    val currentBalance = decimal("current_balance", 14, 2)
    val floatLimit = decimal("float_limit", 14, 2).nullable()
    val posTerminalRef = varchar("pos_terminal_ref", 40).nullable()
    val posSettlementBankAccountId = uuid("pos_settlement_bank_account_id").nullable()
    val status = varchar("status", 20)
    val createdByUserId = uuid("created_by_user_id")
    val createdAt = timestamp("created_at")
}

object CashTransactionsTable : UUIDTable("cash_transactions") {
    val tenantId = reference("tenant_id", TenantsTable)
    val cashAccountId = uuid("cash_account_id")
    val direction = varchar("direction", 3)
    val amount = decimal("amount", 14, 2)
    val balanceAfter = decimal("balance_after", 14, 2)
    val reference = varchar("reference", 40)
    val remark = varchar("remark", 300)
    val sourceType = varchar("source_type", 30)
    val sourceId = uuid("source_id").nullable()
    val expenseCategoryId = uuid("expense_category_id").nullable()
    val entryDate = date("entry_date")
    val createdByUserId = uuid("created_by_user_id").nullable()
    val createdAt = timestamp("created_at")
}

object ExchangeRatePublicationsTable : UUIDTable("exchange_rate_publications") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val publishedByUserId = uuid("published_by_user_id")
    val publishedAt = timestamp("published_at")
}

object ExchangeRatesTable : UUIDTable("exchange_rates") {
    val tenantId = reference("tenant_id", TenantsTable)
    val publicationId = uuid("publication_id")
    val currencyCode = varchar("currency_code", 3)
    val buyRate = decimal("buy_rate", 14, 6)
    val sellRate = decimal("sell_rate", 14, 6)
    val publishedAt = timestamp("published_at")
}
