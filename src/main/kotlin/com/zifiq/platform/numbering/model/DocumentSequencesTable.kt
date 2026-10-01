package com.zifiq.platform.numbering.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.sql.Table

object DocumentSequencesTable : Table("document_sequences") {
    val tenantId = reference("tenant_id", TenantsTable)
    val prefix = varchar("prefix", 10)
    val nextValue = long("next_value").default(1)

    override val primaryKey = PrimaryKey(tenantId, prefix)
}
