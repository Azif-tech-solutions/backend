package com.zifiq.communication.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import java.math.BigDecimal
import org.springframework.stereotype.Component

/** Communication (Commissions) module settings (docs/12 §2.4). */
@Component
class CommunicationSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = REQUIRE_INVOICE_PAID, module = "communication",
            label = "Hold on-account (credit) sales out of commission until their invoice is fully paid",
            type = SettingType.BOOLEAN, default = false,
        ),
        SettingDefinition(
            key = ALLOW_LOCATION_RUNS, module = "communication",
            label = "Allow showroom-only allocation runs (off = company-wide runs only, one per period)",
            type = SettingType.BOOLEAN, default = false,
        ),
        SettingDefinition(
            key = MINIMUM_PAYOUT, module = "communication",
            label = "Minimum commission payout (₦); smaller balances accumulate",
            type = SettingType.DECIMAL, default = BigDecimal("0.00"), min = BigDecimal.ZERO,
        ),
    )

    companion object {
        const val REQUIRE_INVOICE_PAID = "communication.requireInvoicePaid"
        const val ALLOW_LOCATION_RUNS = "communication.allowLocationRuns"
        const val MINIMUM_PAYOUT = "communication.minimumPayout"
    }
}
