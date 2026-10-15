package com.zifiq.inventory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
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
    val driverId = uuid("driver_id").nullable()
    val transferDate = date("transfer_date")
    val receivedDate = date("received_date").nullable()
    val receiptNote = varchar("receipt_note", 300).nullable()
}

object StockTransferLinesTable : UUIDTable("stock_transfer_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val stockTransferId = reference("stock_transfer_id", StockTransfersTable)
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val qtySent = decimal("qty_sent", 14, 3)
    val qtyReceived = decimal("qty_received", 14, 3).nullable()
    val qtyRejected = decimal("qty_rejected", 14, 3).nullable()
    val unitPrice = decimal("unit_price", 14, 2).nullable()
    /** Area dispatched from; null = the source location itself. */
    val fromLocationId = uuid("from_location_id").nullable()
    /** Area received into; null = the destination itself. */
    val receivedIntoLocationId = uuid("received_into_location_id").nullable()
    val batchNo = varchar("batch_no", 40).nullable()
    val rejectionNote = varchar("rejection_note", 300).nullable()
}
