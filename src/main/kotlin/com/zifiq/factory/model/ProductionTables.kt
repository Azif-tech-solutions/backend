package com.zifiq.factory.model

import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object ProductionCostingsTable : UUIDTable("production_costings") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val targetStockItemId = reference("target_stock_item_id", StockItemsTable)
    val recipeId = reference("recipe_id", RecipesTable).nullable()
    val batchSizeQty = decimal("batch_size_qty", 14, 3)
    val materialsSnapshot = jsonb<JsonSnapshot>("materials_snapshot", { it.toJson() }, { it.toSnapshot() })
    val overheadSnapshot = jsonb<JsonSnapshot>("overhead_snapshot", { it.toJson() }, { it.toSnapshot() })
    val computedUnitCost = decimal("computed_unit_cost", 14, 2)
    val computedTotalCost = decimal("computed_total_cost", 14, 2)
    val status = varchar("status", 20).default("PENDING")
    val computedByUserId = reference("computed_by_user_id", AppUsersTable)
    val approvedByUserId = reference("approved_by_user_id", AppUsersTable).nullable()
    val approvedAt = timestamp("approved_at").nullable()
    val rejectionReason = varchar("rejection_reason", 300).nullable()
    val createdAt = timestamp("created_at")
}

object ProductionRunsTable : UUIDTable("production_runs") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val batchRef = varchar("batch_ref", 40)
    val productionCostingId = reference("production_costing_id", ProductionCostingsTable)
    val quantityProduced = decimal("quantity_produced", 14, 3)
    val unitLandingCost = decimal("unit_landing_cost", 14, 2)
    val totalLandingCost = decimal("total_landing_cost", 14, 2)
    val materialsUsed = jsonb<JsonSnapshot>("materials_used", { it.toJson() }, { it.toSnapshot() })
    val runByUserId = reference("run_by_user_id", AppUsersTable)
    val runAt = timestamp("run_at")
}
