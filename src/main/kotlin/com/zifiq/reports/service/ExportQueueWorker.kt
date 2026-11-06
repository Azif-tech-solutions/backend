package com.zifiq.reports.service

import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.util.UUID
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
 * Drains QUEUED report exports (docs/13 §4.6). For each tenant it claims a small batch
 * (QUEUED → PROCESSING, an atomic guarded update so two workers can't take the same row), then
 * produces each one **as the user who requested it** — a synthetic JWT with that user's id is set on
 * the security context so the report re-runs under their permissions, location scope and cost
 * visibility, exactly as the synchronous path would. A failure marks that export FAILED and never
 * blocks the others.
 */
@Component
class ExportQueueWorker(
    private val database: Database,
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val exports: ExportService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${reports.exportWorkerDelayMs:10000}")
    fun drain() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            val claimed = runCatching { tx.executeForTenant(tenantId) { exports.claimQueued(BATCH) } }
                .getOrElse { logger.warn("Claiming queued exports for tenant {} failed", tenantId, it); emptyList() }
            claimed.forEach { claim -> process(tenantId, claim) }
        }
    }

    private fun process(tenantId: UUID, claim: ExportService.Claim) {
        tenantContext.set(tenantId)
        try {
            SecurityContextHolder.getContext().authentication =
                JwtAuthenticationToken(Jwt.withTokenValue("export-worker").header("alg", "none").subject(claim.requesterId.toString()).build())
            runCatching { exports.complete(claim.exportId) }
                .onFailure { logger.warn("Producing queued export {} failed", claim.exportId, it) }
        } finally {
            SecurityContextHolder.clearContext()
            tenantContext.clear()
        }
    }

    companion object {
        /** Exports produced per tenant per tick; the worker runs often, so this stays small. */
        private const val BATCH = 2
    }
}
