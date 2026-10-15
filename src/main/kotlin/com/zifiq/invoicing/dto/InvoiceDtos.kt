package com.zifiq.invoicing.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.common.paging.PageResult
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
    val customer: Ref?,
    val buyerName: String,
    val customerType: String?,
    val location: Ref,
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
    val stockItem: Ref,
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
    val customer: Ref?,
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
    val payments: List<InvoicePaymentView>,
    val creditNotes: List<CreditNoteRef>,
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
    val issuedBy: UserRef?,
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
    val voidedBy: UserRef?,
    val voidedAt: Instant?,
)

data class InvoiceLocation(val id: UUID, val name: String?)

data class InvoiceVat(val enabled: Boolean, val rate: BigDecimal)

/** [code] SENT, FAILED, NO_EMAIL or NOT_SENT (queued or never sent); [status] its label ("Queued" while the outbox holds it). */
data class InvoiceDelivery(val code: String, val status: String, val lastAttemptAt: Instant?, val recipient: String?, val attempts: Int)

/** A voided invoice (§4.9). */
data class VoidedInvoiceRow(
    val id: UUID,
    val number: String,
    val voidReason: String?,
    val customer: String,
    val issued: LocalDate?,
    val total: BigDecimal,
    val voidedBy: UserRef?,
    val voidedAt: Instant?,
    val replacedBy: Ref?,
)

/** Picker row: `number · customer · balance` (§9 lookups). */
data class InvoiceLookupRow(
    val id: UUID,
    val number: String,
    val customer: Ref?,
    val buyerName: String,
    val issued: LocalDate?,
    val total: BigDecimal,
    val balance: BigDecimal,
    val displayStatus: String,
    val label: String,
)

// ---- Payments (docs/08 §4.6) ----

data class RecordPaymentRequest(
    @field:NotNull @field:Pattern(regexp = "CASH|POS|FUND_TRANSFER|CHEQUE|WALLET|DEPOSIT", message = "must be CASH, POS, FUND_TRANSFER, CHEQUE, WALLET or DEPOSIT")
    val method: String?,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    /** When the money was received; defaults to now, never in the future. */
    val paidAt: Instant? = null,
    /** CASH: the till or vault taking it; POS: the till the card was taken at. */
    val cashAccountId: UUID? = null,
    /** FUND_TRANSFER / CHEQUE (required); POS when the till has no settlement account. */
    val receivingBankAccountId: UUID? = null,
    @field:Size(max = 40) val terminalRef: String? = null,
    @field:Size(max = 80) val payerReference: String? = null,
)

data class ReasonBody(@field:NotBlank @field:Size(min = 5, max = 300) val reason: String)

data class InvoicePaymentView(
    val id: UUID,
    val date: Instant,
    val method: String,
    val methodLabel: String,
    val amount: BigDecimal,
    val receipt: Ref,
    /** Confirmed, Pending confirmation, Declined or Voided. */
    val status: String,
    /** POS_CHECKOUT, RECORDED or REISSUE_TRANSFER. */
    val source: String,
    val reversalReason: String?,
)

data class RecordedPayment(
    val paymentId: UUID,
    val receipt: Ref,
    /** NOT_REQUIRED (cash, wallet) or PENDING (POS, transfer, cheque: Accounts confirms it). */
    val confirmationStatus: String,
    val invoice: InvoiceDetail,
)

// ---- Credit notes (docs/08 §4.5) ----

data class CreditNoteLineRequest(
    @field:NotNull val invoiceLineId: UUID?,
    /** Quantity-based credit at the line's own price; or [amount], a net value. Exactly one. */
    @field:DecimalMin(value = "0.001") val quantity: BigDecimal? = null,
    @field:DecimalMin(value = "0.01") val amount: BigDecimal? = null,
)

data class CreditNoteRequest(
    @field:NotNull @field:Pattern(regexp = "PRICING_ERROR|OVERCHARGE|DISCOUNT_GRANTED_AFTER_ISSUE|DUPLICATE_BILLING|GOODWILL|OTHER",
        message = "must be PRICING_ERROR, OVERCHARGE, DISCOUNT_GRANTED_AFTER_ISSUE, DUPLICATE_BILLING, GOODWILL or OTHER")
    val reason: String?,
    @field:NotBlank @field:Size(max = 300) val reasonNote: String,
    @field:NotEmpty @field:Valid val lines: List<CreditNoteLineRequest>,
)

data class CreditNoteRef(
    val id: UUID,
    val number: String,
    val issuedAt: Instant,
    val reason: String,
    val total: BigDecimal,
    val appliedToInvoice: BigDecimal,
    val excessRefunded: BigDecimal,
)

data class CreditNoteLineDetail(
    val id: UUID,
    val invoiceLineId: UUID,
    val lineNo: Int,
    val code: String,
    val name: String,
    val quantity: BigDecimal?,
    val amount: BigDecimal?,
    val unitPrice: BigDecimal,
    val vatRate: BigDecimal,
    val lineNet: BigDecimal,
    val vatAmount: BigDecimal,
    val lineTotal: BigDecimal,
)

