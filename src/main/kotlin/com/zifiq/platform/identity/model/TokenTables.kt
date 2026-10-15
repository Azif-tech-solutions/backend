package com.zifiq.platform.identity.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object RefreshTokensTable : UUIDTable("refresh_tokens") {
    val tokenHash = varchar("token_hash", 255).uniqueIndex()
    val userId = reference("user_id", AppUsersTable)
    val tenantId = reference("tenant_id", TenantsTable)
    /** The factory or showroom the session had switched to, kept across refreshes (V14_10). */
    val locationId = uuid("location_id").nullable()
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val revoked = bool("revoked").default(false)
}

object PasswordResetTokensTable : UUIDTable("password_reset_tokens") {
    val tokenHash = varchar("token_hash", 255).uniqueIndex()
    val userId = reference("user_id", AppUsersTable)
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()
    val createdAt = timestamp("created_at")
}
