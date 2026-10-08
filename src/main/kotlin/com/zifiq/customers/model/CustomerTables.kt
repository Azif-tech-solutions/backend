package com.zifiq.customers.model

import com.zifiq.common.json.Json
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

/** Mirrors V8__customers_module.sql (docs/04 §2). Generated `search_vector` columns aren't mapped. */
object DealerCategoriesTable : UUIDTable("dealer_categories") {
    val tenantId = reference("tenant_id", TenantsTable)
    val name = varchar("name", 60)
    val discountPct = decimal("discount_pct", 5, 2)
    val suggestedCreditLimit = decimal("suggested_credit_limit", 14, 2)
    val sortOrder = integer("sort_order")
    val status = varchar("status", 20)
    val createdByUserId = uuid("created_by_user_id")
    val createdAt = timestamp("created_at")
}

object CorrespondentsTable : UUIDTable("correspondents") {
    val tenantId = reference("tenant_id", TenantsTable)
    val code = varchar("code", 20)
    val name = varchar("name", 160)
    val phone = varchar("phone", 40)
    val phoneE164 = varchar("phone_e164", 20)
    val email = varchar("email", 320).nullable()
    val userId = uuid("user_id").nullable()
    val region = varchar("region", 60).nullable()
    val locationId = uuid("location_id").nullable()
    val responsibilities = varchar("responsibilities", 300).nullable()
    val status = varchar("status", 20)
    val assignedAt = date("assigned_at").nullable()
    val createdByUserId = uuid("created_by_user_id")
    val createdAt = timestamp("created_at")
}

object CorrespondentAssignmentHistoryTable : UUIDTable("correspondent_assignment_history") {
    val tenantId = reference("tenant_id", TenantsTable)
    val correspondentId = reference("correspondent_id", CorrespondentsTable)
    val action = varchar("action", 20)
    val region = varchar("region", 60).nullable()
    val locationId = uuid("location_id").nullable()
    val effectiveFrom = date("effective_from")
    val note = varchar("note", 300).nullable()
    val performedByUserId = uuid("performed_by_user_id")
    val createdAt = timestamp("created_at")
}

object CustomersTable : UUIDTable("customers") {
    val tenantId = reference("tenant_id", TenantsTable)
    val code = varchar("code", 20)
    val type = varchar("type", 30)
    val isTemporary = bool("is_temporary")
    val name = varchar("name", 200)
    val contactPerson = varchar("contact_person", 160).nullable()
    val phone = varchar("phone", 40).nullable()
    val phoneE164 = varchar("phone_e164", 20).nullable()
    val alternatePhone = varchar("alternate_phone", 40).nullable()
    val email = varchar("email", 320).nullable()
    val tin = varchar("tin", 40).nullable()
    val businessDescription = varchar("business_description", 200).nullable()
    val streetAddress = varchar("street_address", 300).nullable()
    val city = varchar("city", 120).nullable()
    val stateCode = varchar("state_code", 5).nullable()
    val lgaCode = varchar("lga_code", 10).nullable()
    val countryCode = varchar("country_code", 2)
    val locationId = uuid("location_id").nullable()
    val correspondentId = uuid("correspondent_id").nullable()
    val dealerCategoryId = uuid("dealer_category_id").nullable()
    val creditLimit = decimal("credit_limit", 14, 2)
    val walletBalance = decimal("wallet_balance", 14, 2)
    val depositBalance = decimal("deposit_balance", 14, 2)
    val lifetimeValue = decimal("lifetime_value", 14, 2)
    val lastPurchaseAt = timestamp("last_purchase_at").nullable()
    val customerSince = date("customer_since")
    val status = varchar("status", 20)
    val statusReason = varchar("status_reason", 300).nullable()
    val removedAt = timestamp("removed_at").nullable()
    val userId = uuid("user_id").nullable()
    // Raw JSON text (see ModuleSettingsTable for why JsonNode/Map aren't bound directly).
    val customAttributes = jsonb<String>("custom_attributes", { it }, { it })
    val createdByUserId = uuid("created_by_user_id")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    fun attributes(json: String): Map<String, Any?> = Json.readMap(json)
}

object CustomerLedgerEntriesTable : UUIDTable("customer_ledger_entries") {
    val tenantId = reference("tenant_id", TenantsTable)
    val customerId = reference("customer_id", CustomersTable)
    val balanceType = varchar("balance_type", 10)
    val entryType = varchar("entry_type", 30)
    val direction = varchar("direction", 6)
    val amount = decimal("amount", 14, 2)
    val balanceAfter = decimal("balance_after", 14, 2)
    val reference = varchar("reference", 40)
    val description = varchar("description", 300)
    val paymentMethod = varchar("payment_method", 30).nullable()
    val sourceModule = varchar("source_module", 30)
    val sourceType = varchar("source_type", 40)
    val sourceId = uuid("source_id").nullable()
    val locationId = uuid("location_id").nullable()
    val reversesEntryId = uuid("reverses_entry_id").nullable()
    val reason = varchar("reason", 300).nullable()
    val performedByUserId = uuid("performed_by_user_id").nullable()
    val entryDate = date("entry_date")
    val createdAt = timestamp("created_at")
}

object CustomerReclassificationsTable : UUIDTable("customer_reclassifications") {
    val tenantId = reference("tenant_id", TenantsTable)
    val customerId = reference("customer_id", CustomersTable)
    val fromType = varchar("from_type", 30)
    val toType = varchar("to_type", 30)
    val direction = varchar("direction", 10)
    val reason = varchar("reason", 40)
    val note = varchar("note", 300).nullable()
    val fromDealerCategoryId = uuid("from_dealer_category_id").nullable()
    val toDealerCategoryId = uuid("to_dealer_category_id").nullable()
    val fromCreditLimit = decimal("from_credit_limit", 14, 2)
    val toCreditLimit = decimal("to_credit_limit", 14, 2)
    val effectiveFrom = date("effective_from")
    val status = varchar("status", 20)
    val authorisedByUserId = uuid("authorised_by_user_id")
    val appliedAt = timestamp("applied_at").nullable()
    val createdAt = timestamp("created_at")
}
