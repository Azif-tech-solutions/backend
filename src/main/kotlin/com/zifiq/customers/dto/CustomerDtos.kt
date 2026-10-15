package com.zifiq.customers.dto

import com.zifiq.platform.directory.UserRef
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerDirection
import com.zifiq.customers.spi.LedgerEntryType
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val PHONE_PATTERN = "^[+0-9 ()-]{7,20}$"

// ---- Customers ----

/** docs/04 §4.1. Type-specific rules (dealer needs category/address/state, …) are checked in the service. */
data class CustomerRequest(
    @field:NotNull val type: CustomerType?,
    @field:NotBlank @field:Size(min = 2, max = 200) val name: String,
    @field:Size(max = 160) val contactPerson: String? = null,
    @field:Pattern(regexp = PHONE_PATTERN, message = "must be 7-20 characters of digits, spaces, +, (, ) or -") val phone: String? = null,
    @field:Pattern(regexp = PHONE_PATTERN, message = "must be 7-20 characters of digits, spaces, +, (, ) or -") val alternatePhone: String? = null,
    @field:Email @field:Size(max = 320) val email: String? = null,
    @field:Size(max = 40) val tin: String? = null,
    @field:Size(max = 200) val businessDescription: String? = null,
    @field:Size(max = 300) val streetAddress: String? = null,
    @field:Size(max = 120) val city: String? = null,
    @field:Size(max = 5) val stateCode: String? = null,
    @field:Size(max = 10) val lgaCode: String? = null,
    val locationId: UUID? = null,
    val correspondentId: UUID? = null,
    val dealerCategoryId: UUID? = null,
    @field:PositiveOrZero val creditLimit: BigDecimal? = null,
    val customerSince: LocalDate? = null,
    val userId: UUID? = null,
    val customAttributes: Map<String, Any?>? = null,
)

/** docs/04 §4.3: everything except type, dealer category and customer-since (changed by reclassification or immutable). */
data class CustomerUpdateRequest(
    @field:NotBlank @field:Size(min = 2, max = 200) val name: String,
    @field:Size(max = 160) val contactPerson: String? = null,
    @field:Pattern(regexp = PHONE_PATTERN, message = "must be 7-20 characters of digits, spaces, +, (, ) or -") val phone: String? = null,
    @field:Pattern(regexp = PHONE_PATTERN, message = "must be 7-20 characters of digits, spaces, +, (, ) or -") val alternatePhone: String? = null,
    @field:Email @field:Size(max = 320) val email: String? = null,
    @field:Size(max = 40) val tin: String? = null,
    @field:Size(max = 200) val businessDescription: String? = null,
    @field:Size(max = 300) val streetAddress: String? = null,
    @field:Size(max = 120) val city: String? = null,
    @field:Size(max = 5) val stateCode: String? = null,
    @field:Size(max = 10) val lgaCode: String? = null,
    val locationId: UUID? = null,
    val correspondentId: UUID? = null,
    @field:PositiveOrZero val creditLimit: BigDecimal? = null,
    val userId: UUID? = null,
    val customAttributes: Map<String, Any?>? = null,
)

data class CustomerStatusRequest(
    @field:NotNull val status: CustomerStatus?,
    @field:NotBlank @field:Size(max = 300) val reason: String,
)

data class LocationRef(val id: UUID, val code: String, val name: String)
data class CorrespondentBrief(val id: UUID, val code: String, val name: String, val status: String)
data class DealerCategoryBrief(val id: UUID, val name: String, val discountPct: BigDecimal)

data class CustomerDetail(
    val id: UUID,
    val code: String,
    val type: CustomerType,
    val isTemporary: Boolean,
    val name: String,
    val contactPerson: String?,
    val phone: String?,
    val alternatePhone: String?,
    val email: String?,
    val tin: String?,
    val businessDescription: String?,
    val streetAddress: String?,
    val city: String?,
    val stateCode: String?,
    val stateName: String?,
    val lgaCode: String?,
    val lgaName: String?,
    val countryCode: String,
    val location: LocationRef?,
    val correspondent: CorrespondentBrief?,
    val dealerCategory: DealerCategoryBrief?,
    val creditLimit: BigDecimal,
    val walletBalance: BigDecimal,
    val depositBalance: BigDecimal,
    val lifetimeValue: BigDecimal,
    val lastPurchaseAt: Instant?,
    val customerSince: LocalDate,
    val status: CustomerStatus,
    val statusReason: String?,
    val userId: UUID?,
    val customAttributes: Map<String, Any?>,
    val createdAt: Instant,
)

data class CustomerSummary(
    val id: UUID,
    val code: String,
    val type: CustomerType,
    val isTemporary: Boolean,
    val name: String,
    val contactPerson: String?,
    val phone: String?,
    val stateCode: String?,
    val stateName: String?,
    val locationId: UUID?,
    val locationName: String?,
    val correspondentId: UUID?,
    val correspondentName: String?,
    val dealerCategoryName: String?,
    val creditLimit: BigDecimal,
    val walletBalance: BigDecimal,
    val depositBalance: BigDecimal,
    val lifetimeValue: BigDecimal,
    val lastPurchaseAt: Instant?,
    val customerSince: LocalDate,
    val status: CustomerStatus,
)

data class CustomerStats(
    val total: Long,
    val active: Long,
    val watch: Long,
    val suspended: Long,
    val dealers: Long,
    val special: Long,
    val ordinary: Long,
    val noCredit: Long,
    val walletsHeld: Long,
    val walletBalanceTotal: BigDecimal,
    val depositBalanceTotal: BigDecimal,
)

