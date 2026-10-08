package com.zifiq.platform.identity

import com.zifiq.platform.accesscontrol.RoleDefaults
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.accesscontrol.model.UserRoleAssignmentsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.identity.dto.*
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.identity.model.PasswordResetTokensTable
import com.zifiq.platform.identity.model.RefreshTokensTable
import com.zifiq.platform.notifications.NotificationChannelType
import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.JwtTokenProvider
import com.zifiq.platform.tenancy.TenantProvisioner
import com.zifiq.platform.tenancy.TenantService
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.jetbrains.exposed.sql.Database as ExposedDatabase

private data class RefreshTokenRecord(
    val id: UUID,
    val userId: UUID,
    val tenantId: UUID,
    val expiresAt: Instant,
    val revoked: Boolean,
)

@Service
class AuthService(
    private val exposedDatabase: ExposedDatabase,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val currentActorProvider: CurrentActorProvider,
    private val tenantProvisioners: List<TenantProvisioner> = emptyList(),
) {
    private val secureRandom = SecureRandom()

    fun registerTenant(request: TenantRegistrationRequest): TenantRegistrationResponse = transaction(exposedDatabase) {
        val email = request.adminEmail.trim().lowercase()

        val existingUser = AppUsersTable
            .select(AppUsersTable.id)
            .andWhere { AppUsersTable.email.lowerCase() eq email }
            .firstOrNull()

        if (existingUser != null) {
            throw ApiErrors.userExists()
        }

        val passwordHash = passwordEncoder.encode(request.password)

        // 1. Create User
        val userId = AppUsersTable.insertAndGetId {
            it[AppUsersTable.email] = email
            it[AppUsersTable.displayName] = request.adminDisplayName.trim()
            it[AppUsersTable.passwordHash] = passwordHash
            it[AppUsersTable.createdAt] = Instant.now()
        }.value

        // 2. Create Root Tenant
        val tenantId = TenantsTable.insertAndGetId {
            it[TenantsTable.name] = request.companyName.trim()
            it[TenantsTable.createdAt] = Instant.now()
        }.value

        // Set RLS session context for setup
        TransactionManager.current().exec(
            "select set_config('app.tenant_id', ?, true)",
            listOf(VarCharColumnType() to tenantId.toString()),
        )

        // 3. Create Tenant Membership
        TenantMembershipsTable.insert {
            it[TenantMembershipsTable.tenantId] = tenantId
            it[TenantMembershipsTable.userId] = userId
            it[TenantMembershipsTable.createdByUserId] = userId
            it[TenantMembershipsTable.createdAt] = Instant.now()
        }

        // 4. Seed the eight PRD-defined roles (docs/01 §2), assign Administrator to the creator
        TenantService.seedDefaultRoles(tenantId, userId)
        tenantProvisioners.forEach { it.provision(tenantId, userId) }

        val adminRoleId = TenantRolesTable
            .select(TenantRolesTable.id)
            .andWhere { TenantRolesTable.tenantId eq tenantId }
            .andWhere { TenantRolesTable.name eq RoleDefaults.ADMINISTRATOR }
            .single()[TenantRolesTable.id].value

        UserRoleAssignmentsTable.insert {
            it[UserRoleAssignmentsTable.tenantId] = tenantId
            it[UserRoleAssignmentsTable.userId] = userId
            it[UserRoleAssignmentsTable.roleId] = adminRoleId
            it[UserRoleAssignmentsTable.grantedByUserId] = userId
            it[UserRoleAssignmentsTable.grantedAt] = Instant.now()
        }

        // 5. Generate Tokens
        val tokenResponse = issueTokens(userId, tenantId, email, request.adminDisplayName.trim())

        TenantRegistrationResponse(
            tenantId = tenantId,
            userId = userId,
            companyName = request.companyName.trim(),
            adminEmail = email,
            tokenResponse = tokenResponse,
        )
    }

    fun login(request: UserLoginRequest): TokenResponse = transaction(exposedDatabase) {
        val email = request.email.trim().lowercase()

        val userRow = AppUsersTable
            .select(AppUsersTable.id, AppUsersTable.displayName, AppUsersTable.passwordHash)
            .andWhere { AppUsersTable.email.lowerCase() eq email }
            .firstOrNull() ?: throw ApiErrors.invalidCredentials()

        val userId = userRow[AppUsersTable.id].value
        val displayName = userRow[AppUsersTable.displayName]
        val storedHash = userRow[AppUsersTable.passwordHash]

        if (storedHash == null || !passwordEncoder.matches(request.password, storedHash)) {
            throw ApiErrors.invalidCredentials()
        }

        // Get tenant memberships (not RLS-protected by user_id, but each tenant_memberships row
        // is itself RLS-scoped to app.tenant_id — this query deliberately runs with no tenant
        // context set yet, relying on tenant_memberships having no RLS policy of its own keyed
        // off the querying user, which is why this table's policy only checks tenant_id; see
        // docs/00-platform-foundation.md for the login-time bootstrap ordering).
        val tenantIds = TenantMembershipsTable
            .select(TenantMembershipsTable.tenantId)
            .andWhere { TenantMembershipsTable.userId eq userId }
            .orderBy(TenantMembershipsTable.createdAt)
            .map { it[TenantMembershipsTable.tenantId].value }

        if (tenantIds.isEmpty()) {
            throw ApiErrors.noTenantMembership()
        }

        val targetTenantId = request.tenantId ?: tenantIds.first()
        if (!tenantIds.contains(targetTenantId)) {
            throw ApiErrors.notMemberOfRequestedTenant()
        }

        issueTokens(userId, targetTenantId, email, displayName)
    }

    fun switchTenant(request: TenantSwitchRequest): TokenResponse = transaction(exposedDatabase) {
        val actor = currentActorProvider.current()
        val isMember = TenantMembershipsTable
            .select(TenantMembershipsTable.userId)
            .andWhere { TenantMembershipsTable.userId eq actor.userId }
            .andWhere { TenantMembershipsTable.tenantId eq request.targetTenantId }
            .limit(1)
            .any()

        if (!isMember) {
            throw ApiErrors.tenantSwitchForbidden()
        }

        val userRow = AppUsersTable
            .select(AppUsersTable.email, AppUsersTable.displayName)
            .andWhere { AppUsersTable.id eq actor.userId }
            .firstOrNull() ?: throw ApiErrors.userNotFound()

        issueTokens(actor.userId, request.targetTenantId, userRow[AppUsersTable.email], userRow[AppUsersTable.displayName])
    }

    fun refreshToken(request: RefreshTokenRequest): TokenResponse = transaction(exposedDatabase) {
        val rawToken = request.refreshToken.trim()

        val tokenRow = RefreshTokensTable
            .selectAll()
            .andWhere { RefreshTokensTable.tokenHash eq rawToken }
            .firstOrNull()?.let {
                RefreshTokenRecord(
                    id = it[RefreshTokensTable.id].value,
                    userId = it[RefreshTokensTable.userId].value,
                    tenantId = it[RefreshTokensTable.tenantId].value,
                    expiresAt = it[RefreshTokensTable.expiresAt],
                    revoked = it[RefreshTokensTable.revoked],
                )
            } ?: throw ApiErrors.invalidRefreshToken()

        if (tokenRow.revoked || tokenRow.expiresAt.isBefore(Instant.now())) {
            throw ApiErrors.expiredRefreshToken()
        }

        // Revoke used refresh token
        RefreshTokensTable.update({ RefreshTokensTable.id eq tokenRow.id }) {
            it[RefreshTokensTable.revoked] = true
        }

        val userRow = AppUsersTable
            .select(AppUsersTable.email, AppUsersTable.displayName)
            .andWhere { AppUsersTable.id eq tokenRow.userId }
            .firstOrNull() ?: throw ApiErrors.userNotFound()

        issueTokens(tokenRow.userId, tokenRow.tenantId, userRow[AppUsersTable.email], userRow[AppUsersTable.displayName])
    }

    fun changePassword(request: ChangePasswordRequest): Unit = transaction(exposedDatabase) {
        val actor = currentActorProvider.current()

        val currentHash = AppUsersTable
            .select(AppUsersTable.passwordHash)
            .andWhere { AppUsersTable.id eq actor.userId }
            .firstOrNull()?.get(AppUsersTable.passwordHash)
            ?: throw ApiErrors.userNotFound()

        if (!passwordEncoder.matches(request.currentPassword, currentHash)) {
            throw ApiErrors.invalidCurrentPassword()
        }

        val newHash = passwordEncoder.encode(request.newPassword)
        AppUsersTable.update({ AppUsersTable.id eq actor.userId }) {
            it[AppUsersTable.passwordHash] = newHash
        }
        Unit
    }

    fun forgotPassword(request: ForgotPasswordRequest): PasswordResetResponse = transaction(exposedDatabase) {
        val email = request.email.trim().lowercase()

        val userRow = AppUsersTable
            .select(AppUsersTable.id, AppUsersTable.displayName)
            .andWhere { AppUsersTable.email.lowerCase() eq email }
            .firstOrNull()

        if (userRow != null) {
            val userId = userRow[AppUsersTable.id].value
            val displayName = userRow[AppUsersTable.displayName]
            val resetToken = "RST-" + (100000 + secureRandom.nextInt(900000))
            val expiresAt = Instant.now().plusSeconds(900) // 15 minutes

            PasswordResetTokensTable.insert {
                it[PasswordResetTokensTable.tokenHash] = resetToken
                it[PasswordResetTokensTable.userId] = userId
                it[PasswordResetTokensTable.expiresAt] = expiresAt
                it[PasswordResetTokensTable.createdAt] = Instant.now()
            }

            // notification_outbox is RLS-scoped on tenant_id, so the row must be written under a
            // real tenant's app.tenant_id — use the user's first (oldest) tenant membership purely
            // as the delivery scope; this grants no cross-tenant data access, it only determines
            // which tenant's RLS context the INSERT runs under. Queueing via the outbox (rather
            // than calling NotificationDispatcher inline) lets NotificationOutboxDispatcher drain
            // it, per docs/00-platform-foundation.md §5.2.
            val deliveryTenantId = TenantMembershipsTable
                .select(TenantMembershipsTable.tenantId)
                .andWhere { TenantMembershipsTable.userId eq userId }
                .orderBy(TenantMembershipsTable.createdAt)
                .firstOrNull()?.get(TenantMembershipsTable.tenantId)?.value

            if (deliveryTenantId != null) {
                TransactionManager.current().exec(
                    "select set_config('app.tenant_id', ?, true)",
                    listOf(VarCharColumnType() to deliveryTenantId.toString()),
                )
                NotificationOutboxTable.insert {
                    it[NotificationOutboxTable.tenantId] = deliveryTenantId
                    it[NotificationOutboxTable.channel] = NotificationChannelType.EMAIL.name
                    it[NotificationOutboxTable.recipient] = email
                    it[NotificationOutboxTable.subject] = "ZifIQ Password Reset Request"
                    it[NotificationOutboxTable.body] = "Hello $displayName, your password reset code is: $resetToken. It expires in 15 minutes."
                    it[NotificationOutboxTable.metadata] = emptyMap()
                    it[NotificationOutboxTable.status] = "PENDING"
                    it[NotificationOutboxTable.attempts] = 0
                    it[NotificationOutboxTable.createdAt] = Instant.now()
                }
            }
        }

        PasswordResetResponse(
            message = "If an account exists with that email address, password reset instructions have been sent.",
            status = "PENDING_VERIFICATION",
        )
    }

    fun resetPassword(request: ResetPasswordRequest): PasswordResetResponse = transaction(exposedDatabase) {
        val resetToken = request.resetToken.trim()

        val tokenRow = PasswordResetTokensTable
            .selectAll()
            .andWhere { PasswordResetTokensTable.tokenHash eq resetToken }
            .firstOrNull() ?: throw ApiErrors.invalidResetToken()

        val tokenId = tokenRow[PasswordResetTokensTable.id].value
        val userId = tokenRow[PasswordResetTokensTable.userId].value
        val expiresAt = tokenRow[PasswordResetTokensTable.expiresAt]
        val usedAt = tokenRow[PasswordResetTokensTable.usedAt]

        if (usedAt != null || expiresAt.isBefore(Instant.now())) {
            throw ApiErrors.expiredResetToken()
        }

        val newHash = passwordEncoder.encode(request.newPassword)
        AppUsersTable.update({ AppUsersTable.id eq userId }) {
            it[AppUsersTable.passwordHash] = newHash
        }
        PasswordResetTokensTable.update({ PasswordResetTokensTable.id eq tokenId }) {
            it[PasswordResetTokensTable.usedAt] = Instant.now()
        }

        PasswordResetResponse(
            message = "Password has been successfully reset. You can now log in with your new password.",
            status = "SUCCESS",
        )
    }

    private fun issueTokens(userId: UUID, tenantId: UUID, email: String, displayName: String): TokenResponse {
        val accessToken = jwtTokenProvider.generateAccessToken(userId, tenantId, email, displayName)
        val refreshToken = "RT-" + UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString().take(8)

        val expiresAt = Instant.now().plusSeconds(jwtTokenProvider.refreshTokenValiditySeconds)
        RefreshTokensTable.insert {
            it[RefreshTokensTable.tokenHash] = refreshToken
            it[RefreshTokensTable.userId] = userId
            it[RefreshTokensTable.tenantId] = tenantId
            it[RefreshTokensTable.expiresAt] = expiresAt
            it[RefreshTokensTable.createdAt] = Instant.now()
        }

        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = jwtTokenProvider.accessTokenValiditySeconds,
            activeTenantId = tenantId,
            userId = userId,
        )
    }
}
