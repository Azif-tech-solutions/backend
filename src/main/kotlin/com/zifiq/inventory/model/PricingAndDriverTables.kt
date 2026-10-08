package com.zifiq.inventory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/** Mirrors V9_2 (docs/05 §2.5): driver + vehicle used on waybills. */
object DriversTable : UUIDTable("drivers") {
    val tenantId = reference("tenant_id", TenantsTable)
    val name = varchar("name", 160)
    val phone = varchar("phone", 40).nullable()
    val phoneE164 = varchar("phone_e164", 20).nullable()
    val vehicleNumber = varchar("vehicle_number", 30).nullable()
    val licenceNumber = varchar("licence_number", 40).nullable()
    val status = varchar("status", 20)
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

/** Mirrors V9_2 (docs/05 §2.6): effective-dated selling prices; a row is never changed once effective. */
object StockItemPricesTable : UUIDTable("stock_item_prices") {
    val tenantId = reference("tenant_id", TenantsTable)
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val retailPrice = decimal("retail_price", 14, 2)
    val dealerPrice = decimal("dealer_price", 14, 2)
    val effectiveFrom = date("effective_from")
    val batchRef = varchar("batch_ref", 20)
    val note = varchar("note", 300).nullable()
    val setByUserId = reference("set_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
