package com.zifiq.inventory.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import java.math.BigDecimal
import org.springframework.stereotype.Component

/** Inventory's tenant-configurable settings (docs/03 §10). */
@Component
class InventorySettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = ADJUSTMENT_OTP_THRESHOLD_QTY,
            module = "inventory",
            label = "Quantity adjustments above this quantity need an Admin-issued OTP",
            type = SettingType.DECIMAL,
            default = BigDecimal("50"),
            min = BigDecimal.ONE,
            max = BigDecimal("1000000"),
        ),
        SettingDefinition(
            key = RECEIVE_REQUIRE_FULL_RECONCILIATION,
            module = "inventory",
            label = "On receipt, accepted plus rejected must equal what is outstanding on every line confirmed",
            type = SettingType.BOOLEAN,
            default = true,
        ),
    )

    companion object {
        const val ADJUSTMENT_OTP_THRESHOLD_QTY = "inventory.adjustment.otpThresholdQty"
        const val RECEIVE_REQUIRE_FULL_RECONCILIATION = "inventory.receive.requireFullReconciliation"
    }
}
