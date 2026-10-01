package com.zifiq.platform.security.otp.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.security.otp.dto.OtpChallengeRequest
import com.zifiq.platform.security.otp.dto.OtpChallengeSummary
import com.zifiq.platform.security.otp.dto.OtpIssueResponse
import com.zifiq.platform.security.otp.dto.OtpVerifyRequest
import com.zifiq.platform.security.otp.dto.OtpVerifyResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID

@Tag(name = "OTP Challenges", description = "Admin-reviewed one-time codes gating wallet withdrawals and quantity adjustments")
@SecurityRequirement(name = "bearerAuth")
interface OtpApi {
    @Operation(summary = "Request an OTP challenge for a gated action")
    fun request(request: OtpChallengeRequest): ApiEnvelope<OtpChallengeSummary>

    @Operation(summary = "Review and issue a one-time code for a challenge (Administrator)")
    fun issue(id: UUID): ApiEnvelope<OtpIssueResponse>

    @Operation(summary = "Verify a one-time code as the original requester")
    fun verify(id: UUID, request: OtpVerifyRequest): ApiEnvelope<OtpVerifyResponse>
}
