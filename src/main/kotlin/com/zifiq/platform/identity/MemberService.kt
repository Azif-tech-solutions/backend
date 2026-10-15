package com.zifiq.platform.identity

import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Op
import com.zifiq.common.search.allOf
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchField
import com.zifiq.common.paging.Paging
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.PageRequest
import com.zifiq.platform.security.AccessCaches
import com.zifiq.platform.accesscontrol.RoleAssignmentService
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.accesscontrol.model.UserRoleAssignmentsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.identity.dto.InviteMemberRequest
import com.zifiq.platform.identity.dto.MemberSummary
import com.zifiq.platform.identity.dto.RoleRef
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.identity.model.PasswordResetTokensTable
import com.zifiq.platform.notifications.NotificationChannelType
import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import com.zifiq.platform.tenancy.model.UserLocationAssignmentsTable
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class MemberService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val roleAssignmentService: RoleAssignmentService,
) {
    private val secureRandom = SecureRandom()

    fun inviteMember(request: InviteMemberRequest): MemberSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val email = request.email.trim().lowercase()

        val existingUserId = AppUsersTable.select(AppUsersTable.id)
            .andWhere { AppUsersTable.email eq email }
            .firstOrNull()?.get(AppUsersTable.id)?.value

        val userId = existingUserId ?: AppUsersTable.insertAndGetId {
            it[AppUsersTable.email] = email
            it[AppUsersTable.displayName] = request.displayName.trim()
            it[AppUsersTable.passwordHash] = null
            it[AppUsersTable.createdAt] = Instant.now()
        }.value

        val alreadyMember = TenantMembershipsTable.selectAll()
            .andWhere { TenantMembershipsTable.userId eq userId }
            .andWhere { TenantMembershipsTable.tenantId eq actor.tenantId }
            .limit(1)
            .any()
        if (alreadyMember) {
            throw ApiErrors.userAlreadyMember()
        }

        val roleExists = TenantRolesTable.select(TenantRolesTable.id)
            .andWhere { TenantRolesTable.id eq request.roleId }
            .andWhere { TenantRolesTable.tenantId eq actor.tenantId }
            .limit(1)
            .any()
        if (!roleExists) throw ApiErrors.roleNotFound()

        TenantMembershipsTable.insert {
            it[TenantMembershipsTable.tenantId] = actor.tenantId
            it[TenantMembershipsTable.userId] = userId
            it[TenantMembershipsTable.createdByUserId] = actor.userId
            it[TenantMembershipsTable.createdAt] = Instant.now()
            it[TenantMembershipsTable.status] = "ACTIVE"
        }

        roleAssignmentService.assignRole(userId, request.roleId)

        UserLocationAssignmentsTable.insert {
            it[UserLocationAssignmentsTable.tenantId] = actor.tenantId
            it[UserLocationAssignmentsTable.userId] = userId
            it[UserLocationAssignmentsTable.locationId] = request.locationId
            it[UserLocationAssignmentsTable.accessScope] = if (request.locationId != null) "SINGLE_LOCATION" else "ALL_LOCATIONS"
            it[UserLocationAssignmentsTable.assignedByUserId] = actor.userId
            it[UserLocationAssignmentsTable.assignedAt] = Instant.now()
        }
        AccessCaches.restrictionChanged(actor.tenantId, userId)

        // Reuses the forgot-password token mechanism for first-time password setup.
        val resetToken = "RST-" + (100000 + secureRandom.nextInt(900000))
        PasswordResetTokensTable.insert {
            it[PasswordResetTokensTable.tokenHash] = resetToken
            it[PasswordResetTokensTable.userId] = userId
            it[PasswordResetTokensTable.expiresAt] = Instant.now().plusSeconds(3600 * 72)
            it[PasswordResetTokensTable.createdAt] = Instant.now()
        }
        NotificationOutboxTable.insert {
            it[NotificationOutboxTable.tenantId] = actor.tenantId
            it[NotificationOutboxTable.channel] = NotificationChannelType.EMAIL.name
            it[NotificationOutboxTable.recipient] = email
            it[NotificationOutboxTable.subject] = "You've been invited to ZifIQ"
            it[NotificationOutboxTable.body] = "Hello ${request.displayName}, use code $resetToken with POST /api/v1/auth/reset-password to set your password."
            it[NotificationOutboxTable.metadata] = emptyMap()
            it[NotificationOutboxTable.status] = "PENDING"
            it[NotificationOutboxTable.attempts] = 0
            it[NotificationOutboxTable.createdAt] = Instant.now()
        }

        MemberSummary(
            userId = userId,
            email = email,
            displayName = request.displayName.trim(),
            phone = request.phone,
            status = "ACTIVE",
            role = RoleRef(request.roleId, roleNameOf(request.roleId)),
            locationId = request.locationId,
            accessScope = if (request.locationId != null) "SINGLE_LOCATION" else "ALL_LOCATIONS",
            invitedAt = Instant.now(),
        )
    }

    /**
     * The tenant's members, a page at a time in name order. `q` matches name (trigram) or e-mail
     * (prefix); `status` narrows to ACTIVE/SUSPENDED/INVITED. Roles and location scopes are read for
     * the page's users only.
     */
    fun listMembers(q: String?, status: String?, paging: PageRequest): PageResult<MemberSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val parsed = q?.takeIf { it.isNotBlank() }?.let(MEMBER_SEARCH::parse)
        val condition = listOfNotNull(
            TenantMembershipsTable.tenantId eq tenantId,
            status?.takeIf { it.isNotBlank() }?.let { TenantMembershipsTable.status eq it.uppercase() },
            parsed?.let(MEMBER_SEARCH::condition),
        ).allOf()!!
        val rank = parsed?.let(MEMBER_SEARCH::rank).orEmpty()
        val query = memberRows(condition).orderBy(*(rank + listOf(AppUsersTable.displayName to SortOrder.ASC, AppUsersTable.id to SortOrder.ASC)).toTypedArray())
        val page = Paging.fetch(query, paging) { it }
        page.withItems(summaries(tenantId, page.items))
    }

    private fun memberRows(condition: Op<Boolean>) = AppUsersTable
        .join(TenantMembershipsTable, JoinType.INNER) { AppUsersTable.id eq TenantMembershipsTable.userId }
        .selectAll()
        .where(condition)

    /** One member, read on its own (never by listing the tenant). */
    private fun member(tenantId: UUID, userId: UUID): MemberSummary =
        summaries(tenantId, memberRows((TenantMembershipsTable.tenantId eq tenantId) and (TenantMembershipsTable.userId eq userId)).toList())
            .firstOrNull() ?: throw ApiErrors.memberNotFound()

    private fun summaries(tenantId: UUID, rows: List<ResultRow>): List<MemberSummary> {
        val userIds = rows.map { it[AppUsersTable.id].value }
        if (userIds.isEmpty()) return emptyList()
        val roleByUser = UserRoleAssignmentsTable
            .join(TenantRolesTable, JoinType.INNER) { UserRoleAssignmentsTable.roleId eq TenantRolesTable.id }
            .select(UserRoleAssignmentsTable.userId, TenantRolesTable.id, TenantRolesTable.name)
            .where { (UserRoleAssignmentsTable.tenantId eq tenantId) and (UserRoleAssignmentsTable.userId inList userIds) }
            .associate { it[UserRoleAssignmentsTable.userId].value to RoleRef(it[TenantRolesTable.id].value, it[TenantRolesTable.name]) }
        val locationByUser = UserLocationAssignmentsTable.selectAll()
            .where { (UserLocationAssignmentsTable.tenantId eq tenantId) and (UserLocationAssignmentsTable.userId inList userIds) }
            .associate { it[UserLocationAssignmentsTable.userId].value to Pair(it[UserLocationAssignmentsTable.locationId], it[UserLocationAssignmentsTable.accessScope]) }
        return rows.map { row ->
            val userId = row[AppUsersTable.id].value
            val location = locationByUser[userId]
            MemberSummary(
                userId = userId,
                email = row[AppUsersTable.email],
                displayName = row[AppUsersTable.displayName],
                phone = null,
                status = row[TenantMembershipsTable.status],
                role = roleByUser[userId],
                locationId = location?.first,
                accessScope = location?.second ?: "ALL_LOCATIONS",
                invitedAt = row[TenantMembershipsTable.createdAt],
            )
        }
    }

    fun updateStatus(userId: UUID, status: String): MemberSummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val updated = TenantMembershipsTable.update({
            (TenantMembershipsTable.userId eq userId) and (TenantMembershipsTable.tenantId eq tenantId)
        }) {
            it[TenantMembershipsTable.status] = status
        }
        if (updated == 0) throw ApiErrors.memberNotFound()
        member(tenantId, userId)
    }

    fun updateLocation(userId: UUID, locationId: UUID?): MemberSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val isMember = TenantMembershipsTable.selectAll()
            .andWhere { TenantMembershipsTable.userId eq userId }
            .andWhere { TenantMembershipsTable.tenantId eq actor.tenantId }
            .limit(1)
            .any()
        if (!isMember) throw ApiErrors.memberNotFound()

        val accessScope = if (locationId != null) "SINGLE_LOCATION" else "ALL_LOCATIONS"
        AccessCaches.restrictionChanged(actor.tenantId, userId)
        val updated = UserLocationAssignmentsTable.update({
            (UserLocationAssignmentsTable.userId eq userId) and (UserLocationAssignmentsTable.tenantId eq actor.tenantId)
        }) {
            it[UserLocationAssignmentsTable.locationId] = locationId
            it[UserLocationAssignmentsTable.accessScope] = accessScope
            it[UserLocationAssignmentsTable.assignedByUserId] = actor.userId
            it[UserLocationAssignmentsTable.assignedAt] = Instant.now()
        }
        if (updated == 0) {
            UserLocationAssignmentsTable.insert {
                it[UserLocationAssignmentsTable.tenantId] = actor.tenantId
                it[UserLocationAssignmentsTable.userId] = userId
                it[UserLocationAssignmentsTable.locationId] = locationId
                it[UserLocationAssignmentsTable.accessScope] = accessScope
                it[UserLocationAssignmentsTable.assignedByUserId] = actor.userId
                it[UserLocationAssignmentsTable.assignedAt] = Instant.now()
            }
        }
        member(actor.tenantId, userId)
    }

    private fun roleNameOf(roleId: UUID): String =
        TenantRolesTable.select(TenantRolesTable.name)
            .andWhere { TenantRolesTable.id eq roleId }
            .single()[TenantRolesTable.name]

    private companion object {
        val MEMBER_SEARCH = SearchSpec(
            listOf(
                SearchField(AppUsersTable.email, SearchKind.CODE, "E-mail"),
                SearchField(AppUsersTable.displayName, SearchKind.TRIGRAM, "Name"),
            ),
        )
    }
}
