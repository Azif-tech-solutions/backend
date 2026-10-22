package com.zifiq.cashier.service

import com.zifiq.cashier.model.SalesTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.time.Duration
import java.time.Instant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Clears carts left open on a till (docs/09 §4.2 Abandon): an OPEN sale untouched for
 * `cashier.sale.abandonAfterHours` is abandoned. An open sale holds no stock and no money, so this
 * only tidies the till (and lets its session close). Hourly; idempotent.
 */
@Component
class StaleSaleSweep(
    private val database: Database,
    private val tx: TenantScopedTransaction,
    private val support: CashierSupport,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${zifiq.cashier.stale-sale-cron:0 20 * * * *}")
    fun run() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            runCatching { tx.executeForTenant(tenantId) { sweep() } }
                .onFailure { logger.warn("Stale sale sweep failed for tenant {}: {}", tenantId, it.message) }
        }
    }

    /** One pass for the current tenant (caller's transaction). Returns how many carts were cleared. Visible for tests. */
    fun sweep(now: Instant = Instant.now()): Int {
        val cutoff = now - Duration.ofHours(support.settings().int(CashierSettings.ABANDON_AFTER_HOURS).toLong())
        return SalesTable.update({ (SalesTable.tenantId eq support.tenantId()) and (SalesTable.status eq "OPEN") and (SalesTable.updatedAt less cutoff) }) {
            it[status] = "ABANDONED"
            it[abandonedAt] = now
            it[version] = version + 1
            it[updatedAt] = now
        }
    }
}
