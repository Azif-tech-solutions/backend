package com.zifiq.history.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/** Recycle Bin action log (docs/10 §2.6, §4.9). See V16_2. */
object RecycleBinLogTable : UUIDTable("recycle_bin_log") {
    val tenantId = reference("tenant_id", TenantsTable)
    val stockItemId = uuid("stock_item_id")
    val action = varchar("action", 12)
    val reason = varchar("reason", 300).nullable()
    val quantityHeld = decimal("quantity_held", 14, 3).nullable()
    val valueHeld = decimal("value_held", 14, 2).nullable()
    val reviewOn = date("review_on").nullable()
    val retentionUntil = date("retention_until").nullable()
    val writeOffQuantity = decimal("write_off_quantity", 14, 3).nullable()
    val byUserId = reference("by_user_id", AppUsersTable)
    val at = timestamp("at")
}
