package com.zifiq.account.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---- Bank Register (docs/07 §4.1–4.2) ----

data class BankAccountRequest(
    @field:NotBlank @field:Size(max = 160) val bankName: String,
    @field:Size(max = 120) val branch: String? = null,
    @field:NotBlank @field:Size(max = 160) val accountName: String,
    @field:NotBlank @field:Pattern(regexp = "\\d{6,30}", message = "must be 6-30 digits") val accountNumber: String,
    @field:NotNull @field:Pattern(regexp = "CURRENT|COLLECTIONS|PAYROLL|SAVINGS|DOMICILIARY", message = "must be CURRENT, COLLECTIONS, PAYROLL, SAVINGS or DOMICILIARY")
    val accountType: String?,
    /** DOMICILIARY accounts only, and must be a tracked currency; everything else is NGN. */
    @field:Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") val currencyCode: String? = null,
    @field:Size(max = 40) val sortCodeOrBic: String? = null,
    @field:Size(max = 160) val authorisedSignatory: String? = null,
    @field:Size(max = 160) val relationshipManager: String? = null,
    val dateOpened: LocalDate? = null,
    val openingBalance: BigDecimal? = null,
    val openingBalanceDate: LocalDate? = null,
    val locationId: UUID? = null,
    val active: Boolean = true,
)

/** The opening balance, currency and type can't change once registered (the opening entry is on the ledger). */
data class BankAccountUpdateRequest(
    @field:NotBlank @field:Size(max = 160) val bankName: String,
    @field:Size(max = 120) val branch: String? = null,
    @field:NotBlank @field:Size(max = 160) val accountName: String,
    @field:NotBlank @field:Pattern(regexp = "\\d{6,30}", message = "must be 6-30 digits") val accountNumber: String,
    @field:Size(max = 40) val sortCodeOrBic: String? = null,
    @field:Size(max = 160) val authorisedSignatory: String? = null,
    @field:Size(max = 160) val relationshipManager: String? = null,
    val dateOpened: LocalDate? = null,
    val locationId: UUID? = null,
)

data class BankAccountStatusRequest(
    @field:NotNull @field:Pattern(regexp = "ACTIVE|DORMANT", message = "must be ACTIVE or DORMANT") val status: String?,
)

data class BankAccountView(
    val id: UUID,
    val code: String,
    val bankName: String,
    val branch: String?,
    val accountName: String,
    /** In full only for holders of accounting.bank.manage; otherwise the same as [accountNumberMasked]. */
    val accountNumber: String,
    val accountNumberMasked: String,
    val accountType: String,
    val currencyCode: String,
    val sortCodeOrBic: String?,
    val authorisedSignatory: String?,
    val relationshipManager: String?,
    val dateOpened: LocalDate?,
    val openingBalance: BigDecimal,
    val openingBalanceDate: LocalDate,
    val currentBalance: BigDecimal,
    val location: Ref?,
    val status: String,
    val createdAt: Instant,
)

data class ManualBankEntryRequest(
    @field:NotNull @field:Pattern(regexp = "CREDIT|DEBIT", message = "must be CREDIT or DEBIT") val entryType: String?,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    @field:NotNull val entryDate: LocalDate?,
    @field:NotBlank @field:Size(max = 300) val description: String,
    /** Required for a DEBIT (e.g. Bank Charges), so the cost lands in a category. */
    val expenseCategoryId: UUID? = null,
)

data class BankEntryView(
    val id: UUID,
    val reference: String,
    val entryType: String,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val entryDate: LocalDate,
    val description: String,
)

data class BankStatementRow(
    val id: UUID,
    val date: LocalDate,
    val reference: String,
    val description: String,
    /** "Credit" or "Debit". */
    val type: String,
    val credit: BigDecimal?,
    val debit: BigDecimal?,
    /** The account balance after this entry, in date order. */
    val balance: BigDecimal,
    val sourceType: String,
    val expenseCategoryName: String?,
    val createdAt: Instant,
)

// ---- Cash accounts (docs/07 §4.3) ----

data class CashAccountRequest(
    @field:NotBlank @field:Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,29}", message = "must be 1-30 letters, digits or hyphens") val code: String,
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotNull @field:Pattern(regexp = "TILL|PETTY_CASH|VAULT", message = "must be TILL, PETTY_CASH or VAULT") val kind: String?,
    /** A factory or showroom; null = head office. */
    val locationId: UUID? = null,
    @field:Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") val currencyCode: String? = null,
    @field:DecimalMin(value = "0") val openingBalance: BigDecimal? = null,
    /** PETTY_CASH only: the imprest ceiling. */
    @field:DecimalMin(value = "0.01") val floatLimit: BigDecimal? = null,
    /** TILL only. */
    @field:Size(max = 40) val posTerminalRef: String? = null,
    /** TILL only: the bank account card settlements land in. */
    val posSettlementBankAccountId: UUID? = null,
)

