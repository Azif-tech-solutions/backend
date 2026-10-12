package com.zifiq.platform.security.otp.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object OtpChallengesTable : UUIDTable("otp_challenges") {
    val tenantId = reference("tenant_id", TenantsTable)
    val requestedByUserId = reference("requested_by_user_id", AppUsersTable)
    val purpose = varchar("purpose", 30)
    val targetReference = varchar("target_reference", 60)
    val codeHash = varchar("code_hash", 255).nullable()
    val status = varchar("status", 20).default("AWAITING")
    val issuedByUserId = reference("issued_by_user_id", AppUsersTable).nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val createdAt = timestamp("created_at")
    /** What the challenge authorises (docs/03 §9.4), e.g. {"amount": 120000, "targetLabel": "…"}; raw JSON text. */
    val context = jsonb<String>("context", { it }, { it })
}
