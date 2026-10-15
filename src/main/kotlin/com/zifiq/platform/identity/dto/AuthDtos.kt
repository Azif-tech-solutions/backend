package com.zifiq.platform.identity.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class TenantRegistrationRequest(
    @field:NotBlank @field:Size(min = 2, max = 160) val companyName: String,
    @field:NotBlank @field:Email val adminEmail: String,
    @field:NotBlank @field:Size(min = 2, max = 160) val adminDisplayName: String,
    @field:NotBlank @field:Size(min = 6, max = 100) val password: String,
)

data class TenantRegistrationResponse(
    val tenantId: UUID,
    val userId: UUID,
    val companyName: String,
    val adminEmail: String,
    val tokenResponse: TokenResponse,
)

data class UserLoginRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val password: String,
    val tenantId: UUID? = null,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long,
    val activeTenantId: UUID,
    val userId: UUID,
    /** The factory or showroom the session works in (POST /auth/switch-location), or null. */
    val activeLocationId: UUID? = null,
)

data class RefreshTokenRequest(
    @field:NotBlank val refreshToken: String,
)

data class TenantSwitchRequest(
    val targetTenantId: UUID,
)

/** null = leave any location (the whole company); only for users not assigned to one location. */
data class LocationSwitchRequest(
    val locationId: UUID?,
)

/** A factory or showroom the user can switch into. */
data class SwitchableLocation(
    val id: UUID,
    val code: String,
    val name: String,
    val type: String,
    val active: Boolean,
)

data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:Size(min = 6, max = 100) val newPassword: String,
)

data class ForgotPasswordRequest(
    @field:NotBlank @field:Email val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank val resetToken: String,
    @field:NotBlank @field:Size(min = 6, max = 100) val newPassword: String,
)

data class PasswordResetResponse(
    val message: String,
    val status: String,
)
