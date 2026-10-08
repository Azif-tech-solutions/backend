package com.zifiq.platform.tenancy

import java.util.UUID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component

@Component
class TenantScopedTransaction(
    private val database: Database,
    private val tenantContext: TenantContext,
) {
    fun <T> execute(block: () -> T): T = transaction(database) {
        exec(
            "select set_config('app.tenant_id', ?, true)",
            listOf(VarCharColumnType() to tenantContext.requireTenantId().toString()),
        )
        block()
    }

    /**
     * For work outside an HTTP request (scheduled jobs, event consumers): runs [block] in a
     * transaction scoped to [tenantId], with the tenant context set for the duration so code that
     * reads `TenantContext` (e.g. `DomainEvents.record`) sees the same tenant. The previous
     * context, if any, is restored afterwards.
     */
    fun <T> executeForTenant(tenantId: UUID, block: () -> T): T {
        val previous = runCatching { tenantContext.requireTenantId() }.getOrNull()
        tenantContext.set(tenantId)
        try {
            return execute(block)
        } finally {
            if (previous != null) tenantContext.set(previous) else tenantContext.clear()
        }
    }
}
