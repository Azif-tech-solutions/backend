package com.zifiq.inventory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object StockTransfersTable : UUIDTable("stock_transfers") {
    val tenantId = reference("tenant_id", TenantsTable)
    val waybillRef = varchar("waybill_ref", 40)
    val sourceLocationId = uuid("source_location_id")
    val destinationLocationId = uuid("destination_location_id")
    val driverName = varchar("driver_name", 120).nullable()
    val driverVehicle = varchar("driver_vehicle", 60).nullable()
    val dispatchedByUserId = reference("dispatched_by_user_id", AppUsersTable)
    val dispatchedAt = timestamp("dispatched_at")
    val receivedByUserId = reference("received_by_user_id", AppUsersTable).nullable()
    val receivedAt = timestamp("received_at").nullable()
    val status = varchar("status", 20).default("DISPATCHED")
}

object StockTransferLinesTable : UUIDTable("stock_transfer_lines") {
    val stockTransferId = reference("stock_transfer_id", StockTransfersTable)
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val qtySent = decimal("qty_sent", 14, 3)
    val qtyReceived = decimal("qty_received", 14, 3).nullable()
    val qtyRejected = decimal("qty_rejected", 14, 3).nullable()
    val unitPrice = decimal("unit_price", 14, 2).nullable()
}
