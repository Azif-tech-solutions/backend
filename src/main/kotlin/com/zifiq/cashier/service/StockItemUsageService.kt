package com.zifiq.cashier.service

import com.zifiq.cashier.model.CustomerWaybillLinesTable
import com.zifiq.cashier.model.ProFormaLinesTable
import com.zifiq.cashier.model.ProFormasTable
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.cashier.spi.StockItemUsageQueries
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/** Tells Audit's Recycle Bin whether a product is still on live Sales & POS documents (docs/10 §4.9). */
@Service
class StockItemUsageService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
) : StockItemUsageQueries {
    override fun inOpenDocuments(stockItemId: UUID): Boolean = tx.execute {
        val onOpenSale = SaleLinesTable.join(SalesTable, JoinType.INNER, SaleLinesTable.saleId, SalesTable.id)
            .selectAll()
            .where { (SaleLinesTable.tenantId eq support.tenantId()) and (SaleLinesTable.stockItemId eq stockItemId) and (SalesTable.status eq "OPEN") }
            .limit(1).any()
        if (onOpenSale) return@execute true
        ProFormaLinesTable.join(ProFormasTable, JoinType.INNER, ProFormaLinesTable.proFormaId, ProFormasTable.id)
            .selectAll()
            .where {
                (ProFormaLinesTable.tenantId eq support.tenantId()) and (ProFormaLinesTable.stockItemId eq stockItemId) and
                    (ProFormasTable.status inList listOf("DRAFT", "PENDING_APPROVAL", "SENT"))
            }
            .limit(1).any()
    }

    override fun liveDocumentLineCount(stockItemId: UUID): Int = tx.execute {
        val onOpenSales = SaleLinesTable.join(SalesTable, JoinType.INNER, SaleLinesTable.saleId, SalesTable.id)
            .selectAll()
            .where { (SaleLinesTable.tenantId eq support.tenantId()) and (SaleLinesTable.stockItemId eq stockItemId) and (SalesTable.status eq "OPEN") }
            .count()
        val onOpenProFormas = ProFormaLinesTable.join(ProFormasTable, JoinType.INNER, ProFormaLinesTable.proFormaId, ProFormasTable.id)
            .selectAll()
            .where {
                (ProFormaLinesTable.tenantId eq support.tenantId()) and (ProFormaLinesTable.stockItemId eq stockItemId) and
                    (ProFormasTable.status inList listOf("DRAFT", "PENDING_APPROVAL", "SENT"))
            }
            .count()
        (onOpenSales + onOpenProFormas).toInt()
    }

    override fun waybillLineCount(stockItemId: UUID): Int = tx.execute {
        CustomerWaybillLinesTable.join(SaleLinesTable, JoinType.INNER, CustomerWaybillLinesTable.saleLineId, SaleLinesTable.id)
            .selectAll()
            .where { (CustomerWaybillLinesTable.tenantId eq support.tenantId()) and (SaleLinesTable.stockItemId eq stockItemId) }
            .count().toInt()
    }
}
