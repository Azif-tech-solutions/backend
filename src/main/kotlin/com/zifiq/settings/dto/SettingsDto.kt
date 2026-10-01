package com.zifiq.settings.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CompanySettingsRequest(
    @field:NotBlank val companyName: String,
    @field:NotBlank @field:Email val primaryEmail: String,
    @field:NotBlank val primaryPhone: String,
    val taxRegistrationNumber: String?,
    val headOfficeAddress: String?,
)

data class CompanySettingsSummary(
    val tenantId: UUID,
    val companyName: String,
    val primaryEmail: String,
    val primaryPhone: String,
    val taxRegistrationNumber: String?,
    val headOfficeAddress: String?,
    val updatedAt: Instant,
)
