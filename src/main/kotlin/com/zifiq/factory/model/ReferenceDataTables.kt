package com.zifiq.factory.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

// Factory Setup reference registers (docs/02-factory.md): Categories, Units of Measure,
// Suppliers, Manufacturers.

object CategoriesTable : UUIDTable("categories") {
    val tenantId = reference("tenant_id", TenantsTable)
    val name = varchar("name", 120)
    val type = varchar("type", 20)
    val brand = varchar("brand", 120).nullable()
    val description = varchar("description", 300).nullable()
    val createdAt = timestamp("created_at")
}

object UnitsOfMeasureTable : UUIDTable("units_of_measure") {
    val tenantId = reference("tenant_id", TenantsTable)
    val code = varchar("code", 20)
    val name = varchar("name", 80)
    val symbol = varchar("symbol", 10).nullable()
    val createdAt = timestamp("created_at")
}

object SuppliersTable : UUIDTable("suppliers") {
    val tenantId = reference("tenant_id", TenantsTable)
    val code = varchar("code", 40)
    val companyName = varchar("company_name", 160)
    val contactEmail = varchar("contact_email", 320).nullable()
    val phone = varchar("phone", 40).nullable()
    val address = varchar("address", 300).nullable()
    val supplies = varchar("supplies", 300).nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val createdAt = timestamp("created_at")
}

object ManufacturersTable : UUIDTable("manufacturers") {
    val tenantId = reference("tenant_id", TenantsTable)
    val code = varchar("code", 40)
    val name = varchar("name", 160)
    val country = varchar("country", 80).nullable()
    val contactEmail = varchar("contact_email", 320).nullable()
    val phone = varchar("phone", 40).nullable()
    val produces = varchar("produces", 300).nullable()
    val status = varchar("status", 20).default("ACTIVE")
    val createdAt = timestamp("created_at")
}
