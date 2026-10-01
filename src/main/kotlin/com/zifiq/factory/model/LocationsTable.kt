package com.zifiq.factory.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Factory Setup > Factories (docs/02-factory.md). The `type` column is retained for future reuse
 * (e.g. if Showroom locations end up sharing this table once the Showroom module is built), but
 * this module's own service/DTOs only ever deal with type='FACTORY' rows.
 */
object LocationsTable : UUIDTable("locations") {
    val tenantId = reference("tenant_id", TenantsTable)
    val type = varchar("type", 20)
    val code = varchar("code", 40)
    val name = varchar("name", 160)
    val address = varchar("address", 300).nullable()
    val state = varchar("state", 80).nullable()
    val phone = varchar("phone", 40).nullable()
    val managerUserId = reference("manager_user_id", AppUsersTable).nullable()
    val vatRate = decimal("vat_rate", 5, 2).default(BigDecimal("7.50"))
    val vatEnabled = bool("vat_enabled").default(true)
    val vatRegistrationNumber = varchar("vat_registration_number", 60).nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
