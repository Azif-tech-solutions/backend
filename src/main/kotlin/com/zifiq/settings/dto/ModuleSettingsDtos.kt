package com.zifiq.settings.dto

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ModuleSettingView(
    val key: String,
    val label: String,
    val type: String,
    val value: Any,
    val default: Any,
    val isDefault: Boolean,
    val min: BigDecimal?,
    val max: BigDecimal?,
    val options: List<String>,
    val updatedByUserId: UUID?,
    val updatedAt: Instant?,
)

data class ModuleSettingsGroup(val module: String, val settings: List<ModuleSettingView>)

data class ModuleSettingUpdateRequest(@field:NotNull val value: JsonNode?)
