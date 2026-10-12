package com.zifiq.account.spi

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// The Finance & Accounting module's published interface (docs/07 §5). Everything runs in the
// caller's tenant-scoped transaction. MoneyLedger (receipts, refunds) and VatRates arrive with
// slice 3; CommissionPayouts with Communication (doc 12).

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
