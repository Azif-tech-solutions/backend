package com.zifiq.platform.tenancy

import java.util.UUID
import org.springframework.stereotype.Component

@Component
class TenantContext {
    private val tenantId = ThreadLocal<UUID?>()

    fun set(value: UUID) = tenantId.set(value)

    fun requireTenantId(): UUID = tenantId.get()
        ?: throw IllegalStateException("Tenant context is not available for this request")

    fun clear() = tenantId.remove()
}
