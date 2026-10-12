package com.zifiq.account.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Body of every reverse/reject action that needs a reason. */
data class ReasonRequest(@field:NotBlank @field:Size(max = 300) val reason: String)

// ---- Petty cash (docs/07 §4.6–4.8) ----

data class PettyCashFundingRequest(
    @field:NotNull val pettyCashAccountId: UUID?,
    @field:NotNull @field:Pattern(regexp = "BANK|CASH", message = "must be BANK or CASH") val sourceKind: String?,
    val sourceBankAccountId: UUID? = null,
    val sourceCashAccountId: UUID? = null,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    @field:NotNull val fundingDate: LocalDate?,
    @field:Size(max = 80) val transferOrChequeReference: String? = null,
    @field:Size(max = 160) val authorisedBy: String? = null,
    @field:Size(max = 300) val description: String? = null,
)

data class PettyCashFundingRow(
    val id: UUID,
    val ref: String,
    val date: LocalDate,
    val pettyCashAccountId: UUID,
    val pettyCashAccountCode: String,
    val sourceKind: String,
    val sourceName: String,
    val amount: BigDecimal,
    val transferOrChequeReference: String?,
    val authorisedBy: String?,
    val description: String?,
    val fundedBy: String?,
    val status: String,
    val reversalReason: String?,
    val createdAt: Instant,
)

data class PettyCashPaymentRequest(
    @field:NotNull val pettyCashAccountId: UUID?,
    @field:NotNull val expenseCategoryId: UUID?,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    @field:NotBlank @field:Size(max = 160) val paidTo: String,
    @field:NotNull val paymentDate: LocalDate?,
    @field:Size(max = 160) val approvedBy: String? = null,
    @field:Size(max = 300) val description: String? = null,
)

data class PettyCashPaymentView(
    val id: UUID,
    val ref: String,
    val date: LocalDate,
    val pettyCashAccountId: UUID,
    val pettyCashAccountCode: String,
    val expenseCategoryId: UUID,
    val category: String,
    val amount: BigDecimal,
    val paidTo: String,
    val approvedBy: String?,
    val description: String?,
    val status: String,
    val reversalReason: String?,
    val balanceAfter: BigDecimal,
    val createdAt: Instant,
)

/** Petty Cash Payment History: petty cash payments and running costs paid from petty cash. */
data class PettyCashHistoryRow(
    val sn: Long,
    val id: UUID,
    val date: LocalDate,
    val ref: String,
    val description: String,
    val category: String?,
    val paidTo: String?,
    val approvedBy: String?,
    val amount: BigDecimal,
    /** "Petty cash payment" or "Running cost". */
    val source: String,
    val status: String?,
    val pettyCashAccountCode: String,
)

data class PettyCashLedgerLine(
    val date: LocalDate,
    val ref: String,
    val description: String,
    val category: String?,
    /** Funding, Payment, Running cost, Allocation, Reversal, Count adjustment, Opening. */
    val direction: String,
    val `in`: BigDecimal,
    val out: BigDecimal,
    val balance: BigDecimal,
    val accountCode: String,
)

data class FundingEntry(val ref: String, val date: LocalDate, val amount: BigDecimal)

data class CategoryUtilisation(val category: String, val count: Long, val amount: BigDecimal)

data class PettyCashStatementSection(
    val openingBalance: BigDecimal,
    val entries: List<FundingEntry>,
    val totalAvailable: BigDecimal,
)

data class PettyCashStatementSummary(
    val funding: PettyCashStatementSection,
    val utilisationByCategory: List<CategoryUtilisation>,
    val totalUtilised: BigDecimal,
    val closingBalance: BigDecimal,
    val utilisedPct: BigDecimal,
)

data class PettyCashStatementView(
    val accounts: List<AccountBrief>,
    val period: Map<String, LocalDate?>,
    val ledger: List<PettyCashLedgerLine>,
    val statement: PettyCashStatementSummary,
)

data class AccountBrief(val id: UUID, val code: String, val name: String, val locationName: String?)

data class PettyCashAllocationRequest(
    @field:NotNull @field:Pattern(regexp = "BANK|CASH", message = "must be BANK or CASH") val sourceKind: String?,
    val sourceBankAccountId: UUID? = null,
    val sourceCashAccountId: UUID? = null,
    @field:NotNull val destinationPettyCashAccountId: UUID?,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    @field:NotBlank @field:Size(max = 300) val purpose: String,
)

data class AllocationRejectRequest(@field:NotBlank @field:Size(max = 300) val note: String)

data class PettyCashAllocationRow(
    val id: UUID,
    val ref: String,
    val date: Instant,
    val sourceName: String,
    val destinationLocationId: UUID,
    val branch: String?,
    val destinationAccountCode: String,
    val purpose: String,
    val sentBy: String?,
    val amount: BigDecimal,
    val status: String,
    val respondedBy: String?,
    val respondedOn: Instant?,
    val note: String?,
)

