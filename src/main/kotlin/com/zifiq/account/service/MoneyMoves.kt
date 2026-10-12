package com.zifiq.account.service

import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.repository.BankEntryType
import com.zifiq.account.repository.CashDirection
import com.zifiq.account.repository.LedgerPosting
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.account.repository.PostedEntry
import com.zifiq.common.errors.ApiErrors
import java.math.BigDecimal
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/** A bank or cash account a document moves money out of or into. */
data class MoneyAccount(
    val bank: Boolean,
    val id: UUID,
    val code: String,
    val name: String,
    val locationId: UUID?,
    /** TILL / PETTY_CASH / VAULT for cash; null for bank. */
    val kind: String?,
    val balance: BigDecimal,
    val floatLimit: BigDecimal?,
) {
    val label: String get() = "$code $name"
}

data class Posted(val cashTransactionId: UUID?, val bankTransactionId: UUID?)

/**
 * Resolves and checks the accounts a money document names, and posts its movements through
 * [MoneyLedgerRepository]. Caller's transaction.
 */
@Component
class MoneyMoves(private val support: AccountSupport, private val ledger: MoneyLedgerRepository) {
    /** An ACTIVE cash account the caller may act on; [kinds] restricts what it may be (else [onWrongKind]). */
    fun cash(id: UUID, kinds: Set<String>? = null, onWrongKind: (String) -> Nothing = { throw ApiErrors.invalidPayingAccount(it) }): MoneyAccount {
        val row = CashAccountsTable.selectAll().where { (CashAccountsTable.id eq id) and (CashAccountsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.cashAccountNotFound()
        support.assertAccess(row[CashAccountsTable.locationId])
        val account = cashAccount(row)
        if (row[CashAccountsTable.status] != "ACTIVE") throw ApiErrors.accountNotActive(account.code)
        if (kinds != null && account.kind !in kinds) {
            onWrongKind("${account.code} is a ${account.kind!!.lowercase().replace('_', ' ')} account; expected ${kinds.joinToString(" or ") { it.lowercase().replace('_', ' ') }}")
        }
        return account
    }

    /**
     * An ACTIVE bank account the caller may act on. [checkAccess] = false for money going *into* a
     * company account from a branch (a cashier lodging takings into head office's collection account).
     */
    fun bank(id: UUID, checkAccess: Boolean = true): MoneyAccount {
        val row = BankAccountsTable.selectAll().where { (BankAccountsTable.id eq id) and (BankAccountsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.bankAccountNotFound()
        if (checkAccess) support.assertAccess(row[BankAccountsTable.locationId])
        val account = bankAccount(row)
        if (row[BankAccountsTable.status] != "ACTIVE") throw ApiErrors.accountNotActive(account.code)
        return account
    }

    /** The source a BANK/CASH document names: exactly the id that matches [kind]. */
    fun source(kind: String, bankId: UUID?, cashId: UUID?, cashKinds: Set<String>? = null): MoneyAccount = when (kind) {
        "BANK" -> {
            if (bankId == null || cashId != null) throw ApiErrors.validationFailed(mapOf("sourceBankAccountId" to "a BANK source needs sourceBankAccountId (and no sourceCashAccountId)"))
            bank(bankId)
        }
        "CASH" -> {
            if (cashId == null || bankId != null) throw ApiErrors.validationFailed(mapOf("sourceCashAccountId" to "a CASH source needs sourceCashAccountId (and no sourceBankAccountId)"))
            cash(cashId, cashKinds)
        }
        else -> throw ApiErrors.validationFailed(mapOf("sourceKind" to "must be BANK or CASH"))
    }

    /** Any account by id regardless of status — for returning money to where it came from. */
    fun existing(bank: Boolean, id: UUID): MoneyAccount =
        if (bank) BankAccountsTable.selectAll().where { (BankAccountsTable.id eq id) and (BankAccountsTable.tenantId eq support.tenantId()) }.single().let(::bankAccount)
        else CashAccountsTable.selectAll().where { (CashAccountsTable.id eq id) and (CashAccountsTable.tenantId eq support.tenantId()) }.single().let(::cashAccount)

    /** Money leaves [account]: a bank DEBIT or a cash OUT (refused when the cash isn't there). */
    fun out(account: MoneyAccount, amount: BigDecimal, posting: LedgerPosting): Posted = post(account, amount, posting, outgoing = true)

    /** Money arrives in [account]: a bank CREDIT or a cash IN. */
    fun into(account: MoneyAccount, amount: BigDecimal, posting: LedgerPosting): Posted = post(account, amount, posting, outgoing = false)

    private fun post(account: MoneyAccount, amount: BigDecimal, posting: LedgerPosting, outgoing: Boolean): Posted {
        val entry: PostedEntry
        return if (account.bank) {
            entry = ledger.postBank(support.tenantId(), account.id, if (outgoing) BankEntryType.DEBIT else BankEntryType.CREDIT, amount, posting)
            Posted(null, entry.id)
        } else {
            entry = ledger.postCash(support.tenantId(), account.id, if (outgoing) CashDirection.OUT else CashDirection.IN, amount, posting)
            Posted(entry.id, null)
        }
    }

    private fun cashAccount(row: ResultRow) = MoneyAccount(
        bank = false, id = row[CashAccountsTable.id].value, code = row[CashAccountsTable.code], name = row[CashAccountsTable.name],
        locationId = row[CashAccountsTable.locationId], kind = row[CashAccountsTable.kind], balance = row[CashAccountsTable.currentBalance],
        floatLimit = row[CashAccountsTable.floatLimit],
    )

    private fun bankAccount(row: ResultRow) = MoneyAccount(
        bank = true, id = row[BankAccountsTable.id].value, code = row[BankAccountsTable.code], name = row[BankAccountsTable.bankName],
        locationId = row[BankAccountsTable.locationId], kind = null, balance = row[BankAccountsTable.currentBalance], floatLimit = null,
    )
}
