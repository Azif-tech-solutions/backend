package com.zifiq.platform.identity.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.identity.dto.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Authentication & Onboarding", description = "Tenant Registration, User Login, Token Refresh, Tenant Switching & Password Management")
interface AuthApi {
    @Operation(summary = "Register a new root tenant and provision initial administrator user")
    fun registerTenant(request: TenantRegistrationRequest): ApiEnvelope<TenantRegistrationResponse>

    @Operation(summary = "Authenticate user credentials and issue scoped access token")
    fun login(request: UserLoginRequest): ApiEnvelope<TokenResponse>

    @Operation(summary = "Refresh active access token using valid refresh token")
    fun refreshToken(request: RefreshTokenRequest): ApiEnvelope<TokenResponse>

    @Operation(summary = "Switch active tenant session and issue new tenant-scoped access token")
    @SecurityRequirement(name = "bearerAuth")
    fun switchTenant(request: TenantSwitchRequest): ApiEnvelope<TokenResponse>

    @Operation(summary = "Work inside one factory or showroom: new tokens whose every list, report, read and write is confined to it (null = whole company, only for users not assigned to a location)")
    @SecurityRequirement(name = "bearerAuth")
    fun switchLocation(request: LocationSwitchRequest): ApiEnvelope<TokenResponse>

    @Operation(summary = "The factories and showrooms this user can switch into")
    @SecurityRequirement(name = "bearerAuth")
    fun switchableLocations(): ApiEnvelope<List<SwitchableLocation>>

    @Operation(summary = "Change password for currently authenticated user")
    @SecurityRequirement(name = "bearerAuth")
    fun changePassword(request: ChangePasswordRequest): ApiEnvelope<Map<String, String>>

    @Operation(summary = "Request password reset verification code via email")
    fun forgotPassword(request: ForgotPasswordRequest): ApiEnvelope<PasswordResetResponse>

    @Operation(summary = "Reset password using verification token")
    fun resetPassword(request: ResetPasswordRequest): ApiEnvelope<PasswordResetResponse>
}
