package com.zifiq.showroom.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/** Mirrors V9_3 (docs/05 §2.7–§2.9). Generated `search_vector` columns aren't mapped. Item/location ids are plain uuids (inventory/platform own them). */
object ShowroomStockEntriesTable : UUIDTable("showroom_stock_entries") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val kind = varchar("kind", 20)
    val locationId = uuid("location_id")
    val intoLocationId = uuid("into_location_id")
    val supplierId = uuid("supplier_id").nullable()
    val supplierName = varchar("supplier_name", 160).nullable()
    val manufacturerId = uuid("manufacturer_id").nullable()
    val partnerReference = varchar("partner_reference", 60).nullable()
    val paymentTerms = varchar("payment_terms", 120).nullable()
    val checkedBy = varchar("checked_by", 120).nullable()
    val receivedDate = date("received_date")
    val totalCost = decimal("total_cost", 14, 2)
    val totalInputVat = decimal("total_input_vat", 14, 2)
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

object ShowroomStockEntryLinesTable : UUIDTable("showroom_stock_entry_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val entryId = reference("entry_id", ShowroomStockEntriesTable)
    val lineNo = integer("line_no")
    val stockItemId = uuid("stock_item_id")
    val description = varchar("description", 200)
    val quantity = decimal("quantity", 14, 3)
    val unitOfMeasureId = uuid("unit_of_measure_id")
    val unitCost = decimal("unit_cost", 14, 2)
    val lineCost = decimal("line_cost", 14, 2)
    val inputVatRate = decimal("input_vat_rate", 5, 2)
    val inputVatAmount = decimal("input_vat_amount", 14, 2)
    val batchNo = varchar("batch_no", 40).nullable()
    val expiryDate = date("expiry_date").nullable()
    val sellingPrice = decimal("selling_price", 14, 2).nullable()
}

object CustomerReturnsTable : UUIDTable("customer_returns") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val returnType = varchar("return_type", 20)
    val locationId = uuid("location_id")
    val customerId = uuid("customer_id")
    val customerCode = varchar("customer_code", 20)
    val customerName = varchar("customer_name", 200)
    val customerType = varchar("customer_type", 30)
    val customerPhone = varchar("customer_phone", 40).nullable()
    val temporaryCustomer = bool("temporary_customer")
    val originalDocumentRef = varchar("original_document_ref", 40).nullable()
    val returnDate = date("return_date")
    val totalValue = decimal("total_value", 14, 2)
    val writeOffValue = decimal("write_off_value", 14, 2)
    val walletEntryId = uuid("wallet_entry_id")
    val walletBalanceBefore = decimal("wallet_balance_before", 14, 2)
    val walletBalanceAfter = decimal("wallet_balance_after", 14, 2)
    val note = varchar("note", 300).nullable()
    val status = varchar("status", 20)
    val reversedReason = varchar("reversed_reason", 300).nullable()
    val reversedByUserId = uuid("reversed_by_user_id").nullable()
    val reversedAt = timestamp("reversed_at").nullable()
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

object CustomerReturnLinesTable : UUIDTable("customer_return_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val returnId = reference("return_id", CustomerReturnsTable)
    val lineNo = integer("line_no")
    val stockItemId = uuid("stock_item_id")
    val quantity = decimal("quantity", 14, 3)
    val unitPrice = decimal("unit_price", 14, 2)
    val lineValue = decimal("line_value", 14, 2)
    val reason = varchar("reason", 40)
    val condition = varchar("condition", 20)
    val intoLocationId = uuid("into_location_id").nullable()
    val note = varchar("note", 300).nullable()
}

object StorageAreaMovesTable : UUIDTable("storage_area_moves") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val rootLocationId = uuid("root_location_id")
    val fromLocationId = uuid("from_location_id")
    val toLocationId = uuid("to_location_id")
    val movedDate = date("moved_date")
    val note = varchar("note", 300).nullable()
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

object StorageAreaMoveLinesTable : UUIDTable("storage_area_move_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val moveId = reference("move_id", StorageAreaMovesTable)
    val lineNo = integer("line_no")
    val stockItemId = uuid("stock_item_id")
    val quantity = decimal("quantity", 14, 3)
}
