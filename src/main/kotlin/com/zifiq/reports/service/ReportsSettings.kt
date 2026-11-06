package com.zifiq.reports.service

import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import java.math.BigDecimal
import org.springframework.stereotype.Component

/** Reports & Analytics module settings (docs/13 §2.4). */
@Component
class ReportsSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(SYNC_EXPORT_ROW_LIMIT, "reports", "Rows up to which an export is produced synchronously", SettingType.INT, 5000, BigDecimal.ONE, BigDecimal(1_000_000)),
        SettingDefinition(EXPORT_RETENTION_DAYS, "reports", "Days a generated export file is kept before removal", SettingType.INT, 7, BigDecimal.ONE, BigDecimal(365)),
        SettingDefinition(SLOW_MOVING_DAYS, "reports", "Days without an outbound movement before stock is 'slow-moving'", SettingType.INT, 60, BigDecimal.ONE),
        SettingDefinition(DEAD_STOCK_DAYS, "reports", "Days without an outbound movement before stock is 'dead'", SettingType.INT, 120, BigDecimal.ONE),
        SettingDefinition(NEAR_EXPIRY_DAYS, "reports", "Days to expiry within which a batch is 'near expiry'", SettingType.INT, 30, BigDecimal.ONE),
    )

    companion object {
        const val SYNC_EXPORT_ROW_LIMIT = "reports.syncExportRowLimit"
        const val EXPORT_RETENTION_DAYS = "reports.exportRetentionDays"
        const val SLOW_MOVING_DAYS = "reports.slowMovingDays"
        const val DEAD_STOCK_DAYS = "reports.deadStockDays"
        const val NEAR_EXPIRY_DAYS = "reports.nearExpiryDays"
    }
}
