package com.zifiq.invoicing.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ---- Requests (docs/08 §4.1, §4.7) ----

/** Issue a draft, or edit a draft's header: every field optional (issue date defaults to today, due date to issue date + terms). */
data class DraftHeaderRequest(
    val issueDate: LocalDate? = null,
    @field:Min(0) val paymentTermsDays: Int? = null,
    val dueDate: LocalDate? = null,
    @field:Size(max = 300) val paymentTermsNote: String? = null,
    @field:Email @field:Size(max = 320) val buyerEmail: String? = null,
)

data class WalkInBuyerRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Email @field:Size(max = 320) val email: String? = null,
    @field:Size(max = 40) val phone: String? = null,
    @field:Size(max = 40) val tin: String? = null,
    @field:Size(max = 300) val address: String? = null,
    @field:Size(max = 5) val stateCode: String? = null,
)

/** Void-and-reissue to a corrected buyer; line corrections come from Sales & POS through the spi. */
data class ReissueRequest(
    val customerId: UUID? = null,
    @field:Valid val walkInBuyer: WalkInBuyerRequest? = null,
    @field:Email @field:Size(max = 320) val buyerEmail: String? = null,
)

data class VoidInvoiceRequest(
    @field:NotBlank @field:Size(min = 10, max = 300, message = "explain the void in at least 10 characters") val reason: String,
    @field:Valid val reissue: ReissueRequest? = null,
)

data class SandboxLineRequest(
    @field:NotNull val stockItemId: UUID?,
    @field:NotNull @field:DecimalMin(value = "0.001") val quantity: BigDecimal?,
    @field:NotNull @field:DecimalMin(value = "0") val unitPrice: BigDecimal?,
    @field:DecimalMin(value = "0") val discountAmount: BigDecimal? = null,
    @field:DecimalMin(value = "0") val discountRate: BigDecimal? = null,
)

/**
 * Test support until Sales & POS (doc 09) exists: raises an invoice from a made-up source so the
 * invoicing screens and the Postman collection can be exercised. Disabled with
 * `zifiq.invoicing.sandbox.enabled=false`; removed when doc 09 ships.
 */
data class SandboxInvoiceRequest(
    @field:Pattern(regexp = "POS_SALE|PRO_FORMA", message = "must be POS_SALE or PRO_FORMA") val sourceType: String? = null,
    @field:Size(max = 30) val sourceRef: String? = null,
    val sourceDate: LocalDate? = null,
    @field:Size(max = 30) val proFormaRef: String? = null,
    @field:NotNull val locationId: UUID?,
    val customerId: UUID? = null,
    @field:Valid val walkInBuyer: WalkInBuyerRequest? = null,
    val issueDate: LocalDate? = null,
    @field:Min(0) val paymentTermsDays: Int? = null,
    @field:Size(max = 300) val paymentTermsNote: String? = null,
    @field:NotEmpty @field:Valid val lines: List<SandboxLineRequest>,
    /** true = save as a draft (pro-forma conversion); false = issue now (POS checkout). */
    val asDraft: Boolean = false,
)

// ---- Responses (docs/08 §4.2, §4.3, §4.9) ----

/** One row of invoice history (§4.2). */
data class InvoiceRow(
    val id: UUID,
    val number: String?,
    val status: String,
    val displayStatus: String,
    val issued: LocalDate?,
    val due: LocalDate?,
    val customerId: UUID?,
    val customer: String,
    val customerType: String?,
    val locationId: UUID,
    val showroom: String?,
    val sourceType: String,
    val source: String,
    val sourceRef: String,
    val proForma: String?,
    val waybills: List<String>,
    val total: BigDecimal,
    val paid: BigDecimal,
    val credited: BigDecimal,
    val balance: BigDecimal,
    val daysOverdue: Int,
    val delivery: String,
)

