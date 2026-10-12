package com.zifiq.customers.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import org.springframework.stereotype.Component

/** Customers' tenant-configurable settings (docs/04 §2.6). */
@Component
class CustomersSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = CORRESPONDENT_REGIONS,
            module = "customers",
            label = "Regions correspondents can be assigned to",
            type = SettingType.STRING_LIST,
            default = listOf("Lagos Mainland", "Lagos Island", "North Central", "North West", "South East", "South South", "South West"),
            maxItems = 60,
        ),
        SettingDefinition(
            key = TEMPORARY_MATCH_BY_PHONE,
            module = "customers",
            label = "Match walk-in returns to an existing customer by phone number",
            type = SettingType.BOOLEAN,
            default = true,
        ),
        SettingDefinition(
            key = WITHDRAWAL_OTP_REQUIRED,
            module = "customers",
            label = "Dealer-requested wallet withdrawals need an Admin-issued OTP",
            type = SettingType.BOOLEAN,
            default = true,
        ),
    )

    companion object {
        const val CORRESPONDENT_REGIONS = "customers.correspondent.regions"
        const val TEMPORARY_MATCH_BY_PHONE = "customers.temporary.matchByPhone"
        const val WITHDRAWAL_OTP_REQUIRED = "customers.wallet.withdrawalOtpRequired"
    }
}
