package com.zifiq.settings.service

import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.settings.dto.CompanySettingsRequest
import com.zifiq.settings.dto.CompanySettingsSummary
import java.time.Instant
import org.springframework.stereotype.Service

/**
 * Settings > Company (sketch/index.html). Factory Setup (Factories, Suppliers, Manufacturers,
 * Units of Measure, Categories) moved to `com.zifiq.factory.service.FactorySetupService` — see
 * docs/02-factory.md. This module now owns only company-wide profile settings.
 */
@Service
class SettingsService(
    private val currentActorProvider: CurrentActorProvider,
) {
    fun getCompanySettings(): CompanySettingsSummary {
        val actor = currentActorProvider.current()
        return CompanySettingsSummary(
            tenantId = actor.tenantId,
            companyName = "ZifIQ Operations Ltd",
            primaryEmail = actor.email ?: "info@zifiq.com",
            primaryPhone = "+1-800-555-ZIF",
            taxRegistrationNumber = "TAX-9948120",
            headOfficeAddress = "Suite 400, Innovation Tower, Central District",
            updatedAt = Instant.now(),
        )
    }

    fun updateCompanySettings(request: CompanySettingsRequest): CompanySettingsSummary {
        val actor = currentActorProvider.current()
        return CompanySettingsSummary(
            tenantId = actor.tenantId,
            companyName = request.companyName,
            primaryEmail = request.primaryEmail,
            primaryPhone = request.primaryPhone,
            taxRegistrationNumber = request.taxRegistrationNumber,
            headOfficeAddress = request.headOfficeAddress,
            updatedAt = Instant.now(),
        )
    }
}
