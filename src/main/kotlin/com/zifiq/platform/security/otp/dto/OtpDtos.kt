package com.zifiq.platform.security.otp.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

data class OtpChallengeRequest(
    @field:Pattern(regexp = "WALLET_WITHDRAWAL|QUANTITY_ADJUSTMENT")
    val purpose: String,
    @field:NotBlank val targetReference: String,
)

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
