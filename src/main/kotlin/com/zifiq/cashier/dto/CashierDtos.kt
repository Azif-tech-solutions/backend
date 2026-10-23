package com.zifiq.cashier.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---- Till sessions (docs/09 §4.1) ----

data class TillOpenRequest(
    @field:NotNull val cashAccountId: UUID?,
    @field:NotNull @field:DecimalMin("0.00") val openingFloat: BigDecimal?,
)

data class TillCloseRequest(
    @field:NotNull @field:DecimalMin("0.00") val countedCash: BigDecimal?,
    @field:Size(max = 300) val note: String? = null,
)

/** What the session has taken so far, by method; expected cash = float + net cash through the till. */
data class TillRunningFigures(
    val salesCount: Int,
    val cashTaken: BigDecimal,
    val cardTaken: BigDecimal,
    val transferTaken: BigDecimal,
    val walletTaken: BigDecimal,
    val depositTaken: BigDecimal,
    val onAccount: BigDecimal,
    val expectedCashNow: BigDecimal,
)

data class TillSessionView(
    val id: UUID,
    /** The till (a TILL cash account). */
    val till: Ref,
    val location: Ref,
    val cashier: UserRef,
    val status: String,
    val openedAt: Instant,
    val openingFloat: BigDecimal,
    val closedAt: Instant?,
    val closedBy: UserRef?,
    val countedCash: BigDecimal?,
    val expectedCash: BigDecimal?,
    val variance: BigDecimal?,
    val closeNote: String?,
    val forceClosed: Boolean,
    /** Open sessions only. */
    val running: TillRunningFigures?,
)

// ---- Catalogue (§4.2) ----

data class CatalogueItem(
    val stockItem: Ref,
    val code: String,
    val barcode: String?,
    val name: String,
    val category: String?,
    val unit: String,
    /** What this customer pays: the dealer price for an authorised dealer, else retail. */
    val price: BigDecimal,
    val retailPrice: BigDecimal,
    val dealerPrice: BigDecimal,
    val available: BigDecimal,
    val lowStock: Boolean,
)

// ---- Sales (§4.2) ----

data class SaleStartRequest(val customerId: UUID? = null)

data class SaleCustomerRequest(val customerId: UUID?, @field:NotNull val version: Int?)

data class SaleLineInput(
    @field:NotNull val stockItemId: UUID?,
    @field:NotNull @field:DecimalMin(value = "0.001") val quantity: BigDecimal?,
)

data class SaleLinesRequest(
    @field:NotNull val version: Int?,
    @field:Valid @field:Size(max = 200) val lines: List<SaleLineInput> = emptyList(),
)

data class SaleVersionRequest(@field:NotNull val version: Int?)

data class SalePaymentInput(
    /** CASH, POS, FUND_TRANSFER, WALLET or DEPOSIT. */
    @field:NotNull val method: String?,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    /** CASH: what the customer handed over (≥ amount); the difference is change. */
    val tendered: BigDecimal? = null,
    @field:Size(max = 40) val terminalRef: String? = null,
    /** Transfer reference or card authorisation code. */
    @field:Size(max = 80) val payerReference: String? = null,
    /** FUND_TRANSFER: the account the money went into. */
    val receivingBankAccountId: UUID? = null,
)

data class SaleBuyerInput(
    @field:NotNull @field:Size(min = 2, max = 200) val name: String?,
    @field:Size(max = 320) val email: String? = null,
    @field:Size(max = 40) val phone: String? = null,
    @field:Size(max = 40) val tin: String? = null,
    @field:Size(max = 300) val address: String? = null,
    @field:Size(max = 5) val stateCode: String? = null,
)

/** How much of a line is collected now, and from which area; the rest is reserved for later waybills. */
data class SaleSupplyLine(
    @field:NotNull val lineNo: Int?,
    @field:NotNull @field:DecimalMin("0.000") val supplyNow: BigDecimal?,
    val fromLocationId: UUID? = null,
)

