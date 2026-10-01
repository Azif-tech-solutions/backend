package com.zifiq.inventory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object StockLedgerEntriesTable : UUIDTable("stock_ledger_entries") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val sourceType = varchar("source_type", 30)
    val sourceReference = varchar("source_reference", 40)
    val qtyIn = decimal("qty_in", 14, 3).default(java.math.BigDecimal.ZERO)
    val qtyOut = decimal("qty_out", 14, 3).default(java.math.BigDecimal.ZERO)
    val unitCost = decimal("unit_cost", 14, 2).nullable()
    val remark = varchar("remark", 300).nullable()
    val entryDate = date("entry_date")
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