data class CustomerLookupRow(
    val id: UUID,
    val code: String,
    val label: String,
    val sublabel: String,
    val type: CustomerType,
    val status: CustomerStatus,
    val disabled: Boolean,
    val disabledReason: String?,
    val walletBalance: BigDecimal,
    val depositBalance: BigDecimal,
    val creditLimit: BigDecimal,
)

// ---- Reclassification ----

enum class ReclassificationReason {
    SUSTAINED_SALES_PERFORMANCE, MET_PURCHASE_VOLUME, COMPLIANCE_CONCERN,
    PAYMENT_ARREARS, PROLONGED_INACTIVITY, MANAGEMENT_DECISION,
}

data class ReclassificationRequest(
    @field:NotNull val toType: CustomerType?,
    @field:NotNull val reason: ReclassificationReason?,
    @field:Size(max = 300) val note: String? = null,
    @field:NotNull val effectiveFrom: LocalDate?,
    val toDealerCategoryId: UUID? = null,
    @field:PositiveOrZero val toCreditLimit: BigDecimal? = null,
)

data class ReclassificationSummary(
    val id: UUID,
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val fromType: CustomerType,
    val toType: CustomerType,
    val direction: String,
    val reason: String,
    val note: String?,
    val effectiveFrom: LocalDate,
    val status: String,
    val fromCreditLimit: BigDecimal,
    val toCreditLimit: BigDecimal,
    val toDealerCategoryName: String?,
    val walletBalanceCarriedOver: BigDecimal,
    val authorisedBy: UserRef,
    val appliedAt: Instant?,
    val createdAt: Instant,
)

// ---- Ledger ----

data class LedgerRow(
    val id: UUID,
    val entryDate: LocalDate,
    val reference: String,
    val entryType: LedgerEntryType,
    val displayType: String,
    val direction: LedgerDirection,
    val description: String,
    val paymentMethod: String?,
    /** Signed: credit +, debit −. */
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val locationId: UUID?,
    val sourceModule: String,
    val performedBy: UserRef?,
    val reversible: Boolean,
    val reversedByEntryId: UUID?,
    val createdAt: Instant,
)

data class LedgerPage(
    val customer: CustomerSummary,
    val balanceType: BalanceType,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val totalCredits: BigDecimal,
    val totalDebits: BigDecimal,
    val items: List<LedgerRow>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

data class WalletAdjustmentRequest(
    @field:NotNull val balanceType: BalanceType?,
    @field:NotNull val direction: LedgerDirection?,
    @field:NotNull @field:DecimalMin(value = "0.01") @field:DecimalMax(value = "999999999999.99") val amount: BigDecimal?,
    @field:NotBlank @field:Size(max = 300) val reason: String,
)

data class ReversalRequest(@field:NotBlank @field:Size(max = 300) val reason: String)

// ---- Dealer categories ----

data class DealerCategoryRequest(
    @field:NotBlank @field:Size(max = 60) val name: String,
    @field:NotNull @field:DecimalMin("0") @field:DecimalMax("100") val discountPct: BigDecimal?,
    @field:NotNull @field:PositiveOrZero val suggestedCreditLimit: BigDecimal?,
    val sortOrder: Int = 0,
)

data class DealerCategoryStatusRequest(@field:NotNull @field:Pattern(regexp = "ACTIVE|INACTIVE") val status: String?)

data class DealerCategorySummary(
    val id: UUID,
    val name: String,
    val discountPct: BigDecimal,
    val suggestedCreditLimit: BigDecimal,
    val sortOrder: Int,
    val status: String,
    val dealerCount: Long,
)

// ---- Correspondents ----

data class CorrespondentRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:NotBlank @field:Pattern(regexp = PHONE_PATTERN, message = "must be 7-20 characters of digits, spaces, +, (, ) or -") val phone: String,
    @field:Email @field:Size(max = 320) val email: String? = null,
    val userId: UUID? = null,
    @field:Size(max = 60) val region: String? = null,
    val locationId: UUID? = null,
    @field:Size(max = 300) val responsibilities: String? = null,
)

data class CorrespondentUpdateRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:NotBlank @field:Pattern(regexp = PHONE_PATTERN, message = "must be 7-20 characters of digits, spaces, +, (, ) or -") val phone: String,
    @field:Email @field:Size(max = 320) val email: String? = null,
    val userId: UUID? = null,
    @field:Size(max = 300) val responsibilities: String? = null,
)

data class CorrespondentAssignRequest(
    @field:NotBlank @field:Size(max = 60) val region: String,
    @field:NotNull val locationId: UUID?,
    val effectiveFrom: LocalDate? = null,
    @field:Size(max = 300) val note: String? = null,
)

data class CorrespondentDeassignRequest(
    val effectiveFrom: LocalDate? = null,
    @field:Size(max = 300) val note: String? = null,
)

data class CustomerCorrespondentRequest(val correspondentId: UUID?)

data class CorrespondentSummary(
    val id: UUID,
    val code: String,
    val name: String,
    val phone: String,
    val email: String?,
    val region: String?,
    val locationId: UUID?,
    val locationName: String?,
    val status: String,
    val assignedAt: LocalDate?,
    val customerCount: Long,
    val dealerCount: Long,
    val responsibilities: String?,
    val userLinked: Boolean,
)

data class CorrespondentHistoryRow(
    val action: String,
    val region: String?,
    val locationId: UUID?,
    val locationName: String?,
    val effectiveFrom: LocalDate,
    val note: String?,
    val performedBy: UserRef,
    val createdAt: Instant,
)

data class CorrespondentDetail(
    val correspondent: CorrespondentSummary,
    val history: List<CorrespondentHistoryRow>,
    /** Present after a de-assignment: customers still linked who should be re-linked. */
    val affectedCustomers: Long? = null,
)
