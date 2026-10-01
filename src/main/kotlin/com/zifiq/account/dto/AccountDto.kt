package com.zifiq.account.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CashFlowEntry(
    val id: UUID,
    val type: String, // INFLOW or OUTFLOW
    val category: String,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val timestamp: Instant,
)

data class BankAccountStatement(
    val accountId: UUID,
    val bankName: String,
    val accountNumber: String,
    val currency: String,
    val currentBalance: BigDecimal,
    val transactions: List<BankTransactionSummary>,
)

data class BankTransactionSummary(
    val id: UUID,
    val reference: String,
    val amount: BigDecimal,
    val transactionType: String,
    val timestamp: Instant,
)

data class PettyCashStatement(
    val accountId: UUID,
    val availableBalance: BigDecimal,
    val floatLimit: BigDecimal,
    val currency: String,
)

data class OperatingCostPaymentRequest(
    @field:NotBlank val category: String,
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:NotBlank val paymentMethod: String,
    val description: String?,
)

data class PaymentSummary(
    val id: UUID,
    val category: String,
    val amount: BigDecimal,
    val status: String,
    val referenceCode: String,
)

data class BankAccountRegistrationRequest(
    @field:NotBlank val bankName: String,
    @field:NotBlank val accountNumber: String,
    @field:NotBlank val accountName: String,
    @field:NotBlank val currency: String,
)

data class BankAccountSummary(
    val id: UUID,
    val bankName: String,
    val accountNumber: String,
    val accountName: String,
    val currency: String,
)

data class PettyCashTopUpRequest(
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:NotBlank val sourceBankAccountId: String,
    val remarks: String?,
)
