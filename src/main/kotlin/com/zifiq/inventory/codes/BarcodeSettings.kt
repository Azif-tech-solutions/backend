package com.zifiq.inventory.codes

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import java.math.BigDecimal
import org.springframework.stereotype.Component

/**
 * Barcode & QR module settings (docs/11 §2.4). Registered here, in OPEN inventory, because the code
 * generator that reads them lives here (registration must not depend on the leaf barcode module); the
 * barcode module references the same key constants. An empty GS1 company prefix means codes are
 * generated in GS1's restricted-circulation range (20–29), valid on any scanner and collision-free.
 */
@Component
class BarcodeSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = GS1_COMPANY_PREFIX, module = "barcode",
            label = "GS1 company prefix (7–10 digits); blank generates restricted-circulation codes",
            type = SettingType.STRING, default = "",
        ),
        SettingDefinition(
            key = RESTRICTED_PREFIX, module = "barcode",
            label = "Restricted-circulation EAN prefix (GS1 reserves 20–29 for in-store use)",
            type = SettingType.ENUM, default = "20",
            options = (20..29).map { it.toString() },
        ),
        SettingDefinition(
            key = DEFAULT_GOODS_SYMBOLOGY, module = "barcode",
            label = "Default symbology generated for goods",
            type = SettingType.ENUM, default = "EAN_13", options = listOf("EAN_13", "CODE_128"),
        ),
        SettingDefinition(
            key = RECIPE_SYMBOLOGY, module = "barcode",
            label = "Default symbology generated for in-house recipes",
            type = SettingType.ENUM, default = "QR", options = listOf("QR", "CODE_128"),
        ),
        SettingDefinition(
            key = AUTO_GENERATE_ON_REGISTER, module = "barcode",
            label = "Generate a code automatically when a product is registered without one",
            type = SettingType.BOOLEAN, default = true,
        ),
        SettingDefinition(
            key = DEFAULT_LABEL_SIZE, module = "barcode",
            label = "Default label size",
            type = SettingType.ENUM, default = "58x40", options = listOf("40x25", "58x40", "100x50", "A4_24"),
        ),
        SettingDefinition(
            key = SCAN_LOG_RETENTION_DAYS, module = "barcode",
            label = "Days a scan-log row is kept before the nightly cleanup removes it",
            type = SettingType.INT, default = 180, min = BigDecimal.ONE, max = BigDecimal(3650),
        ),
    )

    companion object {
        const val GS1_COMPANY_PREFIX = "barcode.gs1CompanyPrefix"
        const val RESTRICTED_PREFIX = "barcode.restrictedPrefix"
        const val DEFAULT_GOODS_SYMBOLOGY = "barcode.defaultGoodsSymbology"
        const val RECIPE_SYMBOLOGY = "barcode.recipeSymbology"
        const val AUTO_GENERATE_ON_REGISTER = "barcode.autoGenerateOnRegister"
        const val DEFAULT_LABEL_SIZE = "barcode.defaultLabelSize"
        const val SCAN_LOG_RETENTION_DAYS = "barcode.scanLogRetentionDays"
    }
}
