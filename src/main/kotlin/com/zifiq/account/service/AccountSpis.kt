package com.zifiq.account.service

import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.CashTransactionsTable
import com.zifiq.account.spi.BankAccountRef
import com.zifiq.account.spi.BankAccounts
import com.zifiq.account.spi.CashAccountRef
import com.zifiq.account.spi.CashAccounts
import com.zifiq.account.spi.CashMovementSummary
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/** Cash-account reads of the Account spi (docs/07 §5.3); caller's transaction, no permission checks (callers apply their own). */
@Component
class CashAccountsSpi(private val support: AccountSupport) : CashAccounts {
    override fun tillsAt(locationId: UUID): List<CashAccountRef> =
        CashAccountsTable.selectAll()
            .where { (CashAccountsTable.tenantId eq support.tenantId()) and (CashAccountsTable.locationId eq locationId) and (CashAccountsTable.kind eq "TILL") and (CashAccountsTable.status eq "ACTIVE") }
            .orderBy(CashAccountsTable.code to SortOrder.ASC)
            .map(::cashRef)

    override fun get(id: UUID): CashAccountRef? =
        CashAccountsTable.selectAll().where { (CashAccountsTable.id eq id) and (CashAccountsTable.tenantId eq support.tenantId()) }.firstOrNull()?.let(::cashRef)

    override fun netCashBetween(cashAccountId: UUID, from: Instant, to: Instant): CashMovementSummary {
        var cashIn = BigDecimal.ZERO
        var cashOut = BigDecimal.ZERO
        CashTransactionsTable.selectAll()
            .where {
                (CashTransactionsTable.tenantId eq support.tenantId()) and (CashTransactionsTable.cashAccountId eq cashAccountId) and
                    (CashTransactionsTable.createdAt greaterEq from) and (CashTransactionsTable.createdAt less to)
            }
            .forEach { if (it[CashTransactionsTable.direction] == "IN") cashIn += it[CashTransactionsTable.amount] else cashOut += it[CashTransactionsTable.amount] }
        return CashMovementSummary(AccountSupport.money(cashIn), AccountSupport.money(cashOut), AccountSupport.money(cashIn - cashOut))
    }

    private fun cashRef(row: ResultRow) = CashAccountRef(
        id = row[CashAccountsTable.id].value, code = row[CashAccountsTable.code], name = row[CashAccountsTable.name], kind = row[CashAccountsTable.kind],
        locationId = row[CashAccountsTable.locationId], currentBalance = row[CashAccountsTable.currentBalance], posTerminalRef = row[CashAccountsTable.posTerminalRef],
        posSettlementBankAccountId = row[CashAccountsTable.posSettlementBankAccountId], active = row[CashAccountsTable.status] == "ACTIVE",
    )
}

/** Bank-account reads of the Account spi (docs/07 §5.3). */
@Component
class BankAccountsSpi(private val support: AccountSupport) : BankAccounts {
    override fun get(id: UUID): BankAccountRef? =
        BankAccountsTable.selectAll().where { (BankAccountsTable.id eq id) and (BankAccountsTable.tenantId eq support.tenantId()) }.firstOrNull()?.let(::bankRef)

    override fun activeBankAccounts(): List<BankAccountRef> =
        BankAccountsTable.selectAll().where { (BankAccountsTable.tenantId eq support.tenantId()) and (BankAccountsTable.status eq "ACTIVE") }
            .orderBy(BankAccountsTable.code to SortOrder.ASC).map(::bankRef)

    private fun bankRef(row: ResultRow) = BankAccountRef(
        id = row[BankAccountsTable.id].value, code = row[BankAccountsTable.code], bankName = row[BankAccountsTable.bankName],
        accountName = row[BankAccountsTable.accountName], accountNumberMasked = BankAccountService.mask(row[BankAccountsTable.accountNumber]),
        currencyCode = row[BankAccountsTable.currencyCode], locationId = row[BankAccountsTable.locationId], active = row[BankAccountsTable.status] == "ACTIVE",
    )
}
