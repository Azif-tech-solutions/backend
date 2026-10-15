package com.zifiq.platform.tenancy

import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.Key
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
        val tenantId = tenantContext.requireTenantId()
        exec(
            "select set_config('app.tenant_id', ?, true)",
            listOf(VarCharColumnType() to tenantId.toString()),
        )
        putUserData(RLS_TENANT, tenantId)
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

    companion object {
        private val RLS_TENANT = Key<UUID>()

        /**
         * The tenant row-level security is enforcing in the current transaction (set with
         * `app.tenant_id` by [execute]). In-process caches of tenant data key on this, so a cached
         * value can only ever be served to the tenant whose RLS scope produced it.
         */
        fun rlsTenant(): UUID = rlsTenantOrNull() ?: throw IllegalStateException("Not inside a tenant-scoped transaction")

        /** The same, or null outside a tenant-scoped transaction. */
        fun rlsTenantOrNull(): UUID? = TransactionManager.currentOrNull()?.getUserData(RLS_TENANT)
    }
}
