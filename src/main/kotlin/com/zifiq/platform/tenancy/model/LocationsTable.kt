package com.zifiq.platform.tenancy.model

import com.zifiq.platform.identity.model.AppUsersTable
import java.math.BigDecimal
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
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

    // Showroom (and factory) details, V9_3 (docs/05 §2.1). `state` holds a nigeria_states code for new rows.
    val streetAddress = varchar("street_address", 300).nullable()
    val city = varchar("city", 120).nullable()
    val email = varchar("email", 320).nullable()
    val managerName = varchar("manager_name", 160).nullable()
    val dateOpened = date("date_opened").nullable()
    val storageCapacity = varchar("storage_capacity", 80).nullable()
    val operatingHours = varchar("operating_hours", 120).nullable()
    /** Where received stock goes by default: the location itself or one of its areas. */
    val defaultReceivingLocationId = uuid("default_receiving_location_id").nullable()
    /** Where POS deducts stock by default (showrooms): the location itself or one of its areas. */
    val defaultSellingLocationId = uuid("default_selling_location_id").nullable()
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
