package com.zifiq.reports.service

import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.time.Instant
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * Fires due scheduled reports (docs/13 §4.7). Per tenant it claims the due schedules (advancing each so
 * it is not re-fired), then enqueues each one's export **as the schedule's owner** — the export is then
 * produced and delivered by [ExportQueueWorker]. A schedule whose owner can no longer run the report is
 * paused by [ScheduleService.fire].
 */
@Component
class ScheduleRunner(
    private val database: Database,
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val schedules: ScheduleService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${reports.scheduleRunnerDelayMs:60000}")
    fun run() {
        val now = Instant.now()
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            val due = runCatching { tx.executeForTenant(tenantId) { schedules.claimDue(now) } }
                .getOrElse { logger.warn("Claiming due schedules for tenant {} failed", tenantId, it); emptyList() }
            due.forEach { d ->
                tenantContext.set(tenantId)
                try {
                    SecurityContextHolder.getContext().authentication =
                        JwtAuthenticationToken(Jwt.withTokenValue("schedule-runner").header("alg", "none").subject(d.ownerId.toString()).build())
                    runCatching { schedules.fire(d) }.onFailure { logger.warn("Firing schedule {} failed", d.id, it) }
                } finally {
                    SecurityContextHolder.clearContext()
                    tenantContext.clear()
                }
            }
        }
    }
}
