package com.zifiq.account.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import org.springframework.stereotype.Component

data class Currency(val code: String, val name: String, val symbol: String)

/** ISO 4217 currencies the tenant can choose to track; names and symbols for the Exchange Rate screen. */
object Currencies {
    val ALL: List<Currency> = listOf(
        Currency("USD", "US Dollar", "$"), Currency("GBP", "British Pound", "£"), Currency("EUR", "Euro", "€"),
        Currency("CNY", "Chinese Yuan", "¥"), Currency("ZAR", "South African Rand", "R"), Currency("GHS", "Ghanaian Cedi", "₵"),
        Currency("XOF", "West African CFA Franc", "CFA"), Currency("XAF", "Central African CFA Franc", "FCFA"),
        Currency("KES", "Kenyan Shilling", "KSh"), Currency("EGP", "Egyptian Pound", "E£"), Currency("MAD", "Moroccan Dirham", "DH"),
        Currency("AED", "UAE Dirham", "د.إ"), Currency("SAR", "Saudi Riyal", "﷼"), Currency("INR", "Indian Rupee", "₹"),
        Currency("JPY", "Japanese Yen", "¥"), Currency("CAD", "Canadian Dollar", "C$"), Currency("AUD", "Australian Dollar", "A$"),
        Currency("CHF", "Swiss Franc", "CHF"), Currency("TRY", "Turkish Lira", "₺"), Currency("BRL", "Brazilian Real", "R$"),
    )
    private val byCode = ALL.associateBy { it.code }

    fun get(code: String): Currency? = byCode[code.uppercase()]
}

/** Finance & Accounting module settings (docs/07 §2.13); more keys arrive with slices 2 and 3. */
@Component
class AccountingSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = TRACKED_CURRENCIES,
            module = "accounting",
            label = "Currencies tracked on the Exchange Rate screen",
            type = SettingType.STRING_LIST,
            default = listOf("USD", "GBP", "EUR", "CNY", "ZAR", "GHS"),
            options = Currencies.ALL.map { it.code },
            maxItems = Currencies.ALL.size,
        ),
        SettingDefinition(
            key = ADMIN_SELF_APPROVAL,
            module = "accounting",
            label = "Administrators may approve running costs they submitted themselves (for one-person companies)",
            type = SettingType.BOOLEAN,
            default = true,
        ),
    )

    companion object {
        const val TRACKED_CURRENCIES = "accounting.fx.currencies"
        const val ADMIN_SELF_APPROVAL = "accounting.runningCost.allowSelfApprovalForAdmin"
    }
}
