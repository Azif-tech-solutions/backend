package com.zifiq.cashier.service

import com.zifiq.account.spi.ConfirmationStatus
import com.zifiq.account.spi.Receipts
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesLineRef
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Completed sale lines for commission allocation (docs/12 §5). A sale's receipts are "confirmed" when
 * none is still PENDING or was DECLINED; a sale with no receipts (fully on account) is confirmed too,
 * because collection is a separate matter. Receipt statuses come from Account's Receipts.bySource, the
 * same source the sale detail view uses.
 */
@Service
class SalesQueryService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val receipts: Receipts,
) : SalesQueries {
    override fun linesFor(filter: SalesLineFilter): List<SalesLineRef> = tx.execute {
        val rows = SaleLinesTable.join(SalesTable, JoinType.INNER, SaleLinesTable.saleId, SalesTable.id)
            .selectAll()
            .where {
                var c = (SalesTable.tenantId eq support.tenantId()) and (SalesTable.status eq "COMPLETED") and (SalesTable.saleDate lessEq filter.completedTo)
                if (filter.locationId != null) c = c and (SalesTable.locationId eq filter.locationId)
                c
            }
            .toList()
        val confirmed = confirmedBySale(rows.map { it[SalesTable.id].value }.distinct())
        rows.map { r ->
            val saleId = r[SalesTable.id].value
            SalesLineRef(
                saleId = saleId, saleLineId = r[SaleLinesTable.id].value, saleDate = r[SalesTable.saleDate] ?: filter.completedTo,
                saleRef = r[SalesTable.ref] ?: "", customerId = r[SalesTable.customerId], customerType = r[SalesTable.customerType],
                correspondentId = r[SalesTable.correspondentId], stockItemId = r[SaleLinesTable.stockItemId], itemCode = r[SaleLinesTable.itemCode],
                itemName = r[SaleLinesTable.itemName], quantity = r[SaleLinesTable.quantity], lineNet = r[SaleLinesTable.lineNet],
                receiptsConfirmed = confirmed[saleId] ?: true, invoicePaid = r[SalesTable.onAccount].signum() == 0,
            )
        }
    }

    private fun confirmedBySale(saleIds: List<UUID>): Map<UUID, Boolean> {
        if (saleIds.isEmpty()) return emptyMap()
        val bySale = receipts.bySource(CashierSupport.SOURCE_MODULE, saleIds).filter { it.status != "VOIDED" }.groupBy { it.sourceId }
        return saleIds.associateWith { id ->
            (bySale[id] ?: emptyList()).all { it.confirmationStatus == ConfirmationStatus.APPROVED || it.confirmationStatus == ConfirmationStatus.NOT_REQUIRED }
        }
    }
}
