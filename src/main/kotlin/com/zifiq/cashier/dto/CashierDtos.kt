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

data class SaleCompleteRequest(
    @field:NotNull val version: Int?,
    @field:Valid @field:Size(max = 10) val payments: List<SalePaymentInput> = emptyList(),
    @field:DecimalMin("0.00") val onAccount: BigDecimal? = null,
    /** Walk-in only: issue an invoice to these buyer details (a registered customer always gets one). */
    @field:Valid val invoiceBuyer: SaleBuyerInput? = null,
    @field:Size(max = 300) val creditOverrideReason: String? = null,
    /** The storage area the goods leave from; default the showroom's selling area (else the showroom itself). */
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
    val invoice: Ref?,
    val invoiceNumber: String?,
    val correspondent: Ref?,
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
