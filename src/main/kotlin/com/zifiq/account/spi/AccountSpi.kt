package com.zifiq.account.spi

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The Finance & Accounting module's published interface (docs/07 §5). Everything runs in the
// caller's tenant-scoped transaction. CommissionPayouts arrives with Communication (doc 12).

data class CashAccountRef(
    val id: UUID,
    val code: String,
    val name: String,
    /** TILL, PETTY_CASH or VAULT. */
    val kind: String,
    val locationId: UUID?,
    val currentBalance: BigDecimal,
    val posTerminalRef: String?,
    val posSettlementBankAccountId: UUID?,
    val active: Boolean,
)

data class BankAccountRef(
    val id: UUID,
    val code: String,
    val bankName: String,
    val accountName: String,
    val accountNumberMasked: String,
    val currencyCode: String,
    val locationId: UUID?,
    val active: Boolean,
)

/** Cash in and out of one account in a time window (doc 09 §4.1: a till session's expected cash). */
data class CashMovementSummary(val cashIn: BigDecimal, val cashOut: BigDecimal, val net: BigDecimal)

interface CashAccounts {
    /** ACTIVE tills at a showroom or factory. */
    fun tillsAt(locationId: UUID): List<CashAccountRef>

    fun get(id: UUID): CashAccountRef?

    fun netCashBetween(cashAccountId: UUID, from: Instant, to: Instant): CashMovementSummary
}

interface BankAccounts {
    fun get(id: UUID): BankAccountRef?

    fun activeBankAccounts(): List<BankAccountRef>
}

/** A published rate. Callers store [id] and the values they used, so history keeps the old rate (rule A13). */
data class RateRef(val id: UUID, val currencyCode: String, val buy: BigDecimal, val sell: BigDecimal, val publishedAt: Instant)

interface ExchangeRates {
    fun current(currencyCode: String): RateRef?

    fun asOf(currencyCode: String, instant: Instant): RateRef?
}

// ---- Money received (§5.1–5.2) ----

/** How money was received. CASH posts at once; POS, FUND_TRANSFER and CHEQUE wait for Accounts to confirm; WALLET/DEPOSIT were already debited by the caller. */
enum class Channel(val label: String) {
    CASH("Cash"), POS("POS (card)"), FUND_TRANSFER("Fund transfer"), CHEQUE("Cheque"), WALLET("Wallet"), DEPOSIT("Deposit");

    companion object {
        /** The label for a stored channel code (the code itself if unknown). */
        fun labelOf(code: String): String = entries.firstOrNull { it.name == code }?.label ?: code
    }
}

enum class ConfirmationStatus { NOT_REQUIRED, PENDING, APPROVED, DECLINED }

data class ReceiptRequest(
    val locationId: UUID?,
    /** CASH: the till or vault taking the cash; POS: the till the card was taken at. */
    val cashAccountId: UUID?,
    val customerId: UUID?,
    /** Snapshot printed on the receipt ("Walk-in" when none). */
    val customerName: String?,
    val channel: Channel,
    val amount: BigDecimal,
    /** "cashier", "invoicing", "account". */
    val sourceModule: String,
    /** POS_SALE, INVOICE_PAYMENT, WALLET_FUNDING, DEPOSIT_FUNDING, OTHER_INCOME. */
    val sourceType: String,
    val sourceId: UUID,
    val sourceRef: String,
    /** FUND_TRANSFER / CHEQUE (required), POS when the till has no settlement account. */
    val receivingBankAccountId: UUID? = null,
    val terminalRef: String? = null,
    /** Transfer reference, cheque number or card authorisation code. */
    val payerReference: String? = null,
    /** WALLET / DEPOSIT: the Customers ledger debit that paid. */
    val customerLedgerEntryId: UUID? = null,
    /** OTHER_INCOME only: BY_PRODUCT_SALES, SCRAP_SALES, RENTAL, INTEREST, OTHER. */
    val incomeCategory: String? = null,
    val description: String? = null,
    /** Business date for the ledger entry; defaults to today in the tenant's timezone. */
    val entryDate: LocalDate? = null,
)

