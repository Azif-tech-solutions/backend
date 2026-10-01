package com.zifiq.platform.tenancy.model

import com.zifiq.platform.identity.model.AppUsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object TenantMembershipsTable : Table("tenant_memberships") {
    val tenantId = reference("tenant_id", TenantsTable)
    val userId = reference("user_id", AppUsersTable)
    val createdByUserId = reference("created_by_user_id", AppUsersTable).nullable()
    val createdAt = timestamp("created_at")
    val status = varchar("status", 20).default("ACTIVE")

    override val primaryKey = PrimaryKey(tenantId, userId)
}
