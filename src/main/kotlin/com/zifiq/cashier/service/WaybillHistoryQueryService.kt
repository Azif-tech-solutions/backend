package com.zifiq.cashier.service

import com.zifiq.cashier.model.CustomerWaybillLinesTable
import com.zifiq.cashier.model.CustomerWaybillsTable
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.cashier.spi.SupplyHistoryFilter
import com.zifiq.cashier.spi.SupplyHistoryRow
import com.zifiq.cashier.spi.WaybillHistoryQueries
import com.zifiq.common.search.allOf
import com.zifiq.platform.tenancy.TenantScopedTransaction
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Supply history for Audit's Waybill History (docs/10 §4.1): the supplied lines of every waybill,
 * joined to the sale and its lines. The running `outstanding` is read straight off the waybill line
 * (stored at release time), so nothing is recomputed. Location-scoped like every cashier read.
 */
@Service
class WaybillHistoryQueryService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
) : WaybillHistoryQueries {
    override fun supplyHistory(filter: SupplyHistoryFilter): List<SupplyHistoryRow> = tx.execute {
        val invoice = filter.invoice?.trim()?.uppercase()?.ifEmpty { null }
        val base = CustomerWaybillLinesTable
            .join(CustomerWaybillsTable, JoinType.INNER, CustomerWaybillLinesTable.waybillId, CustomerWaybillsTable.id)
            .join(SaleLinesTable, JoinType.INNER, additionalConstraint = { CustomerWaybillLinesTable.saleLineId eq SaleLinesTable.id })
            .join(SalesTable, JoinType.INNER, additionalConstraint = { CustomerWaybillsTable.saleId eq SalesTable.id })
        val condition = listOfNotNull(
            CustomerWaybillsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { CustomerWaybillsTable.locationId inList it },
            invoice?.let { (CustomerWaybillsTable.invoiceNumber eq it) or (SalesTable.ref eq it) },
            filter.customerId?.let { SalesTable.customerId eq it },
            filter.driverId?.let { CustomerWaybillsTable.driverId eq it },
            filter.locationId?.let { CustomerWaybillsTable.locationId eq it },
            filter.status?.let { CustomerWaybillsTable.status eq it.uppercase() },
            filter.from?.let { CustomerWaybillsTable.releaseDate greaterEq it },
            filter.to?.let { CustomerWaybillsTable.releaseDate lessEq it },
        ).allOf()!!
        base.selectAll().where(condition)
            .orderBy(CustomerWaybillsTable.releaseDate to SortOrder.DESC, CustomerWaybillsTable.ref to SortOrder.DESC)
            .map {
                SupplyHistoryRow(
                    saleId = it[CustomerWaybillsTable.saleId],
                    saleRef = it[SalesTable.ref],
                    invoiceNumber = it[CustomerWaybillsTable.invoiceNumber] ?: it[SalesTable.invoiceNumber],
                    saleDate = it[SalesTable.saleDate],
                    customerId = it[SalesTable.customerId],
                    customerName = it[SalesTable.customerName],
                    waybillId = it[CustomerWaybillsTable.id].value,
                    waybillRef = it[CustomerWaybillsTable.ref],
                    supplyDate = it[CustomerWaybillsTable.releaseDate],
                    saleLineId = it[CustomerWaybillLinesTable.saleLineId],
                    stockItemId = it[SaleLinesTable.stockItemId],
                    productName = it[SaleLinesTable.itemName],
                    ordered = it[SaleLinesTable.quantity],
                    qtySupplied = it[CustomerWaybillLinesTable.quantity],
                    outstanding = it[CustomerWaybillLinesTable.outstandingAfter],
                    driverId = it[CustomerWaybillsTable.driverId],
                    driverName = it[CustomerWaybillsTable.driverName],
                    receivedBy = it[CustomerWaybillsTable.receivedBy],
                    status = it[CustomerWaybillsTable.status],
                    locationId = it[CustomerWaybillsTable.locationId],
                )
            }
    }
}
