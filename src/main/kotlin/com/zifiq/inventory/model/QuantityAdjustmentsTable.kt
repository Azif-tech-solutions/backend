package com.zifiq.inventory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.security.otp.model.OtpChallengesTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object QuantityAdjustmentsTable : UUIDTable("quantity_adjustments") {
    val tenantId = reference("tenant_id", TenantsTable)
    val locationId = uuid("location_id")
    val ref = varchar("ref", 40)
    val stockItemId = reference("stock_item_id", StockItemsTable)
    val direction = varchar("direction", 10)
    val quantity = decimal("quantity", 14, 3)
    val reason = varchar("reason", 30)
    val note = varchar("note", 300).nullable()
    val otpChallengeId = reference("otp_challenge_id", OtpChallengesTable).nullable()
    val performedByUserId = reference("performed_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
    val mode = varchar("mode", 10)
    val recordedQuantity = decimal("recorded_quantity", 14, 3).nullable()
    val newQuantity = decimal("new_quantity", 14, 3).nullable()
}
