package com.zifiq.settings.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.settings.dto.CompanyProfileRequest
import com.zifiq.settings.dto.CompanyProfileView
import com.zifiq.settings.dto.CustomFieldCreateRequest
import com.zifiq.settings.dto.CustomFieldStatusRequest
import com.zifiq.settings.dto.CustomFieldUpdateRequest
import com.zifiq.settings.dto.CustomFieldView
import com.zifiq.settings.dto.OnboardingCompleteRequest
import com.zifiq.settings.dto.OnboardingStatus
import com.zifiq.settings.dto.SettingsSearchHit
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.web.multipart.MultipartFile

@Tag(name = "Settings — company", description = "Company profile, logo and onboarding (docs/06 §4.1–4.3)")
@SecurityRequirement(name = "bearerAuth")
interface CompanySettingsApi {
    @Operation(summary = "The company profile printed on every document, with which NRS issuer fields are still empty")
    fun get(): ApiEnvelope<CompanyProfileView>

    @Operation(summary = "Replace the company profile (omitted optional fields are cleared)")
    fun update(request: CompanyProfileRequest): ApiEnvelope<CompanyProfileView>

    @Operation(summary = "Upload the logo to file storage: PNG, JPEG, WebP, AVIF or SVG up to 1 MB, checked by content (multipart part 'file'); the profile's logoUrl links to it")
    fun uploadLogo(file: MultipartFile): ApiEnvelope<CompanyProfileView>

    @Operation(summary = "Remove the logo")
    fun removeLogo(): ApiEnvelope<CompanyProfileView>

    @Operation(summary = "Onboarding progress: company, branch and factory steps")
    fun onboarding(): ApiEnvelope<OnboardingStatus>

    @Operation(summary = "Finish onboarding; every unfinished step must be listed in skippedSteps (only BRANCH and FACTORY can be)")
    fun completeOnboarding(request: OnboardingCompleteRequest): ApiEnvelope<OnboardingStatus>
}

@Tag(name = "Settings — custom fields", description = "Field builder for stock items, customers and suppliers (docs/06 §4.5)")
@SecurityRequirement(name = "bearerAuth")
interface CustomFieldsApi {
    @Operation(summary = "Custom fields with how many records use each; unpaged")
    fun list(
        q: String?,
        entityType: List<String>?,
        status: List<String>?,
        dataType: List<String>?,
        required: Boolean?,
        inUse: Boolean?,
        sort: String?,
    ): ApiEnvelope<List<CustomFieldView>>

    @Operation(summary = "One custom field")
    fun get(id: UUID): ApiEnvelope<CustomFieldView>

    @Operation(summary = "Define a field; its key is derived from the label and never changes")
    fun create(request: CustomFieldCreateRequest): ApiEnvelope<CustomFieldView>

    @Operation(summary = "Rename, change options (in-use options can't be removed), required, sort order")
    fun update(id: UUID, request: CustomFieldUpdateRequest): ApiEnvelope<CustomFieldView>

    @Operation(summary = "Activate or deactivate (deactivated fields keep their values, shown read-only)")
    fun changeStatus(id: UUID, request: CustomFieldStatusRequest): ApiEnvelope<CustomFieldView>
}

@Tag(name = "Settings — search", description = "Find a settings screen, module setting or custom field (docs/06 §9)")
@SecurityRequirement(name = "bearerAuth")
interface SettingsSearchApi {
    @Operation(summary = "Matches across settings screens, module settings and custom fields the caller can see")
    fun search(q: String?): ApiEnvelope<List<SettingsSearchHit>>
}
