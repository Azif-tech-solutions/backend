package com.zifiq.cashier.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/** Instant Discount on a live sale (V15_2, docs/09 §2.4). */
object InstantDiscountsTable : UUIDTable("instant_discounts") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val saleId = uuid("sale_id")
    val mode = varchar("mode", 10)
    val value = decimal("value", 14, 4)
    val discountAmount = decimal("discount_amount", 14, 2)
    val baseAmount = decimal("base_amount", 14, 2)
    val pctOfBase = decimal("pct_of_base", 7, 4)
    val reason = varchar("reason", 20)
    val note = varchar("note", 300).nullable()
    val status = varchar("status", 20)
    val appliedByUserId = uuid("applied_by_user_id")
    val approvedByUserId = uuid("approved_by_user_id").nullable()
    val decidedAt = timestamp("decided_at").nullable()
    val rejectionReason = varchar("rejection_reason", 300).nullable()
    val createdAt = timestamp("created_at")
}

/** Pro-forma invoices — quotations (V15_2, docs/09 §2.5). */
object ProFormasTable : UUIDTable("pro_formas") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val status = varchar("status", 20)
    val locationId = uuid("location_id")
    val customerId = uuid("customer_id").nullable()
    val prospectName = varchar("prospect_name", 200).nullable()
    val prospectAddress = varchar("prospect_address", 300).nullable()
    val prospectContact = varchar("prospect_contact", 200).nullable()
    val prospectEmail = varchar("prospect_email", 320).nullable()
    val customerName = varchar("customer_name", 200)
    val customerType = varchar("customer_type", 30).nullable()
    val priceBasis = varchar("price_basis", 10)
    val issueDate = date("issue_date")
    val validUntil = date("valid_until")
    val paymentTerms = varchar("payment_terms", 300).nullable()
    val deliveryTerms = varchar("delivery_terms", 300).nullable()
    val note = varchar("note", 500).nullable()
    val grossAmount = decimal("gross_amount", 14, 2)
    val discountTotal = decimal("discount_total", 14, 2)
    val netAmount = decimal("net_amount", 14, 2)
    val vatEnabled = bool("vat_enabled")
    val vatRate = decimal("vat_rate", 5, 2)
    val vatAmount = decimal("vat_amount", 14, 2)
    val total = decimal("total", 14, 2)
    val preparedByUserId = uuid("prepared_by_user_id")
    val discountApprovedByUserId = uuid("discount_approved_by_user_id").nullable()
    val sentAt = timestamp("sent_at").nullable()
    val decidedAt = timestamp("decided_at").nullable()
    val declineReason = varchar("decline_reason", 300).nullable()
    val convertedSaleId = uuid("converted_sale_id").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object ProFormaLinesTable : UUIDTable("pro_forma_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val proFormaId = reference("pro_forma_id", ProFormasTable)
    val lineNo = integer("line_no")
    val stockItemId = uuid("stock_item_id")
    val itemName = varchar("item_name", 200)
    val unitName = varchar("unit_name", 60)
    val quantity = decimal("quantity", 14, 3)
    val unitPrice = decimal("unit_price", 14, 2)
    val lineDiscount = decimal("line_discount", 14, 2)
    val lineNet = decimal("line_net", 14, 2)
    val vatRate = decimal("vat_rate", 5, 2)
    val vatAmount = decimal("vat_amount", 14, 2)
    val lineTotal = decimal("line_total", 14, 2)
}
