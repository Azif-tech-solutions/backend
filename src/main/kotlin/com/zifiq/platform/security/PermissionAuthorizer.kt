package com.zifiq.platform.security

import com.zifiq.platform.accesscontrol.model.PermissionCatalogTable
import com.zifiq.platform.accesscontrol.model.RolePermissionsTable
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.accesscontrol.model.UserRoleAssignmentsTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component("permissionAuthorizer")
class PermissionAuthorizer(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
) {
    fun hasPermission(authentication: Authentication?, permission: String): Boolean {
        if (authentication !is org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) return false
        val actor = currentActorProvider.current()
        return hasPermission(actor.userId, permission)
    }

    fun hasPermission(userId: UUID, permission: String): Boolean = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        UserRoleAssignmentsTable
            .join(TenantRolesTable, JoinType.INNER) { UserRoleAssignmentsTable.roleId eq TenantRolesTable.id }
            .join(RolePermissionsTable, JoinType.INNER) { TenantRolesTable.id eq RolePermissionsTable.roleId }
            .join(PermissionCatalogTable, JoinType.INNER) { RolePermissionsTable.permissionId eq PermissionCatalogTable.id }
            .selectAll()
            .andWhere { UserRoleAssignmentsTable.userId eq userId }
            .andWhere { UserRoleAssignmentsTable.tenantId eq tenantId }
            .andWhere { PermissionCatalogTable.code eq permission }
            .limit(1)
            .any()
    }

    /** Every permission code the user holds in the current tenant, through any of their roles. */
    fun permissions(userId: UUID): Set<String> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        UserRoleAssignmentsTable
            .join(TenantRolesTable, JoinType.INNER) { UserRoleAssignmentsTable.roleId eq TenantRolesTable.id }
            .join(RolePermissionsTable, JoinType.INNER) { TenantRolesTable.id eq RolePermissionsTable.roleId }
            .join(PermissionCatalogTable, JoinType.INNER) { RolePermissionsTable.permissionId eq PermissionCatalogTable.id }
            .select(PermissionCatalogTable.code)
            .andWhere { UserRoleAssignmentsTable.userId eq userId }
            .andWhere { UserRoleAssignmentsTable.tenantId eq tenantId }
            .map { it[PermissionCatalogTable.code] }
            .toSet()
    }
}
