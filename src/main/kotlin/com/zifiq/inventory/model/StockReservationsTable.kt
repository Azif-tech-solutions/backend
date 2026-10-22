package com.zifiq.inventory.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Stock reserved for later release (V15_1, docs/09 §2.1): goods sold but not collected at the till.
 * Held against the selling location (root), never moved until a waybill releases them. Owned by the
 * inventory substrate; written through [com.zifiq.inventory.service.StockReservations]. Cross-module
 * ids (location, stock item, the owning document) are plain uuid columns.
 */
object StockReservationsTable : UUIDTable("stock_reservations") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val stockItemId = uuid("stock_item_id")
    val sourceModule = varchar("source_module", 30)
    val sourceType = varchar("source_type", 30)
    val sourceId = uuid("source_id")
    val sourceRef = varchar("source_ref", 30)
    val quantityReserved = decimal("quantity_reserved", 14, 3)
    val quantityReleased = decimal("quantity_released", 14, 3)
    val status = varchar("status", 10)
    val createdAt = timestamp("created_at")
    val closedAt = timestamp("closed_at").nullable()
}
