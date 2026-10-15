package com.zifiq.settings.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

/** One row per tenant (V10, docs/06 §2). */
object CompanyProfilesTable : Table("company_profiles") {
    val tenantId = reference("tenant_id", TenantsTable)
    val tradingName = varchar("trading_name", 200)
    val legalName = varchar("legal_name", 200).nullable()
    val registrationNumber = varchar("registration_number", 40).nullable()
    val tin = varchar("tin", 30).nullable()
    val vatRegistrationNumber = varchar("vat_registration_number", 60).nullable()
    val nrsBusinessId = uuid("nrs_business_id").nullable()
    val businessDescription = varchar("business_description", 200).nullable()
    val email = varchar("email", 320).nullable()
    val phone = varchar("phone", 40)
    val phoneE164 = varchar("phone_e164", 20).nullable()
    val website = varchar("website", 200).nullable()
    val streetAddress = varchar("street_address", 300)
    val city = varchar("city", 120).nullable()
    val postalCode = varchar("postal_code", 20).nullable()
    val stateCode = varchar("state_code", 5).nullable()
    val lgaCode = varchar("lga_code", 10).nullable()
    val countryCode = varchar("country_code", 2)
    /** The logo in file storage (purpose `settings.company-logo`, V14_7). */
    val logoFileId = uuid("logo_file_id").nullable()
    val logoUpdatedAt = timestamp("logo_updated_at").nullable()
    val documentFooter = varchar("document_footer", 500).nullable()
    val financialYearStartMonth = integer("financial_year_start_month")
    val onboardingCompletedAt = timestamp("onboarding_completed_at").nullable()
    // Raw JSON text, as ModuleSettingsTable does.
    val onboardingSkippedSteps = jsonb<String>("onboarding_skipped_steps", { it }, { it })
    val updatedByUserId = uuid("updated_by_user_id").nullable()
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(tenantId)
}