/** Money paid back: CASH (cash out now), FUND_TRANSFER (bank debit now) or WALLET (the caller credits the wallet). */
data class RefundRequest(
    val locationId: UUID?,
    val cashAccountId: UUID?,
    val payingBankAccountId: UUID?,
    val customerId: UUID?,
    val customerName: String?,
    val channel: Channel,
    val amount: BigDecimal,
    val sourceModule: String,
    /** SALE_REFUND, DEPOSIT_REFUND. */
    val sourceType: String,
    val sourceId: UUID,
    val sourceRef: String,
    val customerLedgerEntryId: UUID? = null,
    val description: String? = null,
)

data class ReceiptRef(val id: UUID, val ref: String, val channel: Channel, val amount: BigDecimal, val confirmationStatus: ConfirmationStatus)

data class ReceiptView(
    val id: UUID,
    val ref: String,
    /** RECEIPT or REFUND. */
    val kind: String,
    val channel: Channel,
    val amount: BigDecimal,
    val confirmationStatus: ConfirmationStatus,
    /** ISSUED or VOIDED. */
    val status: String,
    val sourceModule: String,
    val sourceType: String,
    val sourceId: UUID,
    val sourceRef: String,
    val customerId: UUID?,
    val locationId: UUID?,
    val receivedAt: Instant,
)

interface MoneyLedger {
    /** Creates the receipt and applies the channel rules (docs/07 §2.7); registers it as a RECEIPT document. */
    fun recordReceipt(request: ReceiptRequest): ReceiptRef

    fun recordRefund(request: RefundRequest): ReceiptRef

    /** Voids a receipt the calling module created, undoing whatever it posted. */
    fun voidReceipt(receiptId: UUID, reason: String, sourceModule: String)
}

interface Receipts {
    /** Invoicing: paid = Σ receipts that are neither DECLINED nor VOIDED. */
    fun bySource(sourceModule: String, sourceIds: Collection<UUID>): List<ReceiptView>

    fun get(id: UUID): ReceiptView?
}

// ---- VAT per location (§5.4) ----

data class VatSetting(val locationId: UUID, val enabled: Boolean, val rate: BigDecimal, val registrationNumber: String?, val effectiveFrom: LocalDate)

interface VatRates {
    /** The setting in force at a showroom/factory (an area resolves to its parent) on [onDate]; disabled = rate 0. */
    fun forLocation(locationId: UUID, onDate: LocalDate): VatSetting
}

// ---- Commission payouts (§5.6) — used by Communication (doc 12 §4.3) ----

/** A cash/bank commission payout. WALLET payouts are the caller's own (customers WalletLedger). */
data class CommissionPayoutRequest(
    /** CASH or BANK. */
    val method: String,
    val cashAccountId: UUID?,
    val bankAccountId: UUID?,
    val amount: BigDecimal,
    /** The commission payment's reference (CP-n). */
    val ref: String,
    val description: String,
    /** The commission payment id, for the ledger entry's source link. */
    val sourceId: UUID,
)

data class CommissionPayoutPosting(val cashTransactionId: UUID?, val bankTransactionId: UUID?)

interface CommissionPayouts {
    /** Pays commission out of a cash or bank account (sufficiency enforced); runs in the caller's transaction. */
    fun payOut(request: CommissionPayoutRequest): CommissionPayoutPosting

    /** Reverses a payout back into the same account. */
    fun reversePayout(request: CommissionPayoutRequest, reason: String): CommissionPayoutPosting
}

// ---- Reporting reads (§5) — used by Reports & Analytics (doc 13 §4.2) ----

/** One operating-expense category's total for a period (docs/13 §4.2). */
data class ExpenseLine(val categoryId: UUID, val categoryCode: String, val categoryName: String, val amount: BigDecimal)

interface AccountReports {
    /**
     * APPROVED operating costs plus POSTED petty-cash payments by expense category in [from]..[to].
     * [locationIds] restricts the location-bearing operating costs (petty cash, which has no location,
     * is included only for a company-wide call). Runs in the caller's transaction.
     */
    fun operatingExpenses(from: LocalDate, to: LocalDate, locationIds: Set<UUID>? = null): List<ExpenseLine>
}
