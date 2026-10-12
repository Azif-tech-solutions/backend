package com.zifiq.account.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

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

data class PettyCashTopUpRequest(
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:NotBlank val sourceBankAccountId: String,
    val remarks: String?,
)
