package com.zifiq.platform.tenancy.model

import com.zifiq.platform.identity.model.AppUsersTable
import java.math.BigDecimal
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Every business location: factories and showrooms (V3__locations_and_rbac_expansion.sql). Owned by
 * platform because every module needs it (docs/03 §11.1); Factory Setup manages FACTORY rows and
 * Showroom manages SHOWROOM rows.
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
    /** Set only on STORAGE_AREA rows: the factory or showroom the area belongs to (docs/03 §11.2). */
    val parentLocationId = uuid("parent_location_id").nullable()
    /** STORAGE_AREA only: SHOP_FLOOR, WAREHOUSE, RECEIVING, QUARANTINE, OTHER. */
    val areaKind = varchar("area_kind", 20).nullable()
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
