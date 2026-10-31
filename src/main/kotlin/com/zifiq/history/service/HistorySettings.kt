package com.zifiq.history.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import java.math.BigDecimal
import org.springframework.stereotype.Component

/** Audit & Compliance module settings (docs/10 §2.8). The recycle-bin and data-quality settings arrive with slice 3. */
@Component
class HistorySettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(
            key = TILL_VARIANCE_THRESHOLD,
            module = "history",
            label = "Till cash variance (₦) at or above which a TILL_VARIANCE audit alert is raised",
            type = SettingType.DECIMAL,
            default = BigDecimal("5000.00"),
            min = BigDecimal.ZERO,
        ),
        SettingDefinition(
            key = REPEATED_DECLINES_PER_WEEK,
            module = "history",
            label = "Declined receipts for one cashier in 7 days that raise a REPEATED_DECLINES alert",
            type = SettingType.INT,
            default = 3,
            min = BigDecimal.ONE,
        ),
        SettingDefinition(
            key = ADJUSTMENT_VALUE_THRESHOLD,
            module = "history",
            label = "Negative stock-adjustment value (₦, cost basis) at or above which a LARGE_ADJUSTMENT alert is raised",
            type = SettingType.DECIMAL,
            default = BigDecimal("100000.00"),
            min = BigDecimal.ZERO,
        ),
        SettingDefinition(
            key = RECYCLE_BIN_RETENTION_DAYS,
            module = "history",
            label = "Days a deleted product stays in the Recycle Bin before it is purge-eligible",
            type = SettingType.INT,
            default = 30,
            min = BigDecimal.ONE,
            max = BigDecimal(3650),
        ),
        SettingDefinition(
            key = DATA_QUALITY_SCAN_ENABLED,
            module = "history",
            label = "Run the nightly product data-quality (spelling / naming) scan",
            type = SettingType.BOOLEAN,
            default = true,
        ),
    )

    companion object {
        const val TILL_VARIANCE_THRESHOLD = "history.alerts.tillVarianceThreshold"
        const val REPEATED_DECLINES_PER_WEEK = "history.alerts.repeatedDeclinesPerWeek"
        const val ADJUSTMENT_VALUE_THRESHOLD = "history.alerts.adjustmentValueThreshold"
        const val RECYCLE_BIN_RETENTION_DAYS = "history.recycleBin.retentionDays"
        const val DATA_QUALITY_SCAN_ENABLED = "history.dataQuality.scanEnabled"
    }
}
