package com.zifiq.inventory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object StockReceiptsTable : UUIDTable("stock_receipts") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val ref = varchar("ref", 40)
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val supplierId = uuid("supplier_id").nullable()
    val manufacturerId = uuid("manufacturer_id").nullable()
    val quantity = decimal("quantity", 14, 3)
    val unitCost = decimal("unit_cost", 14, 2)
    val manufacturedDate = date("manufactured_date").nullable()
    val expiryDate = date("expiry_date").nullable()
    val verifiedByUserId = reference("verified_by_user_id", AppUsersTable).nullable()
    val offloadedAt = timestamp("offloaded_at").nullable()
    val condition = varchar("condition", 30).default("PENDING_VERIFICATION")
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
