package com.zifiq.factory.model

import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object SpecialProductionClientsTable : UUIDTable("special_production_clients") {
    val tenantId = reference("tenant_id", TenantsTable)
    val name = varchar("name", 160)
    val terms = varchar("terms", 300).nullable()
    val createdAt = timestamp("created_at")
}

object SpecialProductionRunsTable : UUIDTable("special_production_runs") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val orderRef = varchar("order_ref", 40)
    val batchRef = varchar("batch_ref", 40)
    val clientId = reference("client_id", SpecialProductionClientsTable)
    val targetStockItemId = reference("target_stock_item_id", StockItemsTable).nullable()
    val productDescription = varchar("product_description", 160).nullable()
    val quantity = decimal("quantity", 14, 3)
    val unitId = uuid("unit_id")
    val unitCost = decimal("unit_cost", 14, 2)
    val price = decimal("price", 14, 2)
    val materialsSnapshot = jsonb<JsonSnapshot>("materials_snapshot", { it.toJson() }, { it.toSnapshot() })
    val status = varchar("status", 20).default("IN_PRODUCTION")
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