data class SaleCompleteRequest(
    @field:NotNull val version: Int?,
    @field:Valid @field:Size(max = 10) val payments: List<SalePaymentInput> = emptyList(),
    @field:DecimalMin("0.00") val onAccount: BigDecimal? = null,
    /** Per line, how much goes now; anything less than the line quantity is reserved for a later waybill. Default: everything now. */
    @field:Valid val supply: List<SaleSupplyLine>? = null,
    /** Walk-in only: issue an invoice to these buyer details (a registered customer always gets one). */
    @field:Valid val invoiceBuyer: SaleBuyerInput? = null,
    @field:Size(max = 300) val creditOverrideReason: String? = null,
    /** Default area the goods leave from when a line doesn't name one; else the showroom's selling area. */
    val fromLocationId: UUID? = null,
)

data class SaleVoidRequest(@field:NotNull @field:Size(min = 10, max = 300) val reason: String?)

data class SaleInvoiceRequest(@field:Valid @field:NotNull val buyer: SaleBuyerInput?)

data class SaleCustomerView(
    val customer: Ref,
    val type: String,
    val category: String?,
    val correspondent: Ref?,
    val walletBalance: BigDecimal,
    val depositBalance: BigDecimal,
    val creditLimit: BigDecimal,
    val outstanding: BigDecimal,
    val creditAvailable: BigDecimal,
    val overLimit: Boolean,
)

data class SaleLineView(
    val lineNo: Int,
    val stockItem: Ref,
    val code: String,
    val name: String,
    val unit: String,
    val category: String?,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val priceBasis: String,
    val lineDiscount: BigDecimal,
    val instantDiscountShare: BigDecimal,
    val lineNet: BigDecimal,
    val vatRate: BigDecimal,
    val vatAmount: BigDecimal,
    val lineTotal: BigDecimal,
    /** Open sales: what the location has to sell now, and whether this line needs more. */
    val available: BigDecimal?,
    val insufficient: Boolean,
    /** How much of the line has been supplied (collected at the till + released by waybills), and what is still owed. */
    val supplied: BigDecimal,
    val outstanding: BigDecimal,
)

data class SaleTotalsView(
    val gross: BigDecimal,
    val lineDiscounts: BigDecimal,
    val instantDiscount: BigDecimal,
    val net: BigDecimal,
    val vatEnabled: Boolean,
    val vatRate: BigDecimal,
    val vat: BigDecimal,
    val total: BigDecimal,
)

data class SalePaymentView(
    val method: String,
    val methodLabel: String,
    val amount: BigDecimal,
    val receipt: Ref,
    val receiptRef: String,
    /** NOT_REQUIRED, PENDING (card/transfer awaiting Accounts), APPROVED or DECLINED. */
    val confirmationStatus: String,
    val terminalRef: String?,
    val payerReference: String?,
)

data class SaleInstantDiscount(val id: UUID, val ref: String, val amount: BigDecimal, val pctOfBase: BigDecimal, val reason: String, val status: String)

data class SaleView(
    val id: UUID,
    val ref: String?,
    val status: String,
    val version: Int,
    val channel: String,
    val till: Ref?,
    val location: Ref,
    val cashier: UserRef,
    /** Null for a walk-in; see [customerName]. */
    val customer: SaleCustomerView?,
    val customerName: String,
    val priceBasis: String,
    val lines: List<SaleLineView>,
    val totals: SaleTotalsView,
    val payments: List<SalePaymentView>,
    val paidNow: BigDecimal,
    val onAccount: BigDecimal,
    val cashTendered: BigDecimal?,
    val changeGiven: BigDecimal?,
    /** Open sales: the payment methods this customer can use. */
    val allowedMethods: List<String>,
    /** The live Instant Discount on this sale (applied or awaiting approval), or null. */
    val instantDiscount: SaleInstantDiscount?,
    val invoice: Ref?,
    val invoiceNumber: String?,
    val correspondent: Ref?,
    /** NOT_APPLICABLE (all collected at the till), NOT_SUPPLIED, PARTIALLY_SUPPLIED or FULLY_SUPPLIED. */
    val supplyStatus: String,
    val saleDate: LocalDate?,
    val startedAt: Instant,
    val completedAt: Instant?,
    val creditOverrideReason: String?,
    val creditOverrideBy: UserRef?,
    val voidedAt: Instant?,
    val voidedBy: UserRef?,
    val voidReason: String?,
    /** Completed sales: until when it can still be voided. */
    val voidableUntil: Instant?,
)

