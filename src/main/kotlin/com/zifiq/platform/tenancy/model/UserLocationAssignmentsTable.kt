package com.zifiq.platform.tenancy.model

import com.zifiq.platform.identity.model.AppUsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Deliberately does NOT Exposed-`reference()` factory.model.LocationsTable — that would create a
 * platform -> business-module Kotlin import, inverting the intended dependency direction (business
 * modules depend on platform, not the reverse). location_id mirrors the SQL foreign key declared in
 * V3__locations_and_rbac_expansion.sql; referential integrity is enforced by Postgres, not Exposed.
 */
object UserLocationAssignmentsTable : Table("user_location_assignments") {
    val tenantId = reference("tenant_id", TenantsTable)
    val userId = reference("user_id", AppUsersTable)
    val locationId = uuid("location_id").nullable()
    val accessScope = varchar("access_scope", 20)
    val assignedByUserId = reference("assigned_by_user_id", AppUsersTable)
    val assignedAt = timestamp("assigned_at")

    override val primaryKey = PrimaryKey(tenantId, userId)
}
