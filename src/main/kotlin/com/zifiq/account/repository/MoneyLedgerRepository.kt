package com.zifiq.account.repository

import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.BankTransactionsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.CashTransactionsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component

enum class BankEntryType { CREDIT, DEBIT }
enum class CashDirection { IN, OUT }

/** What a posting needs besides the account and amount; [entryDate] is the business date shown on statements. */
data class LedgerPosting(
    val reference: String,
    val description: String,
    val sourceType: String,
    val sourceId: UUID? = null,
    val expenseCategoryId: UUID? = null,
    val entryDate: LocalDate,
    val userId: UUID?,
)

data class PostedEntry(val id: UUID, val balanceAfter: BigDecimal)

/**
 * The only writer of `bank_transactions` / `cash_transactions` and of the accounts' `current_balance`
 * caches (docs/07 §2.3) — the Account analogue of `StockLedgerRepository`. Each posting locks the
 * account row, so concurrent postings serialise and the balance cache never drifts from the ledger.
 * Runs in the caller's tenant-scoped transaction.
 */
@Component
class MoneyLedgerRepository {
    /** A dormant account takes no new entries; balances may go negative (an overdraft is a real bank state). */
    fun postBank(tenantId: UUID, accountId: UUID, type: BankEntryType, amount: BigDecimal, posting: LedgerPosting): PostedEntry {
        require(amount.signum() > 0) { "amount must be positive" }
        val account = BankAccountsTable.selectAll()
            .where { (BankAccountsTable.id eq accountId) and (BankAccountsTable.tenantId eq tenantId) }
            .forUpdate().singleOrNull() ?: throw ApiErrors.bankAccountNotFound()
        if (account[BankAccountsTable.status] != "ACTIVE") throw ApiErrors.accountNotActive(account[BankAccountsTable.code])

        val balance = account[BankAccountsTable.currentBalance].let { if (type == BankEntryType.CREDIT) it + amount else it - amount }
        val id = BankTransactionsTable.insertAndGetId {
            it[BankTransactionsTable.tenantId] = EntityID(tenantId, TenantsTable)
            it[bankAccountId] = accountId
            it[entryType] = type.name
            it[BankTransactionsTable.amount] = amount
            it[balanceAfter] = balance
            it[reference] = posting.reference
            it[description] = posting.description.take(300)
            it[sourceType] = posting.sourceType
            it[sourceId] = posting.sourceId
            it[expenseCategoryId] = posting.expenseCategoryId
            it[entryDate] = posting.entryDate
            it[createdByUserId] = posting.userId
            it[createdAt] = Instant.now()
        }.value
        BankAccountsTable.update({ BankAccountsTable.id eq accountId }) { it[currentBalance] = balance }
        return PostedEntry(id, balance)
    }

    /** Cash can't go below zero (rule A5): an OUT larger than the balance is refused, and the DB checks back that up. */
    fun postCash(tenantId: UUID, accountId: UUID, direction: CashDirection, amount: BigDecimal, posting: LedgerPosting): PostedEntry {
        require(amount.signum() > 0) { "amount must be positive" }
        val account = CashAccountsTable.selectAll()
            .where { (CashAccountsTable.id eq accountId) and (CashAccountsTable.tenantId eq tenantId) }
            .forUpdate().singleOrNull() ?: throw ApiErrors.cashAccountNotFound()
        val code = account[CashAccountsTable.code]
        if (account[CashAccountsTable.status] != "ACTIVE") throw ApiErrors.accountNotActive(code)

        val current = account[CashAccountsTable.currentBalance]
        if (direction == CashDirection.OUT && amount > current) throw ApiErrors.insufficientCash(code, current, amount)
        val balance = if (direction == CashDirection.IN) current + amount else current - amount
        val id = CashTransactionsTable.insertAndGetId {
            it[CashTransactionsTable.tenantId] = EntityID(tenantId, TenantsTable)
            it[cashAccountId] = accountId
            it[CashTransactionsTable.direction] = direction.name
            it[CashTransactionsTable.amount] = amount
            it[balanceAfter] = balance
            it[reference] = posting.reference
            it[remark] = posting.description.take(300)
            it[sourceType] = posting.sourceType
            it[sourceId] = posting.sourceId
            it[expenseCategoryId] = posting.expenseCategoryId
            it[entryDate] = posting.entryDate
            it[createdByUserId] = posting.userId
            it[createdAt] = Instant.now()
        }.value
        CashAccountsTable.update({ CashAccountsTable.id eq accountId }) { it[currentBalance] = balance }
        return PostedEntry(id, balance)
    }
}
