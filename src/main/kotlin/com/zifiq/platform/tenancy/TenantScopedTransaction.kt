package com.zifiq.platform.tenancy

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
}
