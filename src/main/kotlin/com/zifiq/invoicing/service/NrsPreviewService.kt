package com.zifiq.invoicing.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.json.Json
import com.zifiq.invoicing.dto.NrsPreview
import com.zifiq.invoicing.model.InvoiceLinesTable
import com.zifiq.invoicing.model.InvoicePaymentsTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * `GET /invoices/{id}/nrs-preview` (docs/08 §4.12): the NRS Invoice Schema JSON this invoice would be
 * validated as, built from the snapshot taken at issue, plus what is still missing for a real
 * submission. This phase transmits nothing; the preview shows the future Access-Point integration is
 * a serializer over data already stored, and which master data to complete first.
 */
@Service
class NrsPreviewService(private val support: InvoiceSupport, private val tx: TenantScopedTransaction) {
    fun preview(id: UUID): NrsPreview = tx.execute {
        val invoice = InvoicesTable.selectAll().where { (InvoicesTable.id eq id) and (InvoicesTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.invoiceNotFound()
        support.assertVisible(invoice[InvoicesTable.locationId].value)
        if (invoice[InvoicesTable.status] != "ISSUED") throw ApiErrors.invoiceNotIssued()
        val missing = mutableListOf<String>()
        val issuer = runCatching { Json.readMap(invoice[InvoicesTable.issuer]) }.getOrDefault(emptyMap())
        fun issuerField(key: String, path: String): String? = issuer[key]?.toString()?.takeIf { it.isNotBlank() }.also { if (it == null) missing += "issuer.$path" }

        val supplier = mapOf(
            "party_name" to (issuer["legalName"] ?: issuer["tradingName"]),
            "tin" to issuerField("tin", "tin"),
            "email" to issuerField("email", "email"),
            "telephone" to issuerField("phone", "phone"),
            "business_description" to issuer["businessDescription"],
            "postal_address" to mapOf(
                "street_name" to issuerField("streetAddress", "streetAddress"), "city_name" to issuerField("city", "city"),
                "postal_zone" to issuer["postalCode"], "lga" to issuerField("lgaCode", "lgaCode"),
                "state" to issuerField("stateCode", "stateCode"), "country" to (issuer["countryCode"] ?: "NG"),
            ),
        )
        val businessId = issuerField("nrsBusinessId", "nrsBusinessId")
        val b2b = invoice[InvoicesTable.invoiceKind] == "B2B"
        fun buyerField(value: String?, path: String): String? = value?.takeIf { it.isNotBlank() }.also { if (it == null && b2b) missing += "buyer.$path" }
        val customer = mapOf(
            "party_name" to invoice[InvoicesTable.buyerName],
            "tin" to buyerField(invoice[InvoicesTable.buyerTin], "tin"),
            "email" to invoice[InvoicesTable.buyerEmail],
            "telephone" to invoice[InvoicesTable.buyerPhone],
            "business_description" to invoice[InvoicesTable.buyerBusinessDescription],
            "postal_address" to mapOf(
                "street_name" to buyerField(invoice[InvoicesTable.buyerStreetAddress], "streetAddress"),
                "city_name" to buyerField(invoice[InvoicesTable.buyerCity], "city"),
                "postal_zone" to invoice[InvoicesTable.buyerPostalCode], "lga" to invoice[InvoicesTable.buyerLgaCode],
                "state" to buyerField(invoice[InvoicesTable.buyerStateCode], "stateCode"), "country" to invoice[InvoicesTable.buyerCountryCode],
            ),
        )

        val lines = InvoiceLinesTable.selectAll().where { InvoiceLinesTable.invoiceId eq id }.orderBy(InvoiceLinesTable.lineNo).toList()
        val invoiceLines = lines.map { line ->
            val no = line[InvoiceLinesTable.lineNo]
            val service = line[InvoiceLinesTable.isService]
            val classification = if (service) {
                if (line[InvoiceLinesTable.isicCode] == null) missing += "lines[$no].isic_code (fallback used)"
                mapOf("isic_code" to line[InvoiceLinesTable.isicCode], "service_category" to line[InvoiceLinesTable.serviceCategory])
            } else {
                if (line[InvoiceLinesTable.hsnCode] == null) missing += "lines[$no].hsn_code (fallback used)"
                mapOf("hsn_code" to (line[InvoiceLinesTable.hsnCode] ?: line[InvoiceLinesTable.hsnSectionCode]), "product_category" to line[InvoiceLinesTable.productCategory])
            }
            if (line[InvoiceLinesTable.nrsUnitCode] == null) missing += "lines[$no].price_unit"
            classification + mapOf(
                "discount_rate" to line[InvoiceLinesTable.discountRate],
                "discount_amount" to line[InvoiceLinesTable.discountAmount],
                "invoiced_quantity" to line[InvoiceLinesTable.quantity],
                "line_extension_amount" to line[InvoiceLinesTable.lineNet],
                "item" to mapOf("name" to line[InvoiceLinesTable.itemName], "description" to (line[InvoiceLinesTable.itemDescription] ?: line[InvoiceLinesTable.itemName]),
                    "sellers_item_identification" to line[InvoiceLinesTable.sellersItemIdentification]),
                "price" to mapOf("price_amount" to line[InvoiceLinesTable.unitPrice], "base_quantity" to line[InvoiceLinesTable.baseQuantity],
                    "price_unit" to line[InvoiceLinesTable.nrsUnitCode]),
            )
        }
        val subtotals = lines.groupBy { line -> line[InvoiceLinesTable.nrsTaxCategoryCode] to line[InvoiceLinesTable.vatRate] }.map { (key, group) ->
            mapOf(
                "taxable_amount" to group.fold(BigDecimal.ZERO) { s, it -> s + it[InvoiceLinesTable.lineNet] }.setScale(2),
                "tax_amount" to group.fold(BigDecimal.ZERO) { s, it -> s + it[InvoiceLinesTable.vatAmount] }.setScale(2),
                "tax_category" to mapOf("id" to key.first, "percent" to key.second),
            )
        }
        val payments = InvoicePaymentsTable.selectAll()
            .where { (InvoicePaymentsTable.invoiceId eq id) and (InvoicePaymentsTable.receiptStatus eq "ACTIVE") }.toList()
            .filter { it[InvoicePaymentsTable.nrsPaymentMeansCode] != null }
            .map { mapOf("payment_means_code" to it[InvoicePaymentsTable.nrsPaymentMeansCode], "payment_due_date" to LocalDate.ofInstant(it[InvoicePaymentsTable.paidAt], support.zone()).toString()) }
        val original = invoice[InvoicesTable.reissueOfInvoiceId]?.let { originalId ->
            InvoicesTable.select(InvoicesTable.number, InvoicesTable.issueDate, InvoicesTable.irn).where { InvoicesTable.id eq originalId }.firstOrNull()
        }
        val total = invoice[InvoicesTable.taxInclusiveAmount]
        val balance = total - invoice[InvoicesTable.amountPaid] - invoice[InvoicesTable.amountCredited]
        val body = linkedMapOf<String, Any?>(
            "business_id" to businessId,
            "irn" to invoice[InvoicesTable.irn],
            "issue_date" to invoice[InvoicesTable.issueDate]?.toString(),
            "issue_time" to invoice[InvoicesTable.issueTime]?.toString(),
            "due_date" to invoice[InvoicesTable.dueDate]?.toString(),
            "invoice_type_code" to invoice[InvoicesTable.invoiceTypeCode],
            "invoice_kind" to invoice[InvoicesTable.invoiceKind],
            "payment_status" to if (balance <= support.tolerance()) "PAID" else "PENDING",
            "note" to invoice[InvoicesTable.paymentTermsNote],
            "payment_terms_note" to invoice[InvoicesTable.paymentTermsNote],
            "document_currency_code" to invoice[InvoicesTable.documentCurrencyCode],
            "tax_currency_code" to invoice[InvoicesTable.taxCurrencyCode],
            "billing_reference" to original?.let { listOf(mapOf("irn" to it[InvoicesTable.irn], "invoice_number" to it[InvoicesTable.number], "issue_date" to it[InvoicesTable.issueDate]?.toString())) },
            "accounting_supplier_party" to supplier,
            "accounting_customer_party" to customer,
            "payment_means" to payments,
            "allowance_charge" to lines.filter { it[InvoiceLinesTable.discountAmount].signum() > 0 }
                .map { mapOf("charge_indicator" to false, "amount" to it[InvoiceLinesTable.discountAmount]) },
            "tax_total" to listOf(mapOf("tax_amount" to invoice[InvoicesTable.vatTotal], "tax_subtotal" to subtotals)),
            "legal_monetary_total" to mapOf(
                "line_extension_amount" to invoice[InvoicesTable.lineExtensionAmount],
                "tax_exclusive_amount" to invoice[InvoicesTable.taxExclusiveAmount],
                "tax_inclusive_amount" to total,
                "payable_amount" to (total - invoice[InvoicesTable.amountCredited]).setScale(2),
            ),
            "invoice_line" to invoiceLines,
        )
        NrsPreview(ready = missing.isEmpty(), missing = missing, invoice = body)
    }
}
