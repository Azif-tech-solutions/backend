package com.zifiq.settings.service

import com.fasterxml.jackson.databind.JsonNode
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.SettingValue
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.settings.dto.ModuleSettingView
import com.zifiq.settings.dto.ModuleSettingsGroup
import java.util.UUID
import org.springframework.stereotype.Service

/** The Settings screen over platform-owned module settings (storage and typed reader live in platform). */
@Service
class ModuleSettingsService(
    private val moduleSettings: ModuleSettings,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val currentActorProvider: CurrentActorProvider,
    private val domainEvents: DomainEvents,
    private val permissionAuthorizer: PermissionAuthorizer,
) {
    /**
     * Holders of `settings.module-settings.manage` see (and may change) every setting. Anyone else
     * sees, read-only, the settings of modules where they hold a `<module>.….manage` permission
     * (docs/06 §4.4). [modified]: only settings changed from their default.
     */
    fun list(q: String? = null, modules: Set<String> = emptySet(), types: Set<String> = emptySet(), modified: Boolean? = null): List<ModuleSettingsGroup> =
        tenantScopedTransaction.execute {
            val visibility = visibility()
            val words = SettingsText.words(q)
            moduleSettings.values()
                .filter { visibility.canSee(it.definition.module) }
                .filter { modules.isEmpty() || modules.any { m -> m.equals(it.definition.module, ignoreCase = true) } }
                .filter { types.isEmpty() || types.any { t -> t.equals(it.definition.type.name, ignoreCase = true) } }
                .filter { modified == null || it.isDefault != modified }
                .filter { SettingsText.matches(words, it.definition.key, it.definition.label, it.definition.module) }
                .groupBy { it.definition.module }
                .map { (module, values) -> ModuleSettingsGroup(module, visibility.canEdit, values.sortedBy { it.definition.label }.map(::view)) }
                .sortedBy { it.module }
        }

    /** Which modules' settings the current user may see. */
    fun visibility(): SettingsVisibility {
        val permissions = permissionAuthorizer.permissions(currentActorProvider.current().userId)
        return SettingsVisibility(permissions)
    }

    fun update(key: String, value: JsonNode): ModuleSettingView = tenantScopedTransaction.execute {
        val before = moduleSettings.values(listOf(key)).single()
        val after = moduleSettings.set(key, value, currentActorProvider.current().userId)
        record(key, before.value, after.value)
        view(after)
    }

    fun reset(key: String): ModuleSettingView = tenantScopedTransaction.execute {
        val before = moduleSettings.values(listOf(key)).single()
        moduleSettings.reset(key)
        val after = moduleSettings.values(listOf(key)).single()
        if (!before.isDefault) record(key, before.value, after.value)
        view(after)
    }

    // Settings hold thresholds and lists, never secrets, so old and new values can go in the payload.
    private fun record(key: String, from: Any, to: Any) {
        domainEvents.record(
            type = "settings.module-setting.changed",
            aggregateType = "module_setting",
            aggregateId = UUID.nameUUIDFromBytes(key.toByteArray()),
            aggregateRef = key,
            summary = "Setting $key changed",
            payload = mapOf("key" to key, "from" to from, "to" to to),
        )
    }

    private fun view(value: SettingValue) = ModuleSettingView(
        key = value.definition.key,
        label = value.definition.label,
        type = value.definition.type.name,
        value = value.value,
        default = value.definition.default,
        isDefault = value.isDefault,
        min = value.definition.min,
        max = value.definition.max,
        options = value.definition.options,
        updatedByUserId = value.updatedByUserId,
        updatedAt = value.updatedAt,
    )
}

class SettingsVisibility(private val permissions: Set<String>) {
    val canEdit: Boolean = MANAGE in permissions

    fun has(permission: String): Boolean = permission in permissions

    fun canSee(module: String): Boolean =
        canEdit || permissions.any { it.startsWith("$module.") && it.endsWith(".manage") }

    companion object {
        const val MANAGE = "settings.module-settings.manage"
    }
}
