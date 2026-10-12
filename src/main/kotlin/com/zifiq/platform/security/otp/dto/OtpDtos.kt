package com.zifiq.platform.security.otp.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

data class OtpChallengeRequest(
    @field:Pattern(regexp = "WALLET_WITHDRAWAL|QUANTITY_ADJUSTMENT")
    val purpose: String,
    @field:NotBlank val targetReference: String,
    /**
     * What the code will authorise, shown to the issuer and checked when it is used — e.g. for a
     * wallet withdrawal `{ "amount": 120000, "targetLabel": "Okafor & Sons (DLR-42)" }` (docs/03 §9.4).
     */
    val context: Map<String, Any?> = emptyMap(),
)

/** A challenge that was verified by the current user, with what it was issued for. */
data class VerifiedChallenge(val id: UUID, val purpose: String, val targetReference: String, val context: Map<String, Any?>)

data class OtpChallengeSummary(
    val challengeId: UUID,
    val status: String,
    val expiresAt: Instant?,
)

data class OtpIssueResponse(
    val challengeId: UUID,
    val code: String,
    val expiresAt: Instant,
)

data class OtpVerifyRequest(
    @field:NotBlank val code: String,
)

data class OtpVerifyResponse(
    val verified: Boolean,
)
