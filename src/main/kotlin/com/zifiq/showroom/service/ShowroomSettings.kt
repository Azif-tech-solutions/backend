package com.zifiq.showroom.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import org.springframework.stereotype.Component

/**
 * Showroom's tenant-configurable settings (docs/05 §2.10). The design's object list of areas is a
 * list of area kinds here (module settings hold scalars and string lists); each kind gets its
 * standard name and code suffix.
 */
@Component
class ShowroomSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = DEFAULT_AREAS,
            module = "showroom",
            label = "Storage areas created for every new showroom (SHOP_FLOOR, WAREHOUSE, RECEIVING, QUARANTINE, OTHER)",
            type = SettingType.STRING_LIST,
            default = listOf("SHOP_FLOOR", "WAREHOUSE"),
            options = AREA_KINDS,
            maxItems = 5,
        ),
    )

    companion object {
        const val DEFAULT_AREAS = "showroom.defaultAreas"
        val AREA_KINDS = listOf("SHOP_FLOOR", "WAREHOUSE", "RECEIVING", "QUARANTINE", "OTHER")
    }
}
