package com.zifiq.platform.accesscontrol

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.accesscontrol.model.PermissionCatalogTable
import com.zifiq.platform.accesscontrol.model.RolePermissionsTable
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.springframework.stereotype.Service

data class CreateRoleCommand(
    val name: String,
    val description: String?,
    val permissions: Set<String>,
)

data class TenantRoleSummary(
    val id: UUID,
    val name: String,
    val description: String?,
    val permissions: List<String>,
)

private data class MutableTenantRole(
    val id: UUID,
    val name: String,
    val description: String?,
    val permissions: MutableList<String> = mutableListOf(),
)

@Service
class RoleManagementService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
) {
    fun listRoles(): List<TenantRoleSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val roles = linkedMapOf<UUID, MutableTenantRole>()
        TenantRolesTable
            .join(RolePermissionsTable, JoinType.LEFT) { TenantRolesTable.id eq RolePermissionsTable.roleId }
            .join(PermissionCatalogTable, JoinType.LEFT) { RolePermissionsTable.permissionId eq PermissionCatalogTable.id }
            .select(
                TenantRolesTable.id, TenantRolesTable.name, TenantRolesTable.description, PermissionCatalogTable.code,
            )
            .andWhere { TenantRolesTable.tenantId eq tenantId }
            .orderBy(TenantRolesTable.name)
            .forEach { row ->
                val roleId = row[TenantRolesTable.id].value
                val role = roles.getOrPut(roleId) {
                    MutableTenantRole(roleId, row[TenantRolesTable.name], row[TenantRolesTable.description])
                }
                row.getOrNull(PermissionCatalogTable.code)?.let(role.permissions::add)
            }
        roles.values.map { TenantRoleSummary(it.id, it.name, it.description, it.permissions.sorted()) }
    }

    fun createRole(command: CreateRoleCommand): TenantRoleSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val normalizedPermissions = command.permissions.map(String::trim).filter(String::isNotEmpty).toSet()
        val knownPermissions = if (normalizedPermissions.isEmpty()) {
            emptySet()
        } else {
            PermissionCatalogTable
                .select(PermissionCatalogTable.code)
                .andWhere { PermissionCatalogTable.code inList normalizedPermissions }
                .map { it[PermissionCatalogTable.code] }
                .toSet()
        }
        if (knownPermissions != normalizedPermissions) {
            throw ApiErrors.unknownPermissions(normalizedPermissions - knownPermissions)
        }

        val roleId = TenantRolesTable.insertAndGetId {
            it[TenantRolesTable.tenantId] = actor.tenantId
            it[TenantRolesTable.name] = command.name.trim()
            it[TenantRolesTable.description] = command.description?.trim()?.takeIf(String::isNotEmpty)
            it[TenantRolesTable.createdByUserId] = actor.userId
            it[TenantRolesTable.createdAt] = Instant.now()
        }.value

        val permissionIds = if (normalizedPermissions.isEmpty()) {
            emptyList()
        } else {
            PermissionCatalogTable
                .select(PermissionCatalogTable.id)
                .andWhere { PermissionCatalogTable.code inList normalizedPermissions }
                .map { it[PermissionCatalogTable.id].value }
        }
        permissionIds.forEach { permissionId ->
            RolePermissionsTable.insert {
                it[RolePermissionsTable.roleId] = roleId
                it[RolePermissionsTable.permissionId] = permissionId
            }
        }

        TenantRoleSummary(roleId, command.name.trim(), command.description?.trim(), normalizedPermissions.sorted())
    }
}
