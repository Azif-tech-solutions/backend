package com.zifiq.platform.tenancy.model

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object TenantsTable : UUIDTable("tenants") {
    val parentTenantId = reference("parent_tenant_id", TenantsTable).nullable()
    val name = varchar("name", 160)
    val createdAt = timestamp("created_at")
}
