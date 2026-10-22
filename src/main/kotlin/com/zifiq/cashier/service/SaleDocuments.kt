package com.zifiq.cashier.service

import com.zifiq.cashier.dto.SaleInvoiceRequest
import com.zifiq.cashier.dto.SaleView
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalePaymentsTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.invoicing.spi.InvoiceIssuer
import com.zifiq.invoicing.spi.InvoiceRequest
import com.zifiq.invoicing.spi.PaymentLink
import com.zifiq.invoicing.spi.SourceType
import com.zifiq.platform.directory.Refs
import com.zifiq.platform.documents.DocumentLinkProvider
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.settings.spi.CompanyProfileProvider
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/** A rendered document and the file name to offer it under. */
data class SaleDocument(val fileName: String, val pdf: ByteArray)

/**
 * The itemised till receipt (docs/09 §4.2 Read) and invoicing a walk-in sale afterwards. The receipt
 * is rendered on demand from the immutable sale; a copy printed after a recorded reprint carries
 * "DUPLICATE COPY" (docs/03 §8.2).
 */
@Service
class SaleDocuments(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val views: SaleViews,
    private val checkout: SaleCheckout,
    private val invoices: InvoiceIssuer,
    private val documents: DocumentRegistry,
    private val companyProfile: CompanyProfileProvider,
    private val pdfRenderer: PdfRenderer,
) {
    fun receipt(id: UUID): SaleDocument = tx.execute {
        val sale = views.visibleRow(id)
        val status = sale[SalesTable.status]
        if (status != "COMPLETED" && status != "VOIDED") throw ApiErrors.saleNotFound()
        val view = views.view(sale).also(Refs::resolve)
        val company = companyProfile.get()
        val ref = view.ref!!
        val model = mapOf(
            "sale" to mapOf("ref" to ref, "invoiceNumber" to view.invoiceNumber),
            "company" to company,
            "logo" to company.logoDataUri(),
            "showroom" to view.location.name,
            "till" to view.till?.code,
            "cashier" to view.cashier.name,
            "customer" to (view.customer?.customer?.let { "${it.name} (${it.code})" } ?: view.customerName),
            "correspondent" to view.correspondent?.name,
            "completedAt" to view.completedAt?.let { LocalDateTime.ofInstant(it, support.zone()).format(STAMP) },
            "lines" to view.lines.map { line ->
                mapOf("name" to line.name, "code" to line.code, "quantity" to line.quantity.stripTrailingZeros().toPlainString(), "unit" to line.unit,
                    "price" to Money.naira(line.unitPrice), "amount" to Money.naira(line.lineTotal))
            },
            "totals" to mapOf(
                "gross" to Money.naira(view.totals.gross), "discount" to Money.naira(view.totals.lineDiscounts + view.totals.instantDiscount),
                "hasDiscount" to ((view.totals.lineDiscounts + view.totals.instantDiscount).signum() > 0),
                "vat" to Money.naira(view.totals.vat), "total" to Money.naira(view.totals.total),
            ),
            "vatLabel" to if (view.totals.vatEnabled) "VAT (${view.totals.vatRate.stripTrailingZeros().toPlainString()}%)" else "VAT (not collected at this showroom)",
            "payments" to view.payments.map {
                mapOf("method" to it.methodLabel, "receipt" to it.receiptRef, "amount" to Money.naira(it.amount), "pending" to (it.confirmationStatus == "PENDING"))
            },
            "onAccount" to view.onAccount.takeIf { it.signum() > 0 }?.let(Money::naira),
            "change" to view.changeGiven?.takeIf { it.signum() > 0 }?.let(Money::naira),
            "voided" to (status == "VOIDED"),
            "voidReason" to view.voidReason,
            "duplicate" to documents.isReprint(ref),
            "printedAt" to LocalDateTime.now(support.zone()).format(STAMP),
        )
        SaleDocument("$ref.pdf", pdfRenderer.render("sale-receipt", model))
    }

    /** A walk-in sale invoiced after the fact (docs/09 §4.2): the sale's own lines and receipts. */
    fun invoice(id: UUID, request: SaleInvoiceRequest): SaleView = tx.execute {
        val sale = views.visibleRow(id, forUpdate = true)
        if (sale[SalesTable.status] != "COMPLETED") throw ApiErrors.invalidStatusTransition(sale[SalesTable.status], "INVOICED")
        val ref = sale[SalesTable.ref]!!
        if (sale[SalesTable.invoiceId] != null) throw ApiErrors.invoiceExistsForSource(ref)
        if (sale[SalesTable.customerId] != null) throw ApiErrors.validationFailed(mapOf("buyer" to "a registered customer's sale is invoiced at completion"))
        val lines = SaleLinesTable.selectAll().where { SaleLinesTable.saleId eq id }.orderBy(SaleLinesTable.lineNo).toList()
        val invoice = invoices.issue(
            InvoiceRequest(
                sourceType = SourceType.POS_SALE, sourceId = id, sourceRef = ref, sourceDate = sale[SalesTable.saleDate]!!,
                locationId = sale[SalesTable.locationId].value, walkInBuyer = checkout.walkIn(request.buyer!!), paymentTermsDays = 0,
                lines = lines.map(checkout::invoiceLine),
            ),
        )
        SalePaymentsTable.selectAll().where { SalePaymentsTable.saleId eq id }.forEach {
            invoices.attachPayment(invoice.id, PaymentLink(it[SalePaymentsTable.paymentReceiptId], it[SalePaymentsTable.receiptRef], it[SalePaymentsTable.method],
                it[SalePaymentsTable.amount], it[SalePaymentsTable.createdAt], it[SalePaymentsTable.customerLedgerEntryId]))
        }
        SalesTable.update({ SalesTable.id eq id }) {
            it[invoiceId] = invoice.id
            it[invoiceNumber] = invoice.number
            it[version] = version + 1
            it[updatedAt] = Instant.now()
        }
        support.record("cashier.sale.invoiced", "sale", id, ref, "$ref invoiced on request as ${invoice.number}", mapOf("invoiceId" to invoice.id), sale[SalesTable.locationId].value)
        views.view(id)
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    }
}

/** Lets the document registry link sale receipts to their PDF. */
@Component
class CashierDocumentLinks : DocumentLinkProvider {
    override val sourceModule = CashierSupport.SOURCE_MODULE
    override fun pdfUrl(docType: DocumentType, sourceId: UUID): String? = when (docType) {
        DocumentType.SALE_RECEIPT -> "/api/v1/cashier/sales/$sourceId/receipt.pdf"
        DocumentType.WAYBILL -> "/api/v1/cashier/waybills/$sourceId/waybill.pdf"
        else -> null
    }
}
