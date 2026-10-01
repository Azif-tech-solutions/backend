package com.zifiq.platform.accesscontrol.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object PermissionCatalogTable : UUIDTable("permission_catalog") {
    val code = varchar("code", 160).uniqueIndex()
    val description = varchar("description", 300)
}

object TenantRolesTable : UUIDTable("tenant_roles") {
    val tenantId = reference("tenant_id", TenantsTable)
    val name = varchar("name", 100)
    val description = varchar("description", 300).nullable()
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}

object RolePermissionsTable : Table("role_permissions") {
    val roleId = reference("role_id", TenantRolesTable)
    val permissionId = reference("permission_id", PermissionCatalogTable)

    override val primaryKey = PrimaryKey(roleId, permissionId)
}

object UserRoleAssignmentsTable : Table("user_role_assignments") {
    val tenantId = reference("tenant_id", TenantsTable)
    val userId = reference("user_id", AppUsersTable)
    val roleId = reference("role_id", TenantRolesTable)
    val grantedByUserId = reference("granted_by_user_id", AppUsersTable)
    val grantedAt = timestamp("granted_at")

    override val primaryKey = PrimaryKey(tenantId, userId, roleId)
}
