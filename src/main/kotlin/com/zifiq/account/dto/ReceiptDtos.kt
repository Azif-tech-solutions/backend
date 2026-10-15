package com.zifiq.account.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---- Receipts and POS / Fund Transfer Confirmation (docs/07 §4.10) ----

data class ReceiptRow(
    val id: UUID,
    val ref: String,
    val kind: String,
    val receivedAt: Instant,
    val location: Ref?,
    val cashAccount: Ref?,
    val cashier: UserRef,
    val customer: Ref?,
    val customerName: String?,
    val channel: String,
    val amount: BigDecimal,
    val sourceModule: String,
    val sourceType: String,
    val sourceRef: String,
    val incomeCategory: String?,
    val description: String?,
    val receivingBankAccount: Ref?,
    val terminalRef: String?,
    val payerReference: String?,
    val confirmationStatus: String,
    val reviewedBy: UserRef?,
    val reviewedAt: Instant?,
    val declineNote: String?,
    val status: String,
    val voidReason: String?,
)

data class DeclineRequest(@field:NotBlank @field:Size(max = 300) val note: String)

data class ConfirmationStats(val pendingCount: Long, val pendingValue: BigDecimal, val approvedToday: Long, val declinedToday: Long)

// ---- Other income (docs/07 §4.18) ----

data class OtherIncomeRequest(
    val locationId: UUID? = null,
    @field:NotNull @field:Pattern(regexp = "BY_PRODUCT_SALES|SCRAP_SALES|RENTAL|INTEREST|OTHER", message = "must be BY_PRODUCT_SALES, SCRAP_SALES, RENTAL, INTEREST or OTHER")
    val incomeCategory: String?,
    @field:NotNull @field:Pattern(regexp = "CASH|POS|FUND_TRANSFER|CHEQUE", message = "must be CASH, POS, FUND_TRANSFER or CHEQUE") val channel: String?,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    val cashAccountId: UUID? = null,
    val receivingBankAccountId: UUID? = null,
    @field:NotBlank @field:Size(max = 200) val payerName: String,
    @field:Size(max = 80) val payerReference: String? = null,
    @field:NotBlank @field:Size(max = 300) val description: String,
    @field:NotNull val receivedDate: LocalDate?,
)

// ---- Wallet / deposit funding (docs/07 §4.12) ----

data class WalletFundingRequest(
    @field:NotNull val customerId: UUID?,
    @field:NotNull @field:Pattern(regexp = "WALLET|DEPOSIT", message = "must be WALLET or DEPOSIT") val balanceType: String?,
    @field:NotNull @field:Pattern(regexp = "CASH|POS|FUND_TRANSFER|CHEQUE", message = "must be CASH, POS, FUND_TRANSFER or CHEQUE") val channel: String?,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    val locationId: UUID? = null,
    /** CASH and POS: the till or vault. */
    val cashAccountId: UUID? = null,
    /** FUND_TRANSFER and CHEQUE: where the money lands. */
    val receivingBankAccountId: UUID? = null,
    @field:Size(max = 40) val terminalRef: String? = null,
    @field:Size(max = 80) val payerReference: String? = null,
)

data class WalletFundingRow(
    val id: UUID,
    val ref: String,
    val date: Instant,
    val customer: Ref,
    val customerCode: String,
    val customerName: String,
    val balanceType: String,
    val channel: String,
    val amount: BigDecimal,
    val location: Ref?,
    val receipt: Ref,
    val confirmationStatus: String?,
    val status: String,
    val createdBy: UserRef,
)

// ---- Wallet withdrawal and deposit refund (docs/07 §4.13) ----

data class WalletWithdrawalRequest(
    @field:NotNull val customerId: UUID?,
    @field:NotNull @field:Pattern(regexp = "WALLET|DEPOSIT", message = "must be WALLET or DEPOSIT") val balanceType: String?,
    @field:NotNull @field:Pattern(regexp = "DEALER_REQUEST|EXCESS_COMMISSION_RECOVERY|OVER_CREDIT_CORRECTION|DEPOSIT_REFUND|INVOICE_SETTLEMENT",
        message = "must be DEALER_REQUEST, EXCESS_COMMISSION_RECOVERY, OVER_CREDIT_CORRECTION or DEPOSIT_REFUND") val reason: String?,
    @field:NotNull @field:DecimalMin(value = "0.01") val amount: BigDecimal?,
    @field:NotNull @field:Pattern(regexp = "CASH|BANK|NONE", message = "must be CASH, BANK or NONE") val payoutKind: String?,
    val payoutBankAccountId: UUID? = null,
    val payoutCashAccountId: UUID? = null,
    @field:Size(max = 120) val paidVia: String? = null,
    val otpChallengeId: UUID? = null,
    val authorisedByUserId: UUID? = null,
    @field:NotNull val withdrawalDate: LocalDate?,
    val locationId: UUID? = null,
    @field:Size(max = 300) val note: String? = null,
)

