package com.zifiq.cashier.service

import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Expires pro-formas past their validity (docs/09 §4.4): a SENT pro-forma whose valid-until date has
 * passed becomes EXPIRED. Runs daily; idempotent (re-running changes nothing).
 */
@Component
class ProFormaExpirySweep(
    private val database: Database,
    private val tx: TenantScopedTransaction,
    private val proFormas: ProFormaService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${zifiq.cashier.pro-forma-expiry-cron:0 5 0 * * *}")
    fun run() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            runCatching { tx.executeForTenant(tenantId) { proFormas.expireDue() } }
                .onFailure { logger.warn("Pro-forma expiry sweep failed for tenant {}: {}", tenantId, it.message) }
        }
    }
}
