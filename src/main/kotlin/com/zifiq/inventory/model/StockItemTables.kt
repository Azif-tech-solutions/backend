package com.zifiq.inventory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Inventory & Stock is the shared central stock substrate used by both Factory and (later)
 * Showroom locations — see docs/03-inventory.md. location_id/category_id/unit_of_measure_id are
 * deliberately plain uuid columns, not Exposed `reference()`s into factory.model.* (Categories/
 * UnitsOfMeasure now live under Factory Setup) — that would create an inventory -> factory Kotlin
 * import, inverting the intended dependency direction (Factory/Showroom depend on Inventory, not
 * the reverse). Referential integrity is enforced by the SQL foreign keys in the migrations, not
 * by Exposed.
 */
object StockItemsTable : UUIDTable("stock_items") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val itemType = varchar("item_type", 20)
    val code = varchar("code", 40)
    val name = varchar("name", 160)
    val categoryId = uuid("category_id").nullable()
    val brand = varchar("brand", 120).nullable()
    val unitOfMeasureId = uuid("unit_of_measure_id")
    val reorderLevel = decimal("reorder_level", 14, 3)
    val barcode = varchar("barcode", 64).nullable()
    val landingCost = decimal("landing_cost", 14, 2).nullable()
    val markupCost = decimal("markup_cost", 14, 2).nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

object StockBalancesTable : Table("stock_balances") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val quantityOnHand = decimal("quantity_on_hand", 14, 3).default(java.math.BigDecimal.ZERO)
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(locationId, stockItemId)
}
