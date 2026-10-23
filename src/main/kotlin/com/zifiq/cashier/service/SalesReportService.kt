package com.zifiq.cashier.service

import com.zifiq.cashier.dto.CustomerActivityReport
import com.zifiq.cashier.dto.CustomerActivityRow
import com.zifiq.cashier.dto.SalesDailyRow
import com.zifiq.cashier.dto.SalesReport
import com.zifiq.cashier.dto.SalesReportStats
import com.zifiq.cashier.model.CustomerWaybillsTable
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalePaymentsTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.search.allOf
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.ref
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/**
 * Sales & POS reports (docs/09 §4.6): the daily Sales Report and the Customers/Distributors
 * Comprehensive report. Both are location-scoped, exclude voided and abandoned sales, and read
 * completed sales by date. Caller's transaction, through the tenant-scoped executor.
 */
@Service
class SalesReportService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
) {
    fun salesDaily(locationId: UUID?, cashierUserId: UUID?, channel: String?, from: LocalDate, to: LocalDate): SalesReport = tx.execute {
        requireRange(from, to)
        val base = baseCondition(from, to, locationId, cashierUserId, channel)
        val txns = SalesTable.id.count()
        val gross = SalesTable.grossAmount.sum()
        val lineDisc = SalesTable.lineDiscountTotal.sum()
        val instDisc = SalesTable.instantDiscountTotal.sum()
        val net = SalesTable.netAmount.sum()
        val vat = SalesTable.vatAmount.sum()
        val money = SalesTable.select(SalesTable.saleDate, SalesTable.locationId, txns, gross, lineDisc, instDisc, net, vat)
            .where(base).groupBy(SalesTable.saleDate, SalesTable.locationId).orderBy(SalesTable.saleDate to SortOrder.ASC)
            .map { it }
        // Units per (date, location): a separate grouped read over the lines of those sales.
        val unitsByKey = SaleLinesTable.join(SalesTable, JoinType.INNER, SaleLinesTable.saleId, SalesTable.id)
            .select(SalesTable.saleDate, SalesTable.locationId, SaleLinesTable.quantity.sum())
            .where(base).groupBy(SalesTable.saleDate, SalesTable.locationId)
            .associate { (it[SalesTable.saleDate] to it[SalesTable.locationId]) to (it[SaleLinesTable.quantity.sum()] ?: BigDecimal.ZERO) }
        val rows = money.map { r ->
            val date = r[SalesTable.saleDate]!!
            val loc = r[SalesTable.locationId]
            val transactions = r[txns]
            val n = Money.round(r[net] ?: BigDecimal.ZERO)
            SalesDailyRow(
                date = date, showroom = Ref.location(loc.value), transactions = transactions,
                units = unitsByKey[date to loc] ?: BigDecimal.ZERO, gross = Money.round(r[gross] ?: BigDecimal.ZERO),
                discount = Money.round((r[lineDisc] ?: BigDecimal.ZERO) + (r[instDisc] ?: BigDecimal.ZERO)), net = n, vat = Money.round(r[vat] ?: BigDecimal.ZERO),
                avgSale = if (transactions == 0L) BigDecimal.ZERO.setScale(2) else (n / BigDecimal(transactions)).setScale(2, RoundingMode.HALF_UP),
            )
        }
        val totals = if (rows.isEmpty()) null else SalesDailyRow(
            date = to, showroom = Ref.location((locationId ?: rows.first().showroom.id)), transactions = rows.sumOf { it.transactions },
            units = rows.fold(BigDecimal.ZERO) { s, it -> s + it.units }, gross = rows.fold(BigDecimal.ZERO) { s, it -> s + it.gross },
            discount = rows.fold(BigDecimal.ZERO) { s, it -> s + it.discount }, net = rows.fold(BigDecimal.ZERO) { s, it -> s + it.net },
            vat = rows.fold(BigDecimal.ZERO) { s, it -> s + it.vat },
            avgSale = rows.sumOf { it.transactions }.let { if (it == 0L) BigDecimal.ZERO.setScale(2) else (rows.fold(BigDecimal.ZERO) { s, r -> s + r.net } / BigDecimal(it)).setScale(2, RoundingMode.HALF_UP) },
        )
        val netSales = rows.fold(BigDecimal.ZERO) { s, it -> s + it.net }
        val totalTxns = rows.sumOf { it.transactions }
        SalesReport(rows, totals, SalesReportStats(
            netSales = netSales, transactions = totalTxns, units = rows.fold(BigDecimal.ZERO) { s, it -> s + it.units },
            averageTransaction = if (totalTxns == 0L) BigDecimal.ZERO.setScale(2) else (netSales / BigDecimal(totalTxns)).setScale(2, RoundingMode.HALF_UP),
            bestDay = rows.maxByOrNull { it.net }?.date,
        ))
    }

    fun customerActivity(kind: String?, customerId: UUID?, locationId: UUID?, from: LocalDate, to: LocalDate): CustomerActivityReport = tx.execute {
        requireRange(from, to)
        val types = when (kind?.uppercase()) {
            "DISTRIBUTORS" -> listOf(CustomerType.AUTHORISED_DEALER.name)
            "SPECIAL" -> listOf(CustomerType.SPECIAL_CUSTOMER.name)
            "NO_CREDIT" -> listOf(CustomerType.NO_CREDIT_WALKIN.name)
            else -> null
        }
        val condition = listOfNotNull(
            baseCondition(from, to, locationId, null, null),
            customerId?.let { SalesTable.customerId eq it },
            types?.let { SalesTable.customerType inList it },
        ).allOf()!!
        val sales = SalesTable.selectAll().where(condition).orderBy(SalesTable.saleDate to SortOrder.DESC, SalesTable.completedAt to SortOrder.DESC).toList()
        val ids = sales.map { it[SalesTable.id].value }
        val payByMethod = if (ids.isEmpty()) emptyMap() else SalePaymentsTable.select(SalePaymentsTable.saleId, SalePaymentsTable.method, SalePaymentsTable.amount.sum())
            .where { SalePaymentsTable.saleId inList ids }.groupBy(SalePaymentsTable.saleId, SalePaymentsTable.method)
            .groupBy({ it[SalePaymentsTable.saleId].value }, { it[SalePaymentsTable.method] to (it[SalePaymentsTable.amount.sum()] ?: BigDecimal.ZERO) })
            .mapValues { (_, l) -> l.toMap() }
        val lineAgg = if (ids.isEmpty()) emptyMap() else SaleLinesTable.select(SaleLinesTable.saleId, SaleLinesTable.id.count(), SaleLinesTable.quantity.sum())
            .where { SaleLinesTable.saleId inList ids }.groupBy(SaleLinesTable.saleId)
            .associate { it[SaleLinesTable.saleId].value to (it[SaleLinesTable.id.count()].toInt() to (it[SaleLinesTable.quantity.sum()] ?: BigDecimal.ZERO)) }
        val waybillRefs = if (ids.isEmpty()) emptyMap() else CustomerWaybillsTable.select(CustomerWaybillsTable.saleId, CustomerWaybillsTable.ref)
            .where { (CustomerWaybillsTable.saleId inList ids) and (CustomerWaybillsTable.status neq "CANCELLED") }
            .groupBy({ it[CustomerWaybillsTable.saleId] }, { it[CustomerWaybillsTable.ref] })
        val rows = sales.map { sale ->
            val id = sale[SalesTable.id].value
            fun m(method: String) = Money.round(payByMethod[id]?.get(method) ?: BigDecimal.ZERO)
            val (items, units) = lineAgg[id] ?: (0 to BigDecimal.ZERO)
            CustomerActivityRow(
                date = sale[SalesTable.saleDate], sale = Ref.sale(id), saleRef = sale[SalesTable.ref] ?: "", invoiceNumber = sale[SalesTable.invoiceNumber],
                customer = ref(sale[SalesTable.customerId], Ref::customer), customerName = sale[SalesTable.customerName], items = items, units = units,
                value = sale[SalesTable.total], cash = m("CASH"), pos = m("POS"), transfer = m("FUND_TRANSFER"), wallet = m("WALLET"), deposit = m("DEPOSIT"),
                onAccount = sale[SalesTable.onAccount], waybills = waybillRefs[id].orEmpty(),
            )
        }
        CustomerActivityReport(rows, rows.size.toLong(), rows.fold(BigDecimal.ZERO) { s, it -> s + it.units },
            rows.fold(BigDecimal.ZERO) { s, it -> s + it.value },
            if (rows.isEmpty()) BigDecimal.ZERO.setScale(2) else (rows.fold(BigDecimal.ZERO) { s, it -> s + it.value } / BigDecimal(rows.size)).setScale(2, RoundingMode.HALF_UP))
    }

    private fun baseCondition(from: LocalDate, to: LocalDate, locationId: UUID?, cashierUserId: UUID?, channel: String?): Op<Boolean> = listOfNotNull(
        SalesTable.tenantId eq support.tenantId(),
        SalesTable.status eq "COMPLETED",
        support.visibleLocations()?.let { SalesTable.locationId inList it },
        locationId?.let { SalesTable.locationId eq it },
        cashierUserId?.let { SalesTable.cashierUserId eq it },
        channel?.let { SalesTable.channel eq it.uppercase() },
        SalesTable.saleDate greaterEq from,
        SalesTable.saleDate lessEq to,
    ).allOf()!!

    private fun requireRange(from: LocalDate, to: LocalDate) {
        if (to.isBefore(from)) throw ApiErrors.validationFailed(mapOf("to" to "must be on or after 'from'"))
        if (ChronoUnit.DAYS.between(from, to) > 366) throw ApiErrors.rangeTooLarge(366)
    }
}
