package com.zifiq.account.service

import com.zifiq.account.model.BankTransactionsTable
import com.zifiq.account.model.CashTransactionsTable
import com.zifiq.common.sql.Aggregates
import com.zifiq.common.sql.RunningBalance
import java.math.BigDecimal
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryAlias
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.alias

/**
 * The cash and bank ledgers' balance arithmetic, shared by every statement and summary: a row's
 * signed effect, the balance brought forward, and the running balance after each row.
 */
internal object LedgerSql {
    val cashSigned: Expression<BigDecimal> = RunningBalance.signed(CashTransactionsTable.amount, CashTransactionsTable.direction eq "IN")
    val bankSigned: Expression<BigDecimal> = RunningBalance.signed(BankTransactionsTable.amount, BankTransactionsTable.entryType eq "CREDIT")

    /** Net cash (in − out) over [condition]. */
    fun cashNet(condition: Op<Boolean>): BigDecimal {
        val net = Aggregates.sum(cashSigned)
        return Aggregates.money(CashTransactionsTable.select(net).where(condition).single(), net)
    }

    /** Net bank balance (credits − debits) over [condition]. */
    fun bankNet(condition: Op<Boolean>): BigDecimal {
        val net = Aggregates.sum(bankSigned)
        return Aggregates.money(BankTransactionsTable.select(net).where(condition).single(), net)
    }

    /**
     * The running balance after each cash row of [period] (the statement's accounts and dates, without
     * its search or filters), in posting order: join it on the row id and read it with [Balances.after].
     */
    fun cashBalances(period: Op<Boolean>): Balances {
        val running = RunningBalance.window(cashSigned, CashTransactionsTable.entryDate, CashTransactionsTable.createdAt, CashTransactionsTable.id).alias("running")
        val alias = CashTransactionsTable.select(CashTransactionsTable.id, running).where(period).alias("running_balance")
        return Balances(alias, alias[CashTransactionsTable.id], alias[running])
    }

    /** The same for a bank account's rows. */
    fun bankBalances(period: Op<Boolean>): Balances {
        val running = RunningBalance.window(bankSigned, BankTransactionsTable.entryDate, BankTransactionsTable.createdAt, BankTransactionsTable.id).alias("running")
        val alias = BankTransactionsTable.select(BankTransactionsTable.id, running).where(period).alias("running_balance")
        return Balances(alias, alias[BankTransactionsTable.id], alias[running])
    }

    class Balances(val alias: QueryAlias, val id: Expression<*>, private val running: Expression<BigDecimal>) {
        val column: Expression<*> get() = running

        /** The balance after [row]: what was brought forward plus the period's movements up to and including it. */
        fun after(row: ResultRow, opening: BigDecimal): BigDecimal = com.zifiq.common.money.Money.round(opening + row[running])
    }
}
