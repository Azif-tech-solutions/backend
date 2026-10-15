package com.zifiq.platform.accesscontrol

import com.zifiq.platform.security.AccessCaches
import com.zifiq.platform.accesscontrol.model.PermissionCatalogTable
import com.zifiq.platform.accesscontrol.model.RolePermissionsTable
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.accesscontrol.model.UserRoleAssignmentsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

data class RoleAssignmentSummary(
    val userId: UUID,
    val roleId: UUID,
)

@Service
class RoleAssignmentService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
) {
    fun assignRole(userId: UUID, roleId: UUID): RoleAssignmentSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()

        val grantedByAssignPermission = UserRoleAssignmentsTable
            .join(RolePermissionsTable, JoinType.INNER) { UserRoleAssignmentsTable.roleId eq RolePermissionsTable.roleId }
            .join(PermissionCatalogTable, JoinType.INNER) { RolePermissionsTable.permissionId eq PermissionCatalogTable.id }
            .selectAll()
            .andWhere { UserRoleAssignmentsTable.userId eq actor.userId }
            .andWhere { UserRoleAssignmentsTable.tenantId eq actor.tenantId }
            .andWhere { PermissionCatalogTable.code eq "iam.users.assign" }
            .limit(1)
            .any()

        val isCreator = TenantMembershipsTable
            .join(TenantRolesTable, JoinType.INNER) { TenantMembershipsTable.tenantId eq TenantRolesTable.tenantId }
            .selectAll()
            .andWhere { TenantMembershipsTable.userId eq userId }
            .andWhere { TenantMembershipsTable.tenantId eq actor.tenantId }
            .andWhere { TenantRolesTable.id eq roleId }
            .andWhere { TenantMembershipsTable.createdByUserId eq actor.userId }
            .limit(1)
            .any()

        if (!isCreator && !grantedByAssignPermission) {
            throw ApiErrors.roleAssignmentForbidden()
        }

        UserRoleAssignmentsTable.insertIgnore {
            it[UserRoleAssignmentsTable.tenantId] = actor.tenantId
            it[UserRoleAssignmentsTable.userId] = userId
            it[UserRoleAssignmentsTable.roleId] = roleId
            it[UserRoleAssignmentsTable.grantedByUserId] = actor.userId
            it[UserRoleAssignmentsTable.grantedAt] = Instant.now()
        }
        AccessCaches.permissionsChanged(actor.tenantId)
        RoleAssignmentSummary(userId, roleId)
    }
}
