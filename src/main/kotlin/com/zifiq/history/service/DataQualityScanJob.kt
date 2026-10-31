package com.zifiq.history.service

import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nightly product data-quality scan (docs/10 §4.8), at 02:00. Runs per tenant; idempotent (an
 * entity/field with an open flag is skipped, so re-running raises nothing new).
 */
@Component
class DataQualityScanJob(
    private val database: Database,
    private val tx: TenantScopedTransaction,
    private val dataQuality: DataQualityService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${zifiq.history.data-quality-scan-cron:0 0 2 * * *}")
    fun run() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            runCatching { tx.executeForTenant(tenantId) { dataQuality.scan() } }
                .onFailure { logger.warn("Data-quality scan failed for tenant {}: {}", tenantId, it.message) }
        }
    }
}
