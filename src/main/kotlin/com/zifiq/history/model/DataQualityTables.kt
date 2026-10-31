package com.zifiq.history.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Error Handler data-quality flags (docs/10 §2.5, §4.8). See V16_2. */
object DataQualityFlagsTable : UUIDTable("data_quality_flags") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val entityType = varchar("entity_type", 20)
    val entityId = uuid("entity_id")
    val entityCode = varchar("entity_code", 40).nullable()
    val field = varchar("field", 20)
    val currentValue = varchar("current_value", 200)
    val suggestedValue = varchar("suggested_value", 200).nullable()
    val kind = varchar("kind", 10)
    val sourceKind = varchar("source", 10)
    val flaggedByUserId = uuid("flagged_by_user_id").nullable()
    val status = varchar("status", 12)
    val appliedValue = varchar("applied_value", 200).nullable()
    val resolvedByUserId = uuid("resolved_by_user_id").nullable()
    val resolvedAt = timestamp("resolved_at").nullable()
    val dismissReason = varchar("dismiss_reason", 300).nullable()
    val createdAt = timestamp("created_at")
}

object DataQualityIgnoresTable : Table("data_quality_ignores") {
    val tenantId = reference("tenant_id", TenantsTable)
    val token = varchar("token", 120)
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(tenantId, token)
}
