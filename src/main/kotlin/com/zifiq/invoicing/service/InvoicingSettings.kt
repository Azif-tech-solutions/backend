package com.zifiq.invoicing.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import java.math.BigDecimal
import org.springframework.stereotype.Component

/** Electronic Invoicing module settings (docs/08 §2.8). */
@Component
class InvoicingSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = DEFAULT_PAYMENT_TERMS_DAYS,
            module = MODULE,
            label = "Payment terms (days) when the sale doesn't set them",
            type = SettingType.INT,
            default = 30,
            min = BigDecimal.ZERO,
            max = BigDecimal(365),
        ),
        SettingDefinition(
            key = PAID_TOLERANCE,
            module = MODULE,
            label = "An invoice counts as paid when its balance is at most this (absorbs VAT rounding)",
            type = SettingType.DECIMAL,
            default = BigDecimal("1.00"),
            min = BigDecimal.ZERO,
            max = BigDecimal(1000),
        ),
        SettingDefinition(
            key = AUTO_EMAIL_ON_ISSUE,
            module = MODULE,
            label = "E-mail the invoice to the buyer when it is issued",
            type = SettingType.BOOLEAN,
            default = true,
        ),
        SettingDefinition(
            key = OVERDUE_REMINDER_DAYS,
            module = MODULE,
            label = "Days past due on which a reminder is e-mailed (empty = never)",
            type = SettingType.STRING_LIST,
            default = listOf("1", "7", "30"),
            maxItems = 10,
        ),
        SettingDefinition(
            key = CREDIT_NOTE_EXCESS_TO,
            module = MODULE,
            label = "Where a credit note's excess over the balance goes",
            type = SettingType.ENUM,
            default = "WALLET",
            options = listOf("WALLET", "BLOCK"),
        ),
        SettingDefinition(
            key = BULK_EXPORT_MAX_INVOICES,
            module = MODULE,
            label = "Most invoices one bulk PDF export may contain",
            type = SettingType.INT,
            default = 200,
            min = BigDecimal.ONE,
            max = BigDecimal(2000),
        ),
    )

    companion object {
        const val MODULE = "invoicing"
        const val DEFAULT_PAYMENT_TERMS_DAYS = "invoicing.defaultPaymentTermsDays"
        const val PAID_TOLERANCE = "invoicing.paidTolerance"
        const val AUTO_EMAIL_ON_ISSUE = "invoicing.autoEmailOnIssue"
        const val OVERDUE_REMINDER_DAYS = "invoicing.overdueReminderDays"
        const val CREDIT_NOTE_EXCESS_TO = "invoicing.creditNoteExcessTo"
        const val BULK_EXPORT_MAX_INVOICES = "invoicing.bulkExportMaxInvoices"
    }
}