// ---- Payment / Running Cost (docs/07 §4.9) ----

data class RunningCostRequest(
    val locationId: UUID? = null,
    @field:NotNull val expenseCategoryId: UUID?,
    @field:NotBlank @field:Size(max = 160) val payee: String,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    @field:NotNull @field:Pattern(regexp = "PETTY_CASH|CASH|BANK_TRANSFER|CHEQUE", message = "must be PETTY_CASH, CASH, BANK_TRANSFER or CHEQUE") val paymentMethod: String?,
    val payingCashAccountId: UUID? = null,
    val payingBankAccountId: UUID? = null,
    @field:Size(max = 30) val chequeNumber: String? = null,
    @field:Size(max = 80) val invoiceOrReceiptNo: String? = null,
    /** VAT included in amount, per the supplier's VAT invoice; needs supplierTin. */
    @field:DecimalMin(value = "0") val inputVatAmount: BigDecimal? = null,
    @field:Size(max = 40) val supplierTin: String? = null,
    @field:NotNull val paymentDate: LocalDate?,
    @field:Size(max = 300) val description: String? = null,
    /** false = save as a draft (no reference, no checks); true = submit for approval. */
    val submit: Boolean = false,
)

data class RunningCostRejectRequest(@field:NotBlank @field:Size(max = 300) val rejectionReason: String)

data class RunningCostView(
    val id: UUID,
    val ref: String?,
    val date: LocalDate,
    val description: String?,
    val expenseCategoryId: UUID,
    val category: String?,
    val payee: String,
    val method: String,
    val payingAccountId: UUID,
    val payingAccount: String?,
    val amount: BigDecimal,
    val inputVatAmount: BigDecimal,
    val supplierTin: String?,
    val invoiceOrReceiptNo: String?,
    val chequeNumber: String?,
    val locationId: UUID?,
    val locationName: String?,
    val status: String,
    val exceedsMonthlyCap: Boolean,
    val submittedBy: String?,
    val submittedAt: Instant?,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val rejectionReason: String?,
    val reversalReason: String?,
    val createdAt: Instant,
)

// ---- Funds Awaiting (docs/07 §4.14) ----

data class FundDisbursementRequest(
    @field:NotNull @field:Pattern(regexp = "BANK_TRANSFER|CASH", message = "must be BANK_TRANSFER or CASH") val channel: String?,
    val sourceBankAccountId: UUID? = null,
    val sourceCashAccountId: UUID? = null,
    @field:NotNull val destinationLocationId: UUID?,
    val destinationBankAccountId: UUID? = null,
    val destinationCashAccountId: UUID? = null,
    @field:NotBlank @field:Size(max = 300) val purpose: String,
    @field:NotNull @field:DecimalMin(value = "0.01") val sentAmount: BigDecimal?,
)

data class FundConfirmRequest(
    @field:NotNull @field:DecimalMin(value = "0") val receivedAmount: BigDecimal?,
    @field:NotNull val dateReceived: LocalDate?,
    @field:Size(max = 80) val bankCreditReference: String? = null,
    @field:Size(max = 300) val shortfallReason: String? = null,
)

data class FundDisbursementRow(
    val id: UUID,
    val ref: String,
    val date: Instant,
    val destinationLocationId: UUID,
    val branch: String?,
    val purpose: String,
    val channel: String,
    val sourceAccount: String?,
    val destinationAccount: String?,
    val sentBy: String?,
    val sent: BigDecimal,
    val received: BigDecimal?,
    val shortfall: BigDecimal?,
    val shortfallReason: String?,
    val status: String,
    val confirmedBy: String?,
    val dateReceived: LocalDate?,
    val rejectionReason: String?,
)

// ---- Cash lodgements (docs/07 §4.15) ----

data class CashLodgementRequest(
    @field:NotNull val locationId: UUID?,
    @field:NotNull val cashAccountId: UUID?,
    @field:NotNull val depositDate: LocalDate?,
    @field:NotNull @field:DecimalMin(value = "0") val takings: BigDecimal?,
    @field:NotNull @field:DecimalMin(value = "0.01") val banked: BigDecimal?,
    @field:NotNull val bankAccountId: UUID?,
    @field:Size(max = 60) val tellerReference: String? = null,
)

data class CashLodgementRow(
    val id: UUID,
    val ref: String,
    val date: LocalDate,
    val locationId: UUID,
    val showroom: String?,
    val cashAccountCode: String?,
    val takings: BigDecimal,
    val banked: BigDecimal,
    val retained: BigDecimal,
    val bank: String?,
    val accountNumberMasked: String?,
    val depositedBy: String?,
    val teller: String?,
    val status: String,
    val confirmedBy: String?,
    val confirmedAt: Instant?,
    val rejectionReason: String?,
)
