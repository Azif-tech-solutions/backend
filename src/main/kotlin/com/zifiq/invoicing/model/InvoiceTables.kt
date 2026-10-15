package com.zifiq.invoicing.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

/**
 * Electronic Invoicing tables (V12__electronic_invoicing_module.sql). Lifecycle `status` is the only
 * state a person sets (DRAFT/ISSUED/VOID); payment status and overdue-ness are derived from
 * [amountPaid], [amountCredited] and [dueDate] (docs/08 §1.3-3). Cross-module ids (customer,
 * source, exchange rate) are plain uuid columns: Invoicing must not import other modules' tables.
 */
object InvoicesTable : UUIDTable("invoices") {
    val tenantId = reference("tenant_id", TenantsTable)
    val number = varchar("number", 20).nullable()
    val status = varchar("status", 10)
    val invoiceTypeCode = varchar("invoice_type_code", 3)
    val invoiceKind = varchar("invoice_kind", 3)
    val sourceType = varchar("source_type", 20)
    val sourceId = uuid("source_id")
    val sourceRef = varchar("source_ref", 30)
    val sourceDate = date("source_date")
    val proFormaRef = varchar("pro_forma_ref", 30).nullable()
    val locationId = reference("location_id", LocationsTable)
    val issueDate = date("issue_date").nullable()
    val issueTime = time("issue_time").nullable()
    val dueDate = date("due_date").nullable()
    val paymentTermsDays = integer("payment_terms_days")
    val paymentTermsNote = varchar("payment_terms_note", 300).nullable()
    val documentCurrencyCode = varchar("document_currency_code", 3)
    val taxCurrencyCode = varchar("tax_currency_code", 3)
    val exchangeRateId = uuid("exchange_rate_id").nullable()
    val exchangeRate = decimal("exchange_rate", 14, 6).nullable()
    val customerId = uuid("customer_id").nullable()
    val buyerName = varchar("buyer_name", 200)
    val buyerType = varchar("buyer_type", 30).nullable()
    val buyerEmail = varchar("buyer_email", 320).nullable()
    val buyerPhone = varchar("buyer_phone", 40).nullable()
    val buyerTin = varchar("buyer_tin", 40).nullable()
    val buyerBusinessDescription = varchar("buyer_business_description", 200).nullable()
    val buyerStreetAddress = varchar("buyer_street_address", 300).nullable()
    val buyerCity = varchar("buyer_city", 120).nullable()
    val buyerPostalCode = varchar("buyer_postal_code", 20).nullable()
    val buyerStateCode = varchar("buyer_state_code", 5).nullable()
    val buyerLgaCode = varchar("buyer_lga_code", 10).nullable()
    val buyerCountryCode = varchar("buyer_country_code", 2)
    val correspondentId = uuid("correspondent_id").nullable()
    /** The issuer snapshot (company profile + issuing location) as a JSON object string; see IssuerSnapshot. */
    val issuer = jsonb<String>("issuer", { it }, { it })
    val lineExtensionAmount = decimal("line_extension_amount", 14, 2)
    val discountTotal = decimal("discount_total", 14, 2)
    val taxExclusiveAmount = decimal("tax_exclusive_amount", 14, 2)
    val vatTotal = decimal("vat_total", 14, 2)
    val taxInclusiveAmount = decimal("tax_inclusive_amount", 14, 2)
    val vatEnabled = bool("vat_enabled")
    val locationVatRate = decimal("location_vat_rate", 5, 2)
    val amountPaid = decimal("amount_paid", 14, 2)
    val amountCredited = decimal("amount_credited", 14, 2)
    val lastPaymentAt = timestamp("last_payment_at").nullable()
    val issuedByUserId = optReference("issued_by_user_id", AppUsersTable)
    val issuedAt = timestamp("issued_at").nullable()
    val voidReason = varchar("void_reason", 300).nullable()
    val voidedByUserId = optReference("voided_by_user_id", AppUsersTable)
    val voidedAt = timestamp("voided_at").nullable()
    val reissueOfInvoiceId = uuid("reissue_of_invoice_id").nullable()
    val replacedByInvoiceId = uuid("replaced_by_invoice_id").nullable()
    val overdueNotifiedAt = timestamp("overdue_notified_at").nullable()
    val irn = varchar("irn", 80).nullable()
    val nrsSubmissionStatus = varchar("nrs_submission_status", 20)
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

object InvoiceLinesTable : UUIDTable("invoice_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val invoiceId = reference("invoice_id", InvoicesTable)
    val lineNo = integer("line_no")
    val stockItemId = uuid("stock_item_id")
    val itemCode = varchar("item_code", 40)
    val itemName = varchar("item_name", 200)
    val itemDescription = varchar("item_description", 300).nullable()
    val sellersItemIdentification = varchar("sellers_item_identification", 80).nullable()
    val quantity = decimal("quantity", 14, 3)
    val unitName = varchar("unit_name", 60)
    val nrsUnitCode = varchar("nrs_unit_code", 10).nullable()
    val baseQuantity = decimal("base_quantity", 14, 3)
    val unitPrice = decimal("unit_price", 14, 2)
    val discountAmount = decimal("discount_amount", 14, 2)
    val discountRate = decimal("discount_rate", 7, 4).nullable()
    val lineNet = decimal("line_net", 14, 2)
    val vatCategoryId = uuid("vat_category_id")
    val vatCategoryCode = varchar("vat_category_code", 30)
    val nrsTaxCategoryCode = varchar("nrs_tax_category_code", 40)
    val vatRate = decimal("vat_rate", 5, 2)
    val vatAmount = decimal("vat_amount", 14, 2)
    val lineTotal = decimal("line_total", 14, 2)
    val isService = bool("is_service")
    val hsnCode = varchar("hsn_code", 10).nullable()
    val hsnSectionCode = varchar("hsn_section_code", 6).nullable()
    val productCategory = varchar("product_category", 500).nullable()
    val isicCode = varchar("isic_code", 4).nullable()
    val serviceCategory = varchar("service_category", 300).nullable()
}

object InvoiceWaybillLinksTable : UUIDTable("invoice_waybill_links") {
    val tenantId = reference("tenant_id", TenantsTable)
    val invoiceId = reference("invoice_id", InvoicesTable)
    val waybillId = uuid("waybill_id")
    val waybillRef = varchar("waybill_ref", 20)
    val waybillDate = date("waybill_date")
    val createdAt = timestamp("created_at")
}
