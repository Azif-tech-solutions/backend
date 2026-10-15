package com.zifiq.settings.dto

import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

// ---- Company profile (docs/06 §4.1) ----

/** PUT replaces the whole profile: an omitted optional field is cleared. */
data class CompanyProfileRequest(
    @field:NotBlank @field:Size(min = 2, max = 200) val tradingName: String,
    @field:Size(max = 200) val legalName: String? = null,
    @field:Size(max = 40) val registrationNumber: String? = null,
    @field:Pattern(regexp = "[A-Za-z0-9-]{6,30}", message = "must be 6-30 letters, digits or hyphens") val tin: String? = null,
    @field:Size(max = 60) val vatRegistrationNumber: String? = null,
    val nrsBusinessId: UUID? = null,
    @field:Size(max = 200) val businessDescription: String? = null,
    @field:Email @field:Size(max = 320) val email: String? = null,
    @field:NotBlank @field:Size(max = 40) val phone: String,
    @field:Pattern(regexp = "(?i)https?://[^\\s]+", message = "must start with http:// or https://") @field:Size(max = 200) val website: String? = null,
    @field:NotBlank @field:Size(max = 300) val streetAddress: String,
    @field:Size(max = 120) val city: String? = null,
    @field:Size(max = 20) val postalCode: String? = null,
    @field:Size(max = 5) val stateCode: String? = null,
    @field:Size(max = 10) val lgaCode: String? = null,
    @field:Pattern(regexp = "[A-Za-z]{2}", message = "must be a 2-letter country code") val countryCode: String = "NG",
    @field:Size(max = 500) val documentFooter: String? = null,
    @field:Min(1) @field:Max(12) val financialYearStartMonth: Int = 1,
)

data class NrsReadiness(val ready: Boolean, val missing: List<String>)

data class CompanyProfileView(
    val tradingName: String,
    val legalName: String?,
    val registrationNumber: String?,
    val tin: String?,
    val vatRegistrationNumber: String?,
    val nrsBusinessId: UUID?,
    val businessDescription: String?,
    val email: String?,
    val phone: String,
    val website: String?,
    val streetAddress: String,
    val city: String?,
    val postalCode: String?,
    val stateCode: String?,
    val stateName: String?,
    val lgaCode: String?,
    val lgaName: String?,
    val countryCode: String,
    val hasLogo: Boolean,
    /** A short-lived S3 link that shows the logo in place (`<img src>`); fetch the profile again for a fresh one after [logoUrlExpiresAt]. */
    val logoUrl: String?,
    val logoUrlExpiresAt: Instant?,
    val logoContentType: String?,
    /** False for AVIF and WebP, which the PDF engine can't draw: invoices, receipts and statements print the trading name instead. */
    val logoInDocuments: Boolean,
    val logoUpdatedAt: Instant?,
    val documentFooter: String?,
    val financialYearStartMonth: Int,
    val nrsReadiness: NrsReadiness,
    val updatedBy: UserRef?,
    val updatedAt: Instant,
)

// ---- Onboarding (docs/06 §4.3) ----

data class OnboardingStep(val key: String, val done: Boolean, val skipped: Boolean)

data class OnboardingStatus(val completed: Boolean, val completedAt: Instant?, val steps: List<OnboardingStep>)

/** skippedSteps: BRANCH and/or FACTORY (checked in the service). */
data class OnboardingCompleteRequest(val skippedSteps: List<String> = emptyList())

// ---- Custom fields (docs/06 §4.5) ----

data class CustomFieldCreateRequest(
    @field:NotNull @field:Pattern(regexp = "STOCK_ITEM|CUSTOMER|SUPPLIER", message = "must be STOCK_ITEM, CUSTOMER or SUPPLIER") val entityType: String?,
    @field:NotBlank @field:Size(min = 2, max = 80) val label: String,
    @field:NotNull @field:Pattern(regexp = "TEXT|NUMBER|DATE|BOOLEAN|SELECT", message = "must be TEXT, NUMBER, DATE, BOOLEAN or SELECT") val dataType: String?,
    val options: List<String>? = null,
    val required: Boolean = false,
    val sortOrder: Int? = null,
)

/** label and required are replaced; options and sortOrder are kept when omitted. */
data class CustomFieldUpdateRequest(
    @field:NotBlank @field:Size(min = 2, max = 80) val label: String,
    val options: List<String>? = null,
    val required: Boolean = false,
    val sortOrder: Int? = null,
)

data class CustomFieldStatusRequest(
    @field:NotNull @field:Pattern(regexp = "ACTIVE|INACTIVE", message = "must be ACTIVE or INACTIVE") val status: String?,
)

data class CustomFieldView(
    val id: UUID,
    val entityType: String,
    val fieldKey: String,
    val label: String,
    val dataType: String,
    val options: List<String>,
    val required: Boolean,
    val status: String,
    val sortOrder: Int,
    val usageCount: Long,
    val createdAt: Instant,
)

// ---- Settings search (docs/06 §9) ----

data class SettingsSearchHit(val type: String, val label: String, val detail: String?, val route: String)
