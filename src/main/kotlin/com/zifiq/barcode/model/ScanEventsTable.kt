package com.zifiq.barcode.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * The barcode scan log (docs/11 §2.2): every resolve attempt and its outcome. High-volume and
 * purged nightly, so record references are plain uuids rather than foreign keys.
 */
object ScanEventsTable : UUIDTable("scan_events") {
    val tenantId = reference("tenant_id", TenantsTable)
    val scannedAt = timestamp("scanned_at")
    val code = varchar("code", 200)
    val inputMethod = varchar("input_method", 10)
    val context = varchar("context", 24)
    val outcome = varchar("outcome", 16)
    val blockReason = varchar("block_reason", 30).nullable()
    val stockItemId = uuid("stock_item_id").nullable()
    val matchedCodeId = uuid("matched_code_id").nullable()
    val locationId = uuid("location_id").nullable()
    val userId = reference("user_id", AppUsersTable)
    val clientRef = varchar("client_ref", 60).nullable()
}
