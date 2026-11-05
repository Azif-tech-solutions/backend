package com.zifiq.barcode.service

import com.zifiq.barcode.model.ScanEventsTable
import com.zifiq.inventory.codes.BarcodeSettings
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Hard-deletes scan-log rows past their retention (docs/11 §2.2) — the one log exempt from the
 * append-only rule, because scans are lookups, not business records. Runs nightly, per tenant, at
 * each tenant's `barcode.scanLogRetentionDays` setting. Idempotent.
 */
@Component
class ScanLogRetentionSweep(
    private val database: Database,
    private val tx: TenantScopedTransaction,
    private val moduleSettings: ModuleSettings,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${zifiq.barcode.scan-log-retention-cron:0 30 1 * * *}")
    fun run() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            runCatching {
                tx.executeForTenant(tenantId) {
                    val cutoff = Instant.now().minus(moduleSettings.int(BarcodeSettings.SCAN_LOG_RETENTION_DAYS).toLong(), ChronoUnit.DAYS)
                    ScanEventsTable.deleteWhere { ScanEventsTable.scannedAt less cutoff }
                }
            }.onFailure { logger.warn("Scan-log retention sweep failed for tenant {}: {}", tenantId, it.message) }
        }
    }
}