data class AuditStatusRequest(
    @field:NotNull @field:Pattern(regexp = "UNDER_REVIEW|COMPLETED", message = "must be UNDER_REVIEW or COMPLETED") val auditStatus: String?,
)

data class WalletWithdrawalRow(
    val id: UUID,
    val ref: String,
    val date: LocalDate,
    val customer: Ref,
    val customerCode: String,
    val customerName: String,
    val balanceType: String,
    val initiatedBy: String,
    val reason: String,
    val balanceBefore: BigDecimal,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val payoutKind: String,
    val payoutAccount: String?,
    val paidVia: String?,
    /** "Verified" when an OTP authorised it, else "Not required". */
    val otp: String,
    val authorisedBy: UserRef?,
    val processedBy: UserRef,
    val auditStatus: String,
    val reversalReason: String?,
    val note: String?,
    val createdAt: Instant,
)

// ---- Tenderable (docs/07 §4.11) ----

data class TenderablePayableRow(
    val date: LocalDate,
    val cashier: UserRef,
    val ftf: BigDecimal,
    val cash: BigDecimal,
    val pos: BigDecimal,
    val wallet: BigDecimal,
    val deposit: BigDecimal,
    val refund: BigDecimal,
    /** ftf + cash + pos + wallet + deposit − refund, excluding pending and declined receipts. */
    val total: BigDecimal,
    val pendingConfirmation: BigDecimal,
    val declined: BigDecimal,
)

data class TenderableGeneralRow(
    /** Null on the report's totals row (all cashiers). */
    val cashier: UserRef?,
    val daysActive: Int,
    val ftf: BigDecimal,
    val cash: BigDecimal,
    val pos: BigDecimal,
    val wallet: BigDecimal,
    val deposit: BigDecimal,
    val refund: BigDecimal,
    val total: BigDecimal,
)

data class TenderableGeneralReport(val period: Map<String, LocalDate?>, val rows: List<TenderableGeneralRow>, val totals: TenderableGeneralRow?)

data class TenderableWalletFundRow(
    val ref: String,
    val date: Instant,
    val customer: Ref,
    val dealer: String,
    /** Fund transfer, Cash deposit, POS, Cheque, or Return credit. */
    val method: String,
    val bank: String?,
    val amount: BigDecimal,
    val confirmedBy: UserRef?,
    /** Confirmed, Awaiting confirmation, Declined. */
    val status: String,
)

// ---- VAT Control per location (docs/07 §4.17) ----

data class ScheduledVat(val rate: BigDecimal, val enabled: Boolean, val effectiveFrom: LocalDate)

data class LocationVatRow(
    val location: Ref,
    val registration: String?,
    val rate: BigDecimal,
    val enabled: Boolean,
    val effectiveFrom: LocalDate?,
    val updatedBy: UserRef?,
    val scheduled: ScheduledVat?,
)

data class LocationVatList(val disabledCount: Int, val locations: List<LocationVatRow>)

data class LocationVatRequest(
    @field:NotNull val vatEnabled: Boolean?,
    @field:NotNull @field:DecimalMin(value = "0") @field:DecimalMax(value = "100") val vatRate: BigDecimal?,
    @field:Size(max = 60) val vatRegistrationNumber: String? = null,
    @field:NotNull val effectiveFrom: LocalDate?,
    @field:Size(max = 300) val note: String? = null,
)

data class LocationVatHistoryRow(
    val id: UUID,
    val enabled: Boolean,
    val rate: BigDecimal,
    val registration: String?,
    val effectiveFrom: LocalDate,
    val note: String?,
    val updatedBy: UserRef,
    val createdAt: Instant,
    /** In force today, scheduled for later, or superseded. */
    val state: String,
)
