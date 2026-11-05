package com.zifiq.communication.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * The Comms. Guide (docs/12 §2.1): a commission rate per finished product and beneficiary type,
 * effective-dated. The rate in force on a date is the row with the greatest effective_from on or
 * before it; an INACTIVE row (or no row) means the product earns nothing.
 */
object CommissionRatesTable : UUIDTable("commission_rates") {
    val tenantId = reference("tenant_id", TenantsTable)
    val stockItemId = uuid("stock_item_id")
    val beneficiaryType = varchar("beneficiary_type", 14)
    val rateType = varchar("rate_type", 10)
    val rate = decimal("rate", 14, 4)
    val referencePrice = decimal("reference_price", 14, 2).nullable()
    val status = varchar("status", 10)
    val effectiveFrom = date("effective_from")
    val effectiveTo = date("effective_to").nullable()
    val note = varchar("note", 300).nullable()
    val setByUserId = reference("set_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
