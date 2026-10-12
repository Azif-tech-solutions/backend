package com.zifiq.platform.security.otp

import com.zifiq.platform.accesscontrol.model.PermissionCatalogTable
import com.zifiq.platform.accesscontrol.model.RolePermissionsTable
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.accesscontrol.model.UserRoleAssignmentsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.notifications.NotificationChannelType
import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.otp.dto.OtpChallengeRequest
import com.zifiq.platform.security.otp.dto.OtpChallengeSummary
import com.zifiq.platform.security.otp.dto.OtpIssueResponse
import com.zifiq.platform.security.otp.dto.OtpVerifyResponse
import com.zifiq.platform.security.otp.dto.VerifiedChallenge
import com.zifiq.platform.security.otp.model.OtpChallengesTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

private const val EXPIRY_MINUTES = 15L

@Service
class OtpChallengeService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val passwordEncoder: PasswordEncoder,
) {
    private val secureRandom = SecureRandom()

    fun request(command: OtpChallengeRequest): OtpChallengeSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()

        val challengeId = OtpChallengesTable.insertAndGetId {
            it[OtpChallengesTable.tenantId] = actor.tenantId
            it[OtpChallengesTable.requestedByUserId] = actor.userId
            it[OtpChallengesTable.purpose] = command.purpose
            it[OtpChallengesTable.targetReference] = command.targetReference
            it[OtpChallengesTable.context] = com.zifiq.common.json.Json.write(command.context)
            it[OtpChallengesTable.status] = "AWAITING"
            it[OtpChallengesTable.createdAt] = Instant.now()
        }.value

        // Notify every tenant member holding otp.issue (Administrators by default) — PRD §8:
        // "Wallet withdrawal or qty adjustment requested -> Admin -> In-app request".
        val issuers = AppUsersTable
            .join(UserRoleAssignmentsTable, JoinType.INNER) { AppUsersTable.id eq UserRoleAssignmentsTable.userId }
            .join(TenantRolesTable, JoinType.INNER) { UserRoleAssignmentsTable.roleId eq TenantRolesTable.id }
            .join(RolePermissionsTable, JoinType.INNER) { TenantRolesTable.id eq RolePermissionsTable.roleId }
            .join(PermissionCatalogTable, JoinType.INNER) { RolePermissionsTable.permissionId eq PermissionCatalogTable.id }
            .select(AppUsersTable.email)
            .andWhere { UserRoleAssignmentsTable.tenantId eq actor.tenantId }
            .andWhere { PermissionCatalogTable.code eq "otp.issue" }
            .map { it[AppUsersTable.email] }
            .distinct()

        issuers.forEach { recipientEmail ->
            NotificationOutboxTable.insert {
                it[NotificationOutboxTable.tenantId] = actor.tenantId
                it[NotificationOutboxTable.channel] = NotificationChannelType.PUSH.name
                it[NotificationOutboxTable.recipient] = recipientEmail
                it[NotificationOutboxTable.subject] = "OTP challenge requested"
                it[NotificationOutboxTable.body] =
                    "A ${command.purpose} OTP was requested for ${command.targetReference}. Review and issue a code."
                it[NotificationOutboxTable.metadata] = mapOf("challengeId" to challengeId.toString())
                it[NotificationOutboxTable.status] = "PENDING"
                it[NotificationOutboxTable.attempts] = 0
                it[NotificationOutboxTable.createdAt] = Instant.now()
            }
        }

        OtpChallengeSummary(challengeId, "AWAITING", null)
    }

    fun issue(challengeId: UUID): OtpIssueResponse = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val challenge = OtpChallengesTable.selectAll()
            .andWhere { OtpChallengesTable.id eq challengeId }
            .firstOrNull() ?: throw ApiErrors.otpChallengeNotFound()

        if (challenge[OtpChallengesTable.status] != "AWAITING") {
            throw ApiErrors.otpChallengeNotAwaiting()
        }

        val code = (100000 + secureRandom.nextInt(900000)).toString()
        val expiresAt = Instant.now().plusSeconds(EXPIRY_MINUTES * 60)

        OtpChallengesTable.update({ OtpChallengesTable.id eq challengeId }) {
            it[OtpChallengesTable.codeHash] = passwordEncoder.encode(code)
            it[OtpChallengesTable.status] = "SENT"
            it[OtpChallengesTable.issuedByUserId] = actor.userId
            it[OtpChallengesTable.expiresAt] = expiresAt
        }

        val requesterEmail = AppUsersTable.select(AppUsersTable.email)
            .andWhere { AppUsersTable.id eq challenge[OtpChallengesTable.requestedByUserId].value }
            .firstOrNull()?.get(AppUsersTable.email)

        if (requesterEmail != null) {
            NotificationOutboxTable.insert {
                it[NotificationOutboxTable.tenantId] = actor.tenantId
                it[NotificationOutboxTable.channel] = NotificationChannelType.PUSH.name
                it[NotificationOutboxTable.recipient] = requesterEmail
                it[NotificationOutboxTable.subject] = "Your OTP code"
                it[NotificationOutboxTable.body] = "Your one-time code is: $code. It expires in $EXPIRY_MINUTES minutes."
                it[NotificationOutboxTable.metadata] = mapOf("challengeId" to challengeId.toString())
                it[NotificationOutboxTable.status] = "PENDING"
                it[NotificationOutboxTable.attempts] = 0
                it[NotificationOutboxTable.createdAt] = Instant.now()
            }
        }

        OtpIssueResponse(challengeId, code, expiresAt)
    }

    fun verify(challengeId: UUID, code: String): OtpVerifyResponse = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val challenge = OtpChallengesTable.selectAll()
            .andWhere { OtpChallengesTable.id eq challengeId }
            .firstOrNull() ?: throw ApiErrors.otpChallengeNotFound()

        if (challenge[OtpChallengesTable.requestedByUserId].value != actor.userId) {
            throw ApiErrors.notOtpChallengeOwner()
        }

        val expiresAt = challenge[OtpChallengesTable.expiresAt]
        if (challenge[OtpChallengesTable.status] != "SENT" || expiresAt == null || expiresAt.isBefore(Instant.now())) {
            OtpChallengesTable.update({ OtpChallengesTable.id eq challengeId }) {
                it[OtpChallengesTable.status] = "EXPIRED"
            }
            throw ApiErrors.otpExpired()
        }

        val codeHash = challenge[OtpChallengesTable.codeHash]
        if (codeHash == null || !passwordEncoder.matches(code, codeHash)) {
            throw ApiErrors.invalidOtpCode()
        }

        OtpChallengesTable.update({ OtpChallengesTable.id eq challengeId }) {
            it[OtpChallengesTable.status] = "USED"
        }

        OtpVerifyResponse(true)
    }

    /**
     * Used by consumers (e.g. Factory quantity adjustments) to confirm a challenge was verified,
     * for the expected purpose, by the same user now attempting the gated action.
     */
    fun isVerified(challengeId: UUID, purpose: String): Boolean = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        OtpChallengesTable.selectAll()
            .andWhere { OtpChallengesTable.id eq challengeId }
            .andWhere { OtpChallengesTable.purpose eq purpose }
            .andWhere { OtpChallengesTable.requestedByUserId eq actor.userId }
            .andWhere { OtpChallengesTable.status eq "USED" }
            .limit(1)
            .any()
    }

    /**
     * Like [isVerified], but returns what the challenge was issued for, so the gated action can check
     * it matches (a code issued for ₦50,000 must not authorise ₦500,000). Runs in the caller's transaction.
     */
    fun verifiedChallenge(challengeId: UUID, purpose: String): VerifiedChallenge? {
        val actor = currentActorProvider.current()
        return OtpChallengesTable.selectAll()
            .andWhere { OtpChallengesTable.id eq challengeId }
            .andWhere { OtpChallengesTable.tenantId eq actor.tenantId }
            .andWhere { OtpChallengesTable.purpose eq purpose }
            .andWhere { OtpChallengesTable.requestedByUserId eq actor.userId }
            .andWhere { OtpChallengesTable.status eq "USED" }
            .firstOrNull()
            ?.let { row ->
                @Suppress("UNCHECKED_CAST")
                val context = com.zifiq.common.json.Json.mapper.readValue(row[OtpChallengesTable.context], Map::class.java) as Map<String, Any?>
                VerifiedChallenge(row[OtpChallengesTable.id].value, row[OtpChallengesTable.purpose], row[OtpChallengesTable.targetReference], context)
            }
    }
}
