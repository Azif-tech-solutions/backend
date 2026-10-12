package com.zifiq.platform.tenancy

import com.zifiq.platform.accesscontrol.RoleDefaults
import com.zifiq.platform.accesscontrol.model.PermissionCatalogTable
import com.zifiq.platform.accesscontrol.model.RolePermissionsTable
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.accesscontrol.model.UserRoleAssignmentsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.springframework.stereotype.Service

data class CreateTenantRequest(val name: String)

data class TenantSummary(
    val id: UUID,
    val parentTenantId: UUID?,
    val name: String,
)

@Service
class TenantService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val tenantProvisioners: List<TenantProvisioner> = emptyList(),
) {
    fun listChildren(): List<TenantSummary> = tenantScopedTransaction.execute {
        val parentTenantId = currentActorProvider.current().tenantId
        TenantsTable.select(TenantsTable.id, TenantsTable.parentTenantId, TenantsTable.name)
            .andWhere { TenantsTable.parentTenantId eq parentTenantId }
            .orderBy(TenantsTable.name)
            .map {
                TenantSummary(
                    id = it[TenantsTable.id].value,
                    parentTenantId = it[TenantsTable.parentTenantId]?.value,
                    name = it[TenantsTable.name],
                )
            }
    }

    fun createChild(name: String): TenantSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        if (!permissionAuthorizer.hasPermission(actor.userId, "tenants.create_child")) {
            throw ApiErrors.tenantCreationForbidden()
        }

        val childId = TenantsTable.insertAndGetId {
            it[TenantsTable.parentTenantId] = actor.tenantId
            it[TenantsTable.name] = name.trim()
            it[TenantsTable.createdAt] = Instant.now()
        }.value

        // Re-scope the RLS session to the new child tenant for the remaining setup statements.
        TransactionManager.current().exec(
            "select set_config('app.tenant_id', ?, true)",
            listOf(VarCharColumnType() to childId.toString()),
        )

        TenantMembershipsTable.insert {
            it[TenantMembershipsTable.tenantId] = childId
            it[TenantMembershipsTable.userId] = actor.userId
            it[TenantMembershipsTable.createdByUserId] = actor.userId
            it[TenantMembershipsTable.createdAt] = Instant.now()
        }

        seedDefaultRoles(childId, actor.userId)
        tenantProvisioners.forEach { it.provision(childId, actor.userId) }

        val adminRoleId = TenantRolesTable
            .select(TenantRolesTable.id)
            .andWhere { TenantRolesTable.tenantId eq childId }
            .andWhere { TenantRolesTable.name eq RoleDefaults.ADMINISTRATOR }
            .single()[TenantRolesTable.id].value

        UserRoleAssignmentsTable.insert {
            it[UserRoleAssignmentsTable.tenantId] = childId
            it[UserRoleAssignmentsTable.userId] = actor.userId
            it[UserRoleAssignmentsTable.roleId] = adminRoleId
            it[UserRoleAssignmentsTable.grantedByUserId] = actor.userId
            it[UserRoleAssignmentsTable.grantedAt] = Instant.now()
        }

        TenantSummary(childId, actor.tenantId, name.trim())
    }

    companion object {
        /**
         * Seeds the eight PRD-defined roles (see docs/implemented/01-user-tenant-access-control.md §2) for a
         * freshly created tenant. Must be called from inside an active tenant-scoped transaction,
         * already re-scoped (via set_config) to [tenantId]. [RoleDefaults.ROLE_DEFAULTS] is the
         * single source of truth every module extends as it adds its own permission codes.
         */
        internal fun seedDefaultRoles(tenantId: UUID, createdByUserId: UUID) {
            val allPermissionCodes = PermissionCatalogTable.selectAll().map { it[PermissionCatalogTable.code] }.toSet()
            RoleDefaults.ROLE_DEFAULTS.forEach { (roleName, codes) ->
                val resolvedCodes = if (codes.contains(RoleDefaults.ALL_PERMISSIONS)) allPermissionCodes else codes
                val roleId = TenantRolesTable.insertAndGetId {
                    it[TenantRolesTable.tenantId] = tenantId
                    it[TenantRolesTable.name] = roleName
                    it[TenantRolesTable.description] = "System-seeded $roleName role"
                    it[TenantRolesTable.createdByUserId] = createdByUserId
                    it[TenantRolesTable.createdAt] = Instant.now()
                }.value
                if (resolvedCodes.isNotEmpty()) {
                    val permissionIds = PermissionCatalogTable
                        .select(PermissionCatalogTable.id)
                        .andWhere { PermissionCatalogTable.code inList resolvedCodes }
                        .map { it[PermissionCatalogTable.id].value }
                    permissionIds.forEach { permissionId ->
                        RolePermissionsTable.insert {
                            it[RolePermissionsTable.roleId] = roleId
                            it[RolePermissionsTable.permissionId] = permissionId
                        }
                    }
                }
            }
        }
    }
}
