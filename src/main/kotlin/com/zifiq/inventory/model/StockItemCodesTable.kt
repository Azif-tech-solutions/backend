package com.zifiq.inventory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Every product code (barcode/QR) a stock item carries (docs/11 §2.1). Lives in the OPEN inventory
 * module so registration can assign a code without depending on the leaf barcode module; the barcode
 * module owns the HTTP API, scan resolution and labels on top of it. `code_normalized` is a stored
 * generated column (never written from Kotlin) that scan resolution looks up by.
 */
object StockItemCodesTable : UUIDTable("stock_item_codes") {
    val tenantId = reference("tenant_id", TenantsTable)
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val code = varchar("code", 64)
    val codeNormalized = varchar("code_normalized", 64)
    val symbology = varchar("symbology", 12)
    val sourceKind = varchar("source", 14)
    val isPrimary = bool("is_primary").default(false)
    val packQuantity = decimal("pack_quantity", 14, 3).default(java.math.BigDecimal.ONE)
    val packLabel = varchar("pack_label", 40).nullable()
    val status = varchar("status", 10).default("ACTIVE")
    val retiredReason = varchar("retired_reason", 200).nullable()
    val assignedByUserId = reference("assigned_by_user_id", AppUsersTable)
    val assignedAt = timestamp("assigned_at")
    val retiredAt = timestamp("retired_at").nullable()
}
