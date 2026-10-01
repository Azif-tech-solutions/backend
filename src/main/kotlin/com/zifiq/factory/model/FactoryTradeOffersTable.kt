package com.zifiq.factory.model

import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object FactoryTradeOffersTable : UUIDTable("factory_trade_offers") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 40)
    val fromLocationId = uuid("from_location_id")
    val toLocationId = uuid("to_location_id")
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val quantity = decimal("quantity", 14, 3)
    val reason = varchar("reason", 20)
    val offeredByUserId = reference("offered_by_user_id", AppUsersTable)
    val offeredAt = timestamp("offered_at")
    val expiresAt = timestamp("expires_at")
    val respondedAt = timestamp("responded_at").nullable()
    val status = varchar("status", 20).default("PENDING")
    val respondedByUserId = reference("responded_by_user_id", AppUsersTable).nullable()
}
