package com.zifiq.platform.notifications

import com.zifiq.platform.accesscontrol.model.PermissionCatalogTable
import com.zifiq.platform.accesscontrol.model.RolePermissionsTable
import com.zifiq.platform.accesscontrol.model.UserRoleAssignmentsTable
import com.zifiq.platform.notifications.model.UserNotificationsTable
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import com.zifiq.platform.tenancy.model.UserLocationAssignmentsTable
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.or
import org.springframework.stereotype.Component

enum class NotificationCategory { APPROVAL_REQUIRED, ALERT, INFO, CONFIRMATION }

/**
 * Resolves "holders of <permission> at <location>" to user ids — docs/03 §9.2. Every module names
 * its notification recipients this way rather than with placeholder strings. Runs in the caller's
 * transaction.
 */
@Component
class Recipients(private val tenantContext: TenantContext) {
    /**
     * Active members of the current tenant who hold [permission] and may see [locationId]: users
     * with no location assignment or ALL_LOCATIONS always qualify; SINGLE_LOCATION users only for
     * their own location. A null [locationId] means "tenant-wide", so everyone holding it qualifies.
     */
    fun withPermission(permission: String, locationId: UUID? = null): Set<UUID> {
        val tenantId = tenantContext.requireTenantId()
        val query = UserRoleAssignmentsTable
            .join(RolePermissionsTable, JoinType.INNER) { UserRoleAssignmentsTable.roleId eq RolePermissionsTable.roleId }
            .join(PermissionCatalogTable, JoinType.INNER) { RolePermissionsTable.permissionId eq PermissionCatalogTable.id }
            .join(TenantMembershipsTable, JoinType.INNER) {
                (TenantMembershipsTable.tenantId eq UserRoleAssignmentsTable.tenantId) and (TenantMembershipsTable.userId eq UserRoleAssignmentsTable.userId)
            }
            .join(UserLocationAssignmentsTable, JoinType.LEFT) {
                (UserLocationAssignmentsTable.tenantId eq UserRoleAssignmentsTable.tenantId) and (UserLocationAssignmentsTable.userId eq UserRoleAssignmentsTable.userId)
            }
            .select(UserRoleAssignmentsTable.userId)
            .where {
                var condition = (UserRoleAssignmentsTable.tenantId eq tenantId) and
                    (PermissionCatalogTable.code eq permission) and
                    (TenantMembershipsTable.status eq "ACTIVE")
                if (locationId != null) {
                    condition = condition and (
                        UserLocationAssignmentsTable.userId.isNull() or
                            (UserLocationAssignmentsTable.accessScope eq "ALL_LOCATIONS") or
                            (UserLocationAssignmentsTable.locationId eq locationId)
                        )
                }
                condition
            }
            .withDistinct()
        return query.map { it[UserRoleAssignmentsTable.userId].value }.toSet()
    }
}

/**
 * In-app notifications, written in the caller's transaction — docs/03 §9.3. A rolled-back action
 * therefore never notifies anyone, and a committed one always does.
 */
@Component
class Notify(
    private val tenantContext: TenantContext,
    private val recipients: Recipients,
) {
    fun inApp(
        userIds: Collection<UUID>,
        category: NotificationCategory,
        title: String,
        body: String,
        linkType: String? = null,
        linkRef: String? = null,
        sourceEventId: UUID? = null,
        exclude: UUID? = null,
    ): Int {
        val targets = userIds.filter { it != exclude }.distinct()
        if (targets.isEmpty()) return 0
        val tenantId = tenantContext.requireTenantId()
        val now = Instant.now()
        UserNotificationsTable.batchInsert(targets, shouldReturnGeneratedValues = false) { userId ->
            this[UserNotificationsTable.tenantId] = tenantId
            this[UserNotificationsTable.userId] = userId
            this[UserNotificationsTable.category] = category.name
            this[UserNotificationsTable.title] = title.take(160)
            this[UserNotificationsTable.body] = body.take(600)
            this[UserNotificationsTable.linkType] = linkType
            this[UserNotificationsTable.linkRef] = linkRef
            this[UserNotificationsTable.sourceEventId] = sourceEventId
            this[UserNotificationsTable.createdAt] = now
        }
        return targets.size
    }

    /** Notifies everyone holding [permission] at [locationId] (see [Recipients.withPermission]). */
    fun holdersOf(
        permission: String,
        locationId: UUID?,
        category: NotificationCategory,
        title: String,
        body: String,
        linkType: String? = null,
        linkRef: String? = null,
        sourceEventId: UUID? = null,
        exclude: UUID? = null,
    ): Int = inApp(recipients.withPermission(permission, locationId), category, title, body, linkType, linkRef, sourceEventId, exclude)
}
