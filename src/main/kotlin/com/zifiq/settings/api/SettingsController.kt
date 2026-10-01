package com.zifiq.settings.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.settings.dto.CompanySettingsRequest
import com.zifiq.settings.dto.CompanySettingsSummary
import com.zifiq.settings.service.SettingsService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/settings")
class SettingsController(
    private val settingsService: SettingsService,
) : SettingsApi {
    @GetMapping("/company")
    override fun getCompanySettings(): ApiEnvelope<CompanySettingsSummary> =
        ApiEnvelope(settingsService.getCompanySettings())

    @PutMapping("/company")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'settings.manage')")
    override fun updateCompanySettings(
        @Valid @RequestBody request: CompanySettingsRequest,
    ): ApiEnvelope<CompanySettingsSummary> =
        ApiEnvelope(settingsService.updateCompanySettings(request))
}
