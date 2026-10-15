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
import com.zifiq.settings.service.CompanyProfileService
import com.zifiq.settings.service.CustomFieldService
import com.zifiq.settings.service.OnboardingService
import com.zifiq.settings.service.SettingsSearchService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

private const val MANAGE = "@permissionAuthorizer.hasPermission(authentication, 'settings.manage')"
private const val MANAGE_FIELDS = "@permissionAuthorizer.hasPermission(authentication, 'settings.custom-fields.manage')"

private fun List<String>?.flattened() = orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** Reading the profile, logo and onboarding needs only tenant membership: every screen shows the company name. */
@RestController
@RequestMapping("/api/v1/settings")
class CompanySettingsController(
    private val profiles: CompanyProfileService,
    private val onboarding: OnboardingService,
) : CompanySettingsApi {
    @GetMapping("/company")
    override fun get(): ApiEnvelope<CompanyProfileView> = ApiEnvelope(profiles.view())

    @PutMapping("/company")
    @PreAuthorize(MANAGE)
    override fun update(@Valid @RequestBody request: CompanyProfileRequest): ApiEnvelope<CompanyProfileView> = ApiEnvelope(profiles.update(request))

    @PutMapping("/company/logo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize(MANAGE)
    override fun uploadLogo(@RequestParam("file") file: MultipartFile): ApiEnvelope<CompanyProfileView> =
        ApiEnvelope(profiles.setLogo(file.bytes, file.originalFilename ?: "logo"))

    @DeleteMapping("/company/logo")
    @PreAuthorize(MANAGE)
    override fun removeLogo(): ApiEnvelope<CompanyProfileView> = ApiEnvelope(profiles.removeLogo())

    @GetMapping("/onboarding")
    override fun onboarding(): ApiEnvelope<OnboardingStatus> = ApiEnvelope(onboarding.status())

    @PostMapping("/onboarding/complete")
    @PreAuthorize(MANAGE)
    override fun completeOnboarding(@RequestBody request: OnboardingCompleteRequest): ApiEnvelope<OnboardingStatus> =
        ApiEnvelope(onboarding.complete(request))
}

/** Reading definitions needs only membership (every form that shows custom fields needs them). */
@RestController
@RequestMapping("/api/v1/settings/custom-fields")
class CustomFieldsController(private val fields: CustomFieldService) : CustomFieldsApi {
    @GetMapping
    override fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) entityType: List<String>?,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(required = false) dataType: List<String>?,
        @RequestParam(required = false) required: Boolean?,
        @RequestParam(required = false) inUse: Boolean?,
        @RequestParam(required = false) sort: String?,
    ): ApiEnvelope<List<CustomFieldView>> =
        ApiEnvelope(fields.list(q, entityType.flattened(), status.flattened(), dataType.flattened(), required, inUse, sort))

    @GetMapping("/{id}")
    override fun get(@PathVariable id: UUID): ApiEnvelope<CustomFieldView> = ApiEnvelope(fields.get(id))

    @PostMapping
    @PreAuthorize(MANAGE_FIELDS)
    override fun create(@Valid @RequestBody request: CustomFieldCreateRequest): ApiEnvelope<CustomFieldView> = ApiEnvelope(fields.create(request))

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE_FIELDS)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: CustomFieldUpdateRequest): ApiEnvelope<CustomFieldView> =
        ApiEnvelope(fields.update(id, request))

    @PutMapping("/{id}/status")
    @PreAuthorize(MANAGE_FIELDS)
    override fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: CustomFieldStatusRequest): ApiEnvelope<CustomFieldView> =
        ApiEnvelope(fields.changeStatus(id, request.status!!))
}

@RestController
@RequestMapping("/api/v1/settings/search")
class SettingsSearchController(private val searchService: SettingsSearchService) : SettingsSearchApi {
    @GetMapping
    override fun search(@RequestParam(required = false) q: String?): ApiEnvelope<List<SettingsSearchHit>> = ApiEnvelope(searchService.search(q))
}
