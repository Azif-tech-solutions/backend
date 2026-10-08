package com.zifiq.settings.service

import com.fasterxml.jackson.databind.JsonNode
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
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
) {
    fun list(): List<ModuleSettingsGroup> = tenantScopedTransaction.execute {
        moduleSettings.values()
            .groupBy { it.definition.module }
            .map { (module, values) -> ModuleSettingsGroup(module, values.map(::view)) }
            .sortedBy { it.module }
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