/** Code, kind, location and currency are fixed: moving them would rewrite statement history. */
data class CashAccountUpdateRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:DecimalMin(value = "0.01") val floatLimit: BigDecimal? = null,
    @field:Size(max = 40) val posTerminalRef: String? = null,
    val posSettlementBankAccountId: UUID? = null,
)

data class CashAccountStatusRequest(
    @field:NotNull @field:Pattern(regexp = "ACTIVE|INACTIVE", message = "must be ACTIVE or INACTIVE") val status: String?,
)

data class CashCountRequest(
    @field:NotNull @field:DecimalMin(value = "0") val countedAmount: BigDecimal?,
    @field:NotNull val countDate: LocalDate?,
    /** Required when the count differs from the book balance. */
    @field:Size(max = 300) val note: String? = null,
)

data class CashAccountView(
    val id: UUID,
    val code: String,
    val name: String,
    val kind: String,
    val location: Ref?,
    val currencyCode: String,
    val openingBalance: BigDecimal,
    val currentBalance: BigDecimal,
    val floatLimit: BigDecimal?,
    /** PETTY_CASH with a float limit: balance below half of it. */
    val belowFloat: Boolean,
    val posTerminalRef: String?,
    val posSettlementBankAccount: Ref?,
    val status: String,
    val createdAt: Instant,
)

data class CashCountResult(
    val account: CashAccountView,
    val bookBalance: BigDecimal,
    val countedAmount: BigDecimal,
    /** counted − book: positive = surplus, negative = shortage. */
    val variance: BigDecimal,
    /** The CASH_COUNT_ADJUSTMENT entry's reference, when there was a variance. */
    val adjustmentReference: String?,
)

// ---- Cash Inflow/Outflow Statement (docs/07 §4.4) ----

data class CashFlowRow(
    val sn: Long,
    val id: UUID,
    val date: LocalDate,
    val reference: String,
    val remark: String,
    val inflow: BigDecimal?,
    val outflow: BigDecimal?,
    /** Running total across every account in scope, in date order. */
    val balance: BigDecimal,
    val accountCode: String,
    val accountName: String,
    val kind: String,
    val sourceType: String,
    val expenseCategoryName: String?,
)

// ---- Expenses / Payment List Guide (docs/07 §4.5) ----

data class ExpenseCategoryRequest(
    @field:Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,19}", message = "must be 1-20 letters, digits or hyphens") val code: String? = null,
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:Size(max = 300) val description: String? = null,
    @field:NotNull @field:Pattern(regexp = "UTILITIES|LOGISTICS|MAINTENANCE|ADMINISTRATIVE|FINANCE", message = "must be UTILITIES, LOGISTICS, MAINTENANCE, ADMINISTRATIVE or FINANCE")
    val accountHead: String?,
    @field:DecimalMin(value = "0.01") val monthlyCap: BigDecimal? = null,
    val pettyCashAllowed: Boolean = true,
)

data class ExpenseCategoryStatusRequest(
    @field:NotNull @field:Pattern(regexp = "ACTIVE|INACTIVE", message = "must be ACTIVE or INACTIVE") val status: String?,
)

data class ExpenseCategoryView(
    val id: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val accountHead: String,
    val monthlyCap: BigDecimal?,
    val pettyCashAllowed: Boolean,
    val status: String,
    /** Net spend this calendar month: bank debits and cash paid out under this category, less reversals. */
    val spentThisMonth: BigDecimal,
    val overCap: Boolean,
    val createdAt: Instant,
)

/** A picker row for bank accounts, cash accounts and expense categories. */
data class AccountLookupRow(val id: UUID, val code: String, val label: String, val sublabel: String?, val status: String)

// ---- Exchange rates (docs/07 §4.16) ----

data class ExchangeRateRow(
    val code: String,
    val currency: String,
    val symbol: String,
    val buy: BigDecimal?,
    val sell: BigDecimal?,
    val spread: BigDecimal?,
    val previousBuy: BigDecimal?,
    val move: BigDecimal?,
    val movePct: BigDecimal?,
    val updatedAt: Instant?,
    val updatedBy: UserRef?,
    /** "Current" when published today (tenant time), "Stale" otherwise, null when never published. */
    val freshness: String?,
)

data class RateInput(
    @field:NotBlank @field:Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter currency code") val currencyCode: String,
    @field:NotNull @field:DecimalMin(value = "0.000001") val buyRate: BigDecimal?,
    @field:NotNull @field:DecimalMin(value = "0.000001") val sellRate: BigDecimal?,
)

data class PublishRatesRequest(@field:NotEmpty @field:Valid val rates: List<RateInput>)

data class PublishedRate(val id: UUID, val currencyCode: String, val buyRate: BigDecimal, val sellRate: BigDecimal)

data class RatePublicationView(val id: UUID, val ref: String, val publishedAt: Instant, val publishedBy: UserRef, val rates: List<PublishedRate>)

data class ExchangeRateHistoryRow(
    val id: UUID,
    val publicationRef: String,
    val currencyCode: String,
    val buyRate: BigDecimal,
    val sellRate: BigDecimal,
    val publishedAt: Instant,
    val publishedBy: UserRef,
)
