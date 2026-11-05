package com.zifiq.communication.dto

import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PayCommissionRequest(
    @field:NotNull val runId: UUID?,
    @field:NotNull val beneficiaryId: UUID?,
    @field:NotNull @field:DecimalMin("0.0", inclusive = false) val amount: BigDecimal?,
    @field:Pattern(regexp = "WALLET|CASH|BANK") val method: String,
    val bankAccountId: UUID? = null,
    val cashAccountId: UUID? = null,
    @field:Size(max = 120) val paidVia: String? = null,
    @field:Size(max = 300) val note: String? = null,
)

data class PayAllRequest(
    @field:Pattern(regexp = "WALLET|CASH|BANK") val method: String,
    val bankAccountId: UUID? = null,
    val cashAccountId: UUID? = null,
)

data class ReversePaymentRequest(@field:NotBlank @field:Size(max = 300) val reason: String?)

data class CommissionPaymentRow(
    val id: UUID,
    val ref: String,
    val runRef: String,
    val beneficiary: BeneficiaryRef,
    val amount: BigDecimal,
    val method: String,
    val paidVia: String?,
    val paidAt: Instant,
    val paidBy: UserRef?,
    val status: String,
    val note: String?,
)