data class SaleCompletion(
    val sale: SaleView,
    val invoice: Ref?,
    val receiptUrl: String,
    val changeGiven: BigDecimal?,
)

data class SaleRow(
    val id: UUID,
    val ref: String?,
    val status: String,
    val channel: String,
    val date: LocalDate?,
    val completedAt: Instant?,
    val location: Ref,
    val till: Ref?,
    val cashier: UserRef,
    val customer: Ref?,
    val customerName: String,
    val customerType: String?,
    val items: Int,
    val units: BigDecimal,
    val net: BigDecimal,
    val vat: BigDecimal,
    val total: BigDecimal,
    /** Payment method labels, e.g. ["Cash", "POS (card)"]. */
    val methods: List<String>,
    val onAccount: BigDecimal,
    val invoice: Ref?,
    val invoiceNumber: String?,
)

// ---- Deliver-later: supply, customer waybills (docs/09 §4.5) ----

/** The outstanding position of a sale or invoice, for releasing goods against it. */
data class SupplyPosition(
    val sale: Ref,
    val saleRef: String,
    val invoiceNumber: String?,
    val customer: Ref?,
    val customerName: String,
    val customerType: String?,
    val address: String?,
    val contact: String?,
    val lines: List<SupplyPositionLine>,
    val totals: SupplyTotals,
    val history: List<SupplyHistoryRow>,
    val waybillCount: Int,
    val fullySupplied: Boolean,
)

data class SupplyPositionLine(
    val saleLineId: UUID,
    val stockItem: Ref,
    val product: String,
    val unit: String,
    val purchased: BigDecimal,
    val supplied: BigDecimal,
    val outstanding: BigDecimal,
)

data class SupplyTotals(val purchased: BigDecimal, val supplied: BigDecimal, val outstanding: BigDecimal, val progressPct: Int)

data class SupplyHistoryRow(
    val waybill: Ref,
    val waybillRef: String,
    val date: LocalDate,
    val product: String,
    val quantity: BigDecimal,
    val driver: String?,
    val collectedBy: String?,
    val receivedBy: String?,
    val status: String,
)

data class WaybillLineInput(
    @field:NotNull val saleLineId: UUID?,
    @field:NotNull @field:DecimalMin("0.001") val quantity: BigDecimal?,
    /** The area the goods leave from; default the sale line's own, else the selling area. */
    val fromLocationId: UUID? = null,
)

data class WaybillRequest(
    @field:NotNull val saleId: UUID?,
    /** DELIVERY (needs driverId) or COLLECTION (needs collectedBy). */
    @field:NotNull val releaseMethod: String?,
    val releaseDate: LocalDate? = null,
    val driverId: UUID? = null,
    @field:Size(max = 30) val vehicleNumber: String? = null,
    @field:Size(max = 160) val collectedBy: String? = null,
    @field:Size(max = 300) val deliveryAddress: String? = null,
    @field:Valid @field:NotEmpty val lines: List<WaybillLineInput> = emptyList(),
)

data class WaybillAcknowledgeRequest(
    @field:NotNull @field:Size(min = 2, max = 160) val receivedBy: String?,
    val receivedAt: java.time.Instant? = null,
    @field:Size(max = 300) val note: String? = null,
)

data class WaybillCancelRequest(@field:NotNull @field:Size(min = 5, max = 300) val reason: String?)

