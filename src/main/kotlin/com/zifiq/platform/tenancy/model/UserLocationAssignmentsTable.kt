package com.zifiq.platform.tenancy.model

import com.zifiq.platform.identity.model.AppUsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Which locations a member may access (V3__locations_and_rbac_expansion.sql): SINGLE_LOCATION with
 * a location_id, or ALL_LOCATIONS. location_id is a plain uuid mirroring the SQL foreign key to
 * [LocationsTable].
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
