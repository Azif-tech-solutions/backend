package com.zifiq.settings.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.settings.dto.CompanySettingsRequest
import com.zifiq.settings.dto.CompanySettingsSummary
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Settings", description = "Company Profile")
@SecurityRequirement(name = "bearerAuth")
interface SettingsApi {
    @Operation(summary = "Get active company configuration profile")
    fun getCompanySettings(): ApiEnvelope<CompanySettingsSummary>

    @Operation(summary = "Update company profile configuration")
    fun updateCompanySettings(request: CompanySettingsRequest): ApiEnvelope<CompanySettingsSummary>
}