data class InvoiceLineDetail(
    val id: UUID,
    val lineNo: Int,
    val stockItemId: UUID,
    val code: String,
    val name: String,
    val quantity: BigDecimal,
    val unit: String,
    val nrsUnitCode: String?,
    val unitPrice: BigDecimal,
    val discountAmount: BigDecimal,
    val discountRate: BigDecimal?,
    val lineNet: BigDecimal,
    val vatCategory: String,
    val nrsTaxCategory: String,
    val vatRate: BigDecimal,
    val vatAmount: BigDecimal,
    val lineTotal: BigDecimal,
    val isService: Boolean,
    val hsnCode: String?,
    val hsnSectionCode: String?,
    val productCategory: String?,
    val isicCode: String?,
    val serviceCategory: String?,
)

data class IssuerBlock(
    val tradingName: String,
    val legalName: String?,
    val tin: String?,
    val vatRegistrationNumber: String?,
    val nrsBusinessId: String?,
    val email: String?,
    val phone: String?,
    val address: String,
    val locationName: String,
    val locationAddress: String?,
)

data class BuyerBlock(
    val customerId: UUID?,
    val code: String?,
    val name: String,
    val type: String?,
    val email: String?,
    val phone: String?,
    val tin: String?,
    val businessDescription: String?,
    val address: String?,
    val stateCode: String?,
    val lgaCode: String?,
    val countryCode: String,
)

data class InvoiceTotals(
    val lineExtension: BigDecimal,
    val discount: BigDecimal,
    val vat: BigDecimal,
    val total: BigDecimal,
    val paid: BigDecimal,
    val credited: BigDecimal,
    val balance: BigDecimal,
    val pendingConfirmation: BigDecimal,
)

data class WaybillLink(val id: UUID, val ref: String, val date: LocalDate)

data class LinkedDocuments(
    val sourceType: String,
    val sourceRef: String,
    val proForma: String?,
    val waybills: List<WaybillLink>,
    /** Payments and credit notes arrive with slice 2; empty until then. */
    val payments: List<Map<String, Any?>>,
    val creditNotes: List<Map<String, Any?>>,
    val reissueOf: InvoiceLinkRef?,
    val replacedBy: InvoiceLinkRef?,
)

data class InvoiceLinkRef(val id: UUID, val number: String?)

data class RegulatoryBlock(
    val irn: String?,
    val cryptographicStampId: String?,
    val qrCode: String?,
    val accessPointSubmission: String,
    val nrsSubmissionStatus: String,
)

data class InvoiceDetail(
    val id: UUID,
    val number: String?,
    val status: String,
    val displayStatus: String,
    val invoiceTypeCode: String,
    val invoiceKind: String,
    val issueDate: LocalDate?,
    val issueTime: LocalTime?,
    val dueDate: LocalDate?,
    val paymentTermsDays: Int,
    val paymentTermsNote: String?,
    val daysOverdue: Int,
    val issuedBy: String?,
    val issuedAt: Instant?,
    val createdAt: Instant,
    val showroom: InvoiceLocation,
    val issuer: IssuerBlock?,
    val buyer: BuyerBlock,
    val lines: List<InvoiceLineDetail>,
    val totals: InvoiceTotals,
    val vat: InvoiceVat,
    val linked: LinkedDocuments,
    val delivery: InvoiceDelivery,
    val regulatory: RegulatoryBlock,
    val voidReason: String?,
    val voidedBy: String?,
    val voidedAt: Instant?,
)

data class InvoiceLocation(val id: UUID, val name: String?)

data class InvoiceVat(val enabled: Boolean, val rate: BigDecimal)

data class InvoiceDelivery(val status: String, val lastAttemptAt: Instant?, val recipient: String?)

/** A voided invoice (§4.9). */
data class VoidedInvoiceRow(
    val id: UUID,
    val number: String,
    val voidReason: String?,
    val customer: String,
    val issued: LocalDate?,
    val total: BigDecimal,
    val voidedBy: String?,
    val voidedAt: Instant?,
    val replacedById: UUID?,
    val replacedBy: String?,
)

/** Picker row: `number · customer · balance` (§9 lookups). */
data class InvoiceLookupRow(
    val id: UUID,
    val number: String,
    val customerId: UUID?,
    val customer: String,
    val issued: LocalDate?,
    val total: BigDecimal,
    val balance: BigDecimal,
    val displayStatus: String,
    val label: String,
)
