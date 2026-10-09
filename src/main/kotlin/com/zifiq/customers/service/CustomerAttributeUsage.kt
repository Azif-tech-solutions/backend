package com.zifiq.customers.service

import com.zifiq.platform.customfields.CustomAttributeUsage
import com.zifiq.platform.customfields.CustomFieldEntity
import com.zifiq.platform.tenancy.TenantContext
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.springframework.stereotype.Component

/** How customers use each custom field, for the Settings field builder (docs/06 §4.5). */
@Component
class CustomerAttributeUsage(private val tenantContext: TenantContext) : CustomAttributeUsage {
    override val entity = CustomFieldEntity.CUSTOMER

    override fun counts(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        query(
            "select key, count(*) from customers, jsonb_object_keys(custom_attributes) key where tenant_id = ? group by key",
            listOf(UUIDColumnType() to tenantContext.requireTenantId()),
        ) { rs -> while (rs.next()) result[rs.getString(1)] = rs.getLong(2) }
        return result
    }

    override fun countWithValue(fieldKey: String, value: String): Long = count(
        "select count(*) from customers where tenant_id = ? and lower(custom_attributes ->> ?) = lower(?)",
        listOf(UUIDColumnType() to tenantContext.requireTenantId(), TextColumnType() to fieldKey, TextColumnType() to value),
    )

    override fun countMissing(fieldKey: String): Long = count(
        "select count(*) from customers where tenant_id = ? and status <> 'REMOVED' and coalesce(custom_attributes ->> ?, '') = ''",
        listOf(UUIDColumnType() to tenantContext.requireTenantId(), TextColumnType() to fieldKey),
    )

    private fun count(sql: String, args: List<Pair<IColumnType<*>, Any?>>): Long {
        var count = 0L
        query(sql, args) { rs -> rs.next(); count = rs.getLong(1) }
        return count
    }

    private fun query(sql: String, args: List<Pair<IColumnType<*>, Any?>>, read: (java.sql.ResultSet) -> Unit) {
        TransactionManager.current().exec(sql, args) { rs -> read(rs) }
    }
}
