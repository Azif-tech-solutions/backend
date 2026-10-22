package com.zifiq.cashier.model

import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Sales & POS tables (V15__sales_pos_tills_and_sales.sql, docs/09 §2.2–2.3). Cross-module ids
 * (cash account, customer, correspondent, receipt, invoice, stock item) are plain uuid columns:
 * Sales & POS must not import other modules' tables; the database still keeps them inside the tenant.
 */
object TillSessionsTable : UUIDTable("till_sessions") {
    val tenantId = reference("tenant_id", TenantsTable)
    val cashAccountId = uuid("cash_account_id")
    val tillCode = varchar("till_code", 30)
    val locationId = reference("location_id", LocationsTable)
    val cashierUserId = uuid("cashier_user_id")
    val status = varchar("status", 10)
    val openedAt = timestamp("opened_at")
    val openingFloat = decimal("opening_float", 14, 2)
    val closedAt = timestamp("closed_at").nullable()
    val closedByUserId = uuid("closed_by_user_id").nullable()
    val countedCash = decimal("counted_cash", 14, 2).nullable()
    val expectedCash = decimal("expected_cash", 14, 2).nullable()
    val variance = decimal("variance", 14, 2).nullable()
    val closeNote = varchar("close_note", 300).nullable()
    val forceClosed = bool("force_closed")
}

object SalesTable : UUIDTable("sales") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20).nullable()
    val channel = varchar("channel", 20)
    val status = varchar("status", 12)
    val version = integer("version")
    val locationId = reference("location_id", LocationsTable)
    val tillSessionId = uuid("till_session_id").nullable()
    val cashierUserId = uuid("cashier_user_id")
    val proFormaId = uuid("pro_forma_id").nullable()
    val customerId = uuid("customer_id").nullable()
    val customerCode = varchar("customer_code", 20).nullable()
    val customerName = varchar("customer_name", 200)
    val customerType = varchar("customer_type", 30).nullable()
    val priceBasis = varchar("price_basis", 10)
    val correspondentId = uuid("correspondent_id").nullable()
    val grossAmount = decimal("gross_amount", 14, 2)
    val lineDiscountTotal = decimal("line_discount_total", 14, 2)
    val instantDiscountTotal = decimal("instant_discount_total", 14, 2)
    val netAmount = decimal("net_amount", 14, 2)
    val vatEnabled = bool("vat_enabled")
    val vatRate = decimal("vat_rate", 5, 2)
    val vatAmount = decimal("vat_amount", 14, 2)
    val total = decimal("total", 14, 2)
    val paidNow = decimal("paid_now", 14, 2)
    val onAccount = decimal("on_account", 14, 2)
    val cashTendered = decimal("cash_tendered", 14, 2).nullable()
    val changeGiven = decimal("change_given", 14, 2).nullable()
    val creditOverrideReason = varchar("credit_override_reason", 300).nullable()
    val creditOverrideByUserId = uuid("credit_override_by_user_id").nullable()
    val invoiceId = uuid("invoice_id").nullable()
    val invoiceNumber = varchar("invoice_number", 20).nullable()
    val walkInPhone = varchar("walk_in_phone", 40).nullable()
    val supplyStatus = varchar("supply_status", 20)
    val saleDate = date("sale_date").nullable()
    val startedAt = timestamp("started_at")
    val updatedAt = timestamp("updated_at")
    val completedAt = timestamp("completed_at").nullable()
    val abandonedAt = timestamp("abandoned_at").nullable()
    val voidedAt = timestamp("voided_at").nullable()
    val voidedByUserId = uuid("voided_by_user_id").nullable()
    val voidReason = varchar("void_reason", 300).nullable()
}

object SaleLinesTable : UUIDTable("sale_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val saleId = reference("sale_id", SalesTable)
    val lineNo = integer("line_no")
    val stockItemId = uuid("stock_item_id")
    val itemCode = varchar("item_code", 40)
    val itemName = varchar("item_name", 200)
    val unitName = varchar("unit_name", 60)
    val categoryName = varchar("category_name", 120).nullable()
    val quantity = decimal("quantity", 14, 3)
    val unitPrice = decimal("unit_price", 14, 2)
    val priceBasis = varchar("price_basis", 10)
    val priceListRef = varchar("price_list_ref", 30).nullable()
    val lineDiscount = decimal("line_discount", 14, 2)
    val instantDiscountShare = decimal("instant_discount_share", 14, 2)
    val lineNet = decimal("line_net", 14, 2)
    val vatCategoryCode = varchar("vat_category_code", 30)
    val vatRate = decimal("vat_rate", 5, 2)
    val vatAmount = decimal("vat_amount", 14, 2)
    val lineTotal = decimal("line_total", 14, 2)
    val supplyNowQty = decimal("supply_now_qty", 14, 3)
    val suppliedQty = decimal("supplied_qty", 14, 3)
    val fromLocationId = uuid("from_location_id").nullable()
}

object SalePaymentsTable : UUIDTable("sale_payments") {
    val tenantId = reference("tenant_id", TenantsTable)
    val saleId = reference("sale_id", SalesTable)
    val method = varchar("method", 20)
    val amount = decimal("amount", 14, 2)
    val paymentReceiptId = uuid("payment_receipt_id")
    val receiptRef = varchar("receipt_ref", 20)
    val customerLedgerEntryId = uuid("customer_ledger_entry_id").nullable()
    val terminalRef = varchar("terminal_ref", 40).nullable()
    val payerReference = varchar("payer_reference", 80).nullable()
    val receivingBankAccountId = uuid("receiving_bank_account_id").nullable()
    val createdAt = timestamp("created_at")
}
