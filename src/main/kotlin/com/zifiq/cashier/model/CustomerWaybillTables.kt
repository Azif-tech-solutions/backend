package com.zifiq.cashier.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Customer waybills (V15_1, docs/09 §2.6): the release of outstanding goods against a completed
 * sale, moving the reserved stock out. Cross-module ids (sale, driver, locations) are plain uuid
 * columns.
 */
object CustomerWaybillsTable : UUIDTable("customer_waybills") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val saleId = uuid("sale_id")
    val invoiceNumber = varchar("invoice_number", 20).nullable()
    val locationId = uuid("location_id")
    val releaseMethod = varchar("release_method", 12)
    val releaseDate = date("release_date")
    val driverId = uuid("driver_id").nullable()
    val driverName = varchar("driver_name", 160).nullable()
    val vehicleNumber = varchar("vehicle_number", 30).nullable()
    val collectedBy = varchar("collected_by", 160).nullable()
    val deliveryAddress = varchar("delivery_address", 300).nullable()
    val status = varchar("status", 12)
    val receivedBy = varchar("received_by", 160).nullable()
    val receivedAt = timestamp("received_at").nullable()
    val acknowledgementNote = varchar("acknowledgement_note", 300).nullable()
    val cancelledReason = varchar("cancelled_reason", 300).nullable()
    val cancelledByUserId = uuid("cancelled_by_user_id").nullable()
    val issuedByUserId = uuid("issued_by_user_id")
    val createdAt = timestamp("created_at")
}

object CustomerWaybillLinesTable : UUIDTable("customer_waybill_lines") {
    val tenantId = reference("tenant_id", TenantsTable)
    val waybillId = reference("waybill_id", CustomerWaybillsTable)
    val saleLineId = uuid("sale_line_id")
    val fromLocationId = uuid("from_location_id")
    val quantity = decimal("quantity", 14, 3)
    val outstandingAfter = decimal("outstanding_after", 14, 3)
}
