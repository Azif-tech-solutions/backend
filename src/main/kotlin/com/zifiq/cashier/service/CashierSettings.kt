package com.zifiq.cashier.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import org.springframework.stereotype.Component

/** Sales & POS module settings (docs/09 §2.7); the discount ceiling and pro-forma validity arrive with slice 3. */
@Component
class CashierSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = VOID_WINDOW_HOURS,
            module = "cashier",
            label = "Hours after completion in which a sale can still be voided (later: take a return and issue a credit note)",
            type = SettingType.INT,
            default = 24,
            min = java.math.BigDecimal.ZERO,
            max = java.math.BigDecimal(720),
        ),
        SettingDefinition(
            key = ABANDON_AFTER_HOURS,
            module = "cashier",
            label = "Hours an untouched open sale stays on the till before it is cleared automatically",
            type = SettingType.INT,
            default = 12,
            min = java.math.BigDecimal.ONE,
            max = java.math.BigDecimal(168),
        ),
        SettingDefinition(
            key = INSTANT_DISCOUNT_CEILING_PCT,
            module = "cashier",
            label = "Discount percentage a cashier/Admin can apply without a second authorisation; above it needs Management approval",
            type = SettingType.DECIMAL,
            default = java.math.BigDecimal("10"),
            min = java.math.BigDecimal.ZERO,
            max = java.math.BigDecimal(100),
        ),
        SettingDefinition(
            key = PRO_FORMA_VALIDITY_DAYS,
            module = "cashier",
            label = "Default number of days a pro-forma invoice stays valid",
            type = SettingType.INT,
            default = 7,
            min = java.math.BigDecimal.ONE,
            max = java.math.BigDecimal(365),
        ),
    )

    companion object {
        const val VOID_WINDOW_HOURS = "cashier.sale.voidWindowHours"
        const val ABANDON_AFTER_HOURS = "cashier.sale.abandonAfterHours"
        const val INSTANT_DISCOUNT_CEILING_PCT = "cashier.instantDiscount.ceilingPct"
        const val PRO_FORMA_VALIDITY_DAYS = "cashier.proForma.defaultValidityDays"
    }
}
