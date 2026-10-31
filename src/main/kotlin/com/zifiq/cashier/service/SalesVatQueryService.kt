package com.zifiq.cashier.service

import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.cashier.spi.SaleVatLineRef
import com.zifiq.cashier.spi.SalesVatQueries
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/** Output-VAT source for Audit's VAT report (docs/10 §4.6): the VAT-bearing lines of completed sales. */
@Service
class SalesVatQueryService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
) : SalesVatQueries {
    override fun outputVatLines(locationId: UUID, from: LocalDate, to: LocalDate): List<SaleVatLineRef> = tx.execute {
        SaleLinesTable.join(SalesTable, JoinType.INNER, SaleLinesTable.saleId, SalesTable.id)
            .selectAll()
            .where {
                (SalesTable.tenantId eq support.tenantId()) and (SalesTable.locationId eq locationId) and
                    (SalesTable.status eq "COMPLETED") and (SalesTable.saleDate greaterEq from) and (SalesTable.saleDate lessEq to)
            }
            .map {
                SaleVatLineRef(
                    date = it[SalesTable.saleDate] ?: from,
                    saleRef = it[SalesTable.ref] ?: "",
                    invoiceNumber = it[SalesTable.invoiceNumber],
                    customerName = it[SalesTable.customerName],
                    taxableAmount = it[SaleLinesTable.lineNet],
                    rate = it[SaleLinesTable.vatRate],
                    vat = it[SaleLinesTable.vatAmount],
                    vatCategoryCode = it[SaleLinesTable.vatCategoryCode],
                )
            }
    }
}
