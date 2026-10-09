package com.zifiq.settings.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.settings.dto.ModuleSettingUpdateRequest
import com.zifiq.settings.dto.ModuleSettingView
import com.zifiq.settings.dto.ModuleSettingsGroup
import com.zifiq.settings.service.ModuleSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Settings — module settings", description = "Tenant-configurable thresholds and lists (docs/03 §10, docs/06 §4.4)")
interface ModuleSettingsApi {
    @Operation(summary = "Module settings the caller may see, grouped by module (q matches key, label and module; modified = changed from default)")
    fun list(q: String?, module: List<String>?, type: List<String>?, modified: Boolean?): ApiEnvelope<List<ModuleSettingsGroup>>

    @Operation(summary = "Change a setting (validated against its type and bounds)")
    fun update(key: String, request: ModuleSettingUpdateRequest): ApiEnvelope<ModuleSettingView>

    @Operation(summary = "Reset a setting to its default")
    fun reset(key: String): ApiEnvelope<ModuleSettingView>
}

@RestController
@RequestMapping("/api/v1/settings/module-settings")
class ModuleSettingsController(
    private val moduleSettingsService: ModuleSettingsService,
) : ModuleSettingsApi {
    @GetMapping
    override fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) module: List<String>?,
        @RequestParam(required = false) type: List<String>?,
        @RequestParam(required = false) modified: Boolean?,
    ): ApiEnvelope<List<ModuleSettingsGroup>> =
        ApiEnvelope(moduleSettingsService.list(q, module.flatten(), type.flatten(), modified))

    private fun List<String>?.flatten() = orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    @PutMapping("/{key}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'settings.module-settings.manage')")
    override fun update(@PathVariable key: String, @jakarta.validation.Valid @RequestBody request: ModuleSettingUpdateRequest): ApiEnvelope<ModuleSettingView> =
        ApiEnvelope(moduleSettingsService.update(key, request.value!!))

    @DeleteMapping("/{key}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'settings.module-settings.manage')")
    override fun reset(@PathVariable key: String): ApiEnvelope<ModuleSettingView> = ApiEnvelope(moduleSettingsService.reset(key))
}
