package com.zifiq.history.service

import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Nightly integrity checks (docs/10 §4.10), at 03:00. Runs per tenant. */
@Component
class IntegrityCheckJob(
    private val database: Database,
    private val tx: TenantScopedTransaction,
    private val integrity: IntegrityCheckService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${zifiq.history.integrity-cron:0 0 3 * * *}")
    fun run() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            runCatching { tx.executeForTenant(tenantId) { integrity.run("SCHEDULED") } }
                .onFailure { logger.warn("Integrity check failed for tenant {}: {}", tenantId, it.message) }
        }
    }
}
