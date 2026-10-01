package com.zifiq.platform.identity.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.identity.AuthService
import com.zifiq.platform.identity.dto.*
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) : AuthApi {
    @PostMapping("/register-tenant")
    override fun registerTenant(
        @Valid @RequestBody request: TenantRegistrationRequest,
    ): ApiEnvelope<TenantRegistrationResponse> =
        ApiEnvelope(authService.registerTenant(request))

    @PostMapping("/login")
    override fun login(
        @Valid @RequestBody request: UserLoginRequest,
    ): ApiEnvelope<TokenResponse> =
        ApiEnvelope(authService.login(request))

    @PostMapping("/refresh")
    override fun refreshToken(
        @Valid @RequestBody request: RefreshTokenRequest,
    ): ApiEnvelope<TokenResponse> =
        ApiEnvelope(authService.refreshToken(request))

    @PostMapping("/switch-tenant")
    override fun switchTenant(
        @Valid @RequestBody request: TenantSwitchRequest,
    ): ApiEnvelope<TokenResponse> =
        ApiEnvelope(authService.switchTenant(request))

    @PostMapping("/change-password")
    override fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ApiEnvelope<Map<String, String>> {
        authService.changePassword(request)
        return ApiEnvelope(mapOf("message" to "Password has been successfully updated", "status" to "SUCCESS"))
    }

    @PostMapping("/forgot-password")
    override fun forgotPassword(
        @Valid @RequestBody request: ForgotPasswordRequest,
    ): ApiEnvelope<PasswordResetResponse> =
        ApiEnvelope(authService.forgotPassword(request))

    @PostMapping("/reset-password")
    override fun resetPassword(
        @Valid @RequestBody request: ResetPasswordRequest,
    ): ApiEnvelope<PasswordResetResponse> =
        ApiEnvelope(authService.resetPassword(request))
}
