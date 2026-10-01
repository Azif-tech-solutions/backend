package com.zifiq.platform.security.otp.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.security.otp.OtpChallengeService
import com.zifiq.platform.security.otp.dto.OtpChallengeRequest
import com.zifiq.platform.security.otp.dto.OtpChallengeSummary
import com.zifiq.platform.security.otp.dto.OtpIssueResponse
import com.zifiq.platform.security.otp.dto.OtpVerifyRequest
import com.zifiq.platform.security.otp.dto.OtpVerifyResponse
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/otp")
class OtpController(
    private val otpChallengeService: OtpChallengeService,
) : OtpApi {
    @PostMapping("/challenges")
    override fun request(@Valid @RequestBody request: OtpChallengeRequest): ApiEnvelope<OtpChallengeSummary> =
        ApiEnvelope(otpChallengeService.request(request))

    @PostMapping("/challenges/{id}/issue")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'otp.issue')")
    override fun issue(@PathVariable id: UUID): ApiEnvelope<OtpIssueResponse> =
        ApiEnvelope(otpChallengeService.issue(id))

    @PostMapping("/challenges/{id}/verify")
    override fun verify(@PathVariable id: UUID, @Valid @RequestBody request: OtpVerifyRequest): ApiEnvelope<OtpVerifyResponse> =
        ApiEnvelope(otpChallengeService.verify(id, request.code))
}
