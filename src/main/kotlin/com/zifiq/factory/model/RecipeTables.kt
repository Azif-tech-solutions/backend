package com.zifiq.factory.model

import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object RecipesTable : UUIDTable("recipes") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val code = varchar("code", 40)
    val name = varchar("name", 160)
    val description = varchar("description", 300).nullable()
    val sourcing = varchar("sourcing", 20)
    val yieldQty = decimal("yield_qty", 14, 3)
    val yieldUnitId = uuid("yield_unit_id")
    val shelfLifeDays = integer("shelf_life_days").nullable()
    val reorderLevel = decimal("reorder_level", 14, 3).nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

object RecipeIngredientsTable : UUIDTable("recipe_ingredients") {
    val recipeId = reference("recipe_id", RecipesTable)
    val rawMaterialStockItemId = reference("raw_material_stock_item_id", StockItemsTable)
    val quantity = decimal("quantity", 14, 3)
    val unitId = uuid("unit_id")
}