data class WaybillLineView(
    val stockItem: Ref,
    val product: String,
    val unit: String,
    val quantity: BigDecimal,
    val from: Ref,
    val stillOutstanding: BigDecimal,
)

data class WaybillView(
    val id: UUID,
    val ref: String,
    val sale: Ref,
    val saleRef: String?,
    val invoiceNumber: String?,
    val location: Ref,
    val customerName: String,
    val releaseMethod: String,
    val releaseDate: LocalDate,
    val driver: Ref?,
    val driverName: String?,
    val vehicleNumber: String?,
    val collectedBy: String?,
    val deliveryAddress: String?,
    val status: String,
    val receivedBy: String?,
    val receivedAt: java.time.Instant?,
    val acknowledgementNote: String?,
    val cancelledReason: String?,
    val issuedBy: UserRef,
    val lines: List<WaybillLineView>,
)

data class WaybillRow(
    val id: UUID,
    val ref: String,
    val sale: Ref,
    val saleRef: String?,
    val invoiceNumber: String?,
    val location: Ref,
    val customerName: String,
    val releaseMethod: String,
    val releaseDate: LocalDate,
    val driverName: String?,
    val collectedBy: String?,
    val units: BigDecimal,
    val status: String,
)

// ---- Pending Supply report (docs/09 §4.6) ----

data class PendingSupplyRow(
    val sale: Ref,
    val saleRef: String,
    val invoice: Ref?,
    val invoiceNumber: String?,
    val customer: Ref?,
    val customerName: String,
    val date: LocalDate?,
    val lines: Int,
    val ordered: BigDecimal,
    val supplied: BigDecimal,
    val outstanding: BigDecimal,
    val progressPct: Int,
    val deliveries: Int,
    val lastSupply: LocalDate?,
    val status: String,
)


// ---- Instant Discount (docs/09 §4.3) ----

data class InstantDiscountRequest(
    /** PERCENT or AMOUNT. */
    @field:NotNull val mode: String?,
    @field:NotNull @field:DecimalMin("0.0001") val value: BigDecimal?,
    /** VOLUME, BULK, LOYALTY, GOODWILL or CLEARANCE. */
    @field:NotNull val reason: String?,
    @field:Size(max = 300) val note: String? = null,
    @field:NotNull val version: Int?,
)

data class RejectRequest(@field:NotNull @field:Size(min = 3, max = 300) val reason: String?)

data class InstantDiscountView(
    val id: UUID,
    val ref: String,
    val sale: Ref,
    val mode: String,
    val value: BigDecimal,
    val discountAmount: BigDecimal,
    val baseAmount: BigDecimal,
    val pctOfBase: BigDecimal,
    val reason: String,
    val note: String?,
    val status: String,
    val appliedBy: UserRef,
    val approvedBy: UserRef?,
    val rejectionReason: String?,
)

data class InstantDiscountRow(
    val id: UUID,
    val ref: String,
    val sale: Ref,
    val discountAmount: BigDecimal,
    val pctOfBase: BigDecimal,
    val reason: String,
    val status: String,
    val appliedBy: UserRef,
    val approvedBy: UserRef?,
    val createdAt: java.time.Instant,
)

// ---- Pro-forma invoices (docs/09 §4.4) ----

data class ProspectInput(
    @field:NotNull @field:Size(min = 2, max = 200) val name: String?,
    @field:Size(max = 300) val address: String? = null,
    @field:Size(max = 200) val contact: String? = null,
    @field:Size(max = 320) val email: String? = null,
)

data class ProFormaLineInput(
    @field:NotNull val stockItemId: UUID?,
    @field:NotNull @field:DecimalMin("0.001") val quantity: BigDecimal?,
    @field:DecimalMin("0.00") val lineDiscount: BigDecimal? = null,
)