data class CreditNoteDetail(
    val id: UUID,
    val number: String,
    val invoiceTypeCode: String,
    val invoice: InvoiceLinkRef,
    val invoiceIssueDate: LocalDate?,
    val customer: Ref?,
    val buyerName: String,
    val showroom: InvoiceLocation,
    val reason: String,
    val reasonNote: String,
    val lines: List<CreditNoteLineDetail>,
    val lineExtension: BigDecimal,
    val vat: BigDecimal,
    val total: BigDecimal,
    val appliedToInvoice: BigDecimal,
    /** Credited to the customer's wallet because it was more than the invoice's balance. */
    val excessRefunded: BigDecimal,
    val issuedBy: UserRef,
    val issuedAt: Instant,
)

data class CreditNoteRow(
    val id: UUID,
    val number: String,
    val invoice: Ref,
    val customer: Ref?,
    val buyerName: String,
    val showroom: String?,
    val reason: String,
    val reasonNote: String,
    val total: BigDecimal,
    val appliedToInvoice: BigDecimal,
    val excessRefunded: BigDecimal,
    val issuedBy: UserRef,
    val issuedAt: Instant,
)

// ---- Delivery (docs/08 §4.8) ----

data class DeliveryRequest(
    /** Default: the invoice's buyer e-mail; a different address is a redirect. */
    @field:Email @field:Size(max = 320) val recipientEmail: String? = null,
    @field:Pattern(regexp = "STANDARD|REMINDER|STATEMENT", message = "must be STANDARD, REMINDER or STATEMENT") val template: String? = null,
    @field:Size(max = 1000) val message: String? = null,
)

data class DeliveryView(
    val id: UUID,
    val invoice: Ref,
    val recipient: String,
    val template: String,
    val trigger: String,
    /** Queued, Sent or Failed (the outbox row's state). */
    val status: String,
    val requestedAt: Instant,
    val sentAt: Instant?,
    /** Null for deliveries the system sent on its own (on issue, overdue reminders). */
    val requestedBy: UserRef?,
)

data class DeliveryRow(
    val invoiceId: UUID,
    val number: String?,
    val issued: LocalDate?,
    val customer: String,
    val email: String?,
    val showroom: String?,
    val total: BigDecimal,
    val delivery: String,
    val deliveryCode: String,
    val lastAttemptAt: Instant?,
    val attempts: Int,
)

// ---- Receivables and debtors (docs/08 §4.9–4.10) ----

data class ReceivableRow(
    val id: UUID,
    val number: String,
    val issued: LocalDate?,
    val due: LocalDate?,
    val customer: Ref?,
    val buyerName: String,
    val customerType: String?,
    val showroom: String?,
    val total: BigDecimal,
    val paid: BigDecimal,
    val credited: BigDecimal,
    val balance: BigDecimal,
    /** Issued, Partially Paid or Overdue. */
    val status: String,
    val daysOverdue: Int,
    /** NOT_DUE, WITHIN_30, DAYS_31_60 or OVER_60 (days past due). */
    val ageingBucket: String,
)

data class DebtorRow(
    val customer: Ref,
    val phone: String?,
    val location: Ref?,
    val correspondent: Ref?,
    val openInvoices: Int,
    val outstanding: BigDecimal,
    val creditLimit: BigDecimal,
    val overLimit: Boolean,
    val oldestInvoiceDays: Int,
    /** WITHIN_30, DAYS_31_60 or OVER_60, on the oldest open invoice's issue date. */
    val ageing: String,
    val ageingLabel: String,
    val lastPaymentAt: Instant?,
)

data class DebtorAccount(
    val openInvoices: Int,
    val lastPaymentAt: Instant?,
    val totalInvoiced: BigDecimal,
    val totalPaid: BigDecimal,
    val totalCredited: BigDecimal,
    val outstanding: BigDecimal,
    val creditLimit: BigDecimal,
    val overLimit: Boolean,
)

data class DebtorTransaction(
    val invoice: Ref,
    val date: LocalDate?,
    val description: String,
    val value: BigDecimal,
    val paid: BigDecimal,
    val balance: BigDecimal,
    val dueDate: LocalDate?,
    val status: String,
)

data class DebtorDetail(
    val customer: DebtorRow,
    val account: DebtorAccount,
    val transactions: PageResult<DebtorTransaction>,
    /** Set when the customer is over their credit limit (rule I13; enforced at the till by Sales & POS). */
    val warning: String?,
)

/** The NRS invoice JSON this invoice would be submitted as, and what is still missing (docs/08 §4.12). Nothing is transmitted. */
data class NrsPreview(val ready: Boolean, val missing: List<String>, val invoice: Map<String, Any?>)
