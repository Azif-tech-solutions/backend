package com.zifiq.cashier.service

import com.zifiq.account.spi.MoneyLedger
import com.zifiq.cashier.dto.SaleView
import com.zifiq.cashier.dto.SaleVoidRequest
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalePaymentsTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.customers.spi.CustomerActivity
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.invoicing.spi.InvoiceIssuer
import com.zifiq.invoicing.spi.SourceType
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Voiding a completed sale within `cashier.sale.voidWindowHours` (docs/09 §4.2 Void), in one
 * transaction: collected goods go back where they left from (`SALE_VOID`), every receipt is voided
 * through Account (which reverses its cash/bank postings), wallet and deposit debits are reversed,
 * the invoice is voided, and the customer's lifetime value is reduced. After the window the sale
 * stands; a customer return and a credit note correct it instead.
 */
@Service
class SaleVoids(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val views: SaleViews,
    private val stockLedger: StockLedgerRepository,
    private val moneyLedger: MoneyLedger,
    private val walletLedger: WalletLedger,
    private val invoices: InvoiceIssuer,
    private val activity: CustomerActivity,
    private val documents: DocumentRegistry,
    private val notify: Notify,
) {
    fun void(id: UUID, request: SaleVoidRequest): SaleView = tx.execute {
        val sale = views.visibleRow(id, forUpdate = true)
        if (sale[SalesTable.status] != "COMPLETED") throw ApiErrors.invalidStatusTransition(sale[SalesTable.status], "VOIDED")
        val hours = support.settings().int(CashierSettings.VOID_WINDOW_HOURS)
        if (Instant.now().isAfter(views.voidableUntil(sale[SalesTable.completedAt]!!))) throw ApiErrors.voidWindowPassed(hours)
        val reason = request.reason!!.trim()
        val ref = sale[SalesTable.ref]!!
        val locationId = sale[SalesTable.locationId].value

        SaleLinesTable.selectAll().where { SaleLinesTable.saleId eq id }.forEach { line ->
            val collected = line[SaleLinesTable.supplyNowQty]
            if (collected.signum() > 0) {
                stockLedger.postEntry(
                    tenantId = support.tenantId(), locationId = line[SaleLinesTable.fromLocationId] ?: locationId, stockItemId = line[SaleLinesTable.stockItemId],
                    sourceType = "SALE_VOID", sourceReference = ref, qtyIn = collected, remark = "Sale $ref voided: $reason",
                    performedByUserId = support.userId(), entryDate = support.today(),
                )
            }
        }
        SalePaymentsTable.selectAll().where { SalePaymentsTable.saleId eq id }.forEach { payment ->
            moneyLedger.voidReceipt(payment[SalePaymentsTable.paymentReceiptId], "Sale $ref voided: $reason", CashierSupport.SOURCE_MODULE)
            payment[SalePaymentsTable.customerLedgerEntryId]?.let { walletLedger.reverse(it, "Sale $ref voided: $reason", CashierSupport.SOURCE_MODULE) }
        }
        // The receipts are void already, so the invoice voids without the "has payments" guard.
        if (sale[SalesTable.invoiceId] != null) invoices.voidForSource(SourceType.POS_SALE.name, id, "Sale $ref voided: $reason")
        sale[SalesTable.customerId]?.let { activity.reverseSale(it, sale[SalesTable.total]) }
        documents.markVoided(ref)

        val now = Instant.now()
        SalesTable.update({ SalesTable.id eq id }) {
            it[status] = "VOIDED"
            it[voidedAt] = now
            it[voidedByUserId] = support.userId()
            it[voidReason] = reason.take(300)
            it[version] = version + 1
            it[updatedAt] = now
        }
        val total: BigDecimal = sale[SalesTable.total]
        val eventId = support.record("cashier.sale.voided", "sale", id, ref, "$ref voided (${Money.naira(total)}): $reason",
            mapOf("total" to total, "customerId" to sale[SalesTable.customerId], "correspondentId" to sale[SalesTable.correspondentId]), locationId)
        notify.holdersOf(
            permission = CashierSupport.READ_ALL, locationId = locationId, category = NotificationCategory.ALERT,
            title = "Sale voided — $ref", body = "${Money.naira(total)} to ${sale[SalesTable.customerName]}: $reason",
            linkType = "sale", linkRef = id.toString(), sourceEventId = eventId, exclude = support.userId(),
        )
        views.view(id)
    }
}