data class ProFormaRequest(
    @field:NotNull val locationId: UUID?,
    val customerId: UUID? = null,
    @field:Valid val prospect: ProspectInput? = null,
    val issueDate: LocalDate? = null,
    val validUntil: LocalDate? = null,
    @field:Size(max = 300) val paymentTerms: String? = null,
    @field:Size(max = 300) val deliveryTerms: String? = null,
    @field:Size(max = 500) val note: String? = null,
    @field:Valid @field:NotEmpty val lines: List<ProFormaLineInput> = emptyList(),
)

data class ProFormaConvertRequest(
    /** ISSUED (issue the invoice now) or DRAFT (Accounts issues it later). */
    @field:NotNull val issueAs: String?,
    val paymentTermsDays: Int? = null,
    @field:Size(max = 300) val paymentTermsNote: String? = null,
    @field:Size(max = 300) val creditOverrideReason: String? = null,
)

data class ProFormaLineView(
    val lineNo: Int,
    val stockItem: Ref,
    val name: String,
    val unit: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineDiscount: BigDecimal,
    val lineNet: BigDecimal,
    val vatRate: BigDecimal,
    val vatAmount: BigDecimal,
    val lineTotal: BigDecimal,
    /** When composing: whether the location has enough stock now (a pro-forma commits none). */
    val availabilityWarning: Boolean,
)

data class ProFormaTotals(
    val gross: BigDecimal,
    val discount: BigDecimal,
    val net: BigDecimal,
    val vatEnabled: Boolean,
    val vatRate: BigDecimal,
    val vat: BigDecimal,
    val total: BigDecimal,
)

data class ProFormaView(
    val id: UUID,
    val ref: String,
    val status: String,
    val location: Ref,
    val customer: Ref?,
    val customerName: String,
    val customerType: String?,
    val prospect: ProspectInput?,
    val priceBasis: String,
    val issueDate: LocalDate,
    val validUntil: LocalDate,
    val paymentTerms: String?,
    val deliveryTerms: String?,
    val note: String?,
    val lines: List<ProFormaLineView>,
    val totals: ProFormaTotals,
    val preparedBy: UserRef,
    val declineReason: String?,
    val convertedSale: Ref?,
)

data class ProFormaRow(
    val id: UUID,
    val ref: String,
    val status: String,
    val location: Ref,
    val customer: Ref?,
    val customerName: String,
    val date: LocalDate,
    val validUntil: LocalDate,
    val items: Int,
    val value: BigDecimal,
    val preparedBy: UserRef,
    val convertedSale: Ref?,
)

data class LoadProFormaRequest(@field:NotNull val proFormaId: UUID?, @field:NotNull val version: Int?)

// ---- Reports (docs/09 §4.6) ----

data class SalesDailyRow(
    val date: LocalDate,
    val showroom: Ref,
    val transactions: Long,
    val units: BigDecimal,
    val gross: BigDecimal,
    val discount: BigDecimal,
    val net: BigDecimal,
    val vat: BigDecimal,
    val avgSale: BigDecimal,
)

data class SalesReportStats(
    val netSales: BigDecimal,
    val transactions: Long,
    val units: BigDecimal,
    val averageTransaction: BigDecimal,
    val bestDay: LocalDate?,
)

data class SalesReport(
    val rows: List<SalesDailyRow>,
    val totals: SalesDailyRow?,
    val stats: SalesReportStats,
)

data class CustomerActivityRow(
    val date: LocalDate?,
    val sale: Ref,
    val saleRef: String,
    val invoiceNumber: String?,
    val customer: Ref?,
    val customerName: String,
    val items: Int,
    val units: BigDecimal,
    val value: BigDecimal,
    val cash: BigDecimal,
    val pos: BigDecimal,
    val transfer: BigDecimal,
    val wallet: BigDecimal,
    val deposit: BigDecimal,
    val onAccount: BigDecimal,
    val waybills: List<String>,
)

data class CustomerActivityReport(
    val rows: List<CustomerActivityRow>,
    val transactions: Long,
    val units: BigDecimal,
    val value: BigDecimal,
    val averageTransaction: BigDecimal,
)
