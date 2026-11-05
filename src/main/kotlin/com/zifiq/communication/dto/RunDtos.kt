package com.zifiq.communication.dto

import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** A commission beneficiary — a dealer (customer) or a correspondent. Polymorphic, so it carries its own identity rather than a single-kind Ref. */
data class BeneficiaryRef(val id: UUID, val code: String, val name: String, val type: String)

data class CreateRunRequest(
    @field:Pattern(regexp = "DEALER|CORRESPONDENT") val beneficiaryType: String = "DEALER",
    @field:NotBlank val periodMonth: String?,            // "2026-09"
    val locationId: UUID? = null,
)

data class ApproveRunRequest(val expectedTotal: BigDecimal? = null)

data class CancelRunRequest(@field:NotBlank @field:Size(max = 300) val reason: String?)

/** A "Previous runs" card (docs/12 §4.2). */
data class RunRow(
    val id: UUID,
    val ref: String,
    val period: String,
    val beneficiaryType: String,
    val beneficiaries: Int,
    val total: BigDecimal,
    val paid: BigDecimal,
    val outstanding: BigDecimal,
    /** Draft | Approved | Paid | Part paid | Cancelled. */
    val status: String,
    val runOn: Instant,
    val runBy: UserRef?,
    val approvedBy: UserRef?,
)

data class TopEarner(val name: String, val amount: BigDecimal)
data class IneligibleInfo(val lines: Int, val products: List<String>)
data class PendingInfo(val lines: Int, val value: BigDecimal)

data class RunHeader(
    val id: UUID,
    val ref: String,
    val period: String,
    val beneficiaryType: String,
    val status: String,
    val beneficiaries: Int,
    val units: BigDecimal,
    val commissionPayable: BigDecimal,
    val topEarner: TopEarner?,
    val ineligible: IneligibleInfo,
    val pendingConfirmation: PendingInfo,
)

/** view=beneficiary ("By dealer"). */
data class RunBeneficiaryView(
    val beneficiary: BeneficiaryRef,
    val region: String?,
    val products: Int,
    val units: BigDecimal,
    val commission: BigDecimal,
    val sharePct: BigDecimal,
    val carriedIn: BigDecimal,
    val carriedOut: BigDecimal,
    val paid: BigDecimal,
    val outstanding: BigDecimal,
)

/** view=line ("By product line"). */
data class RunLineView(
    val beneficiary: BeneficiaryRef,
    val itemCode: String,
    val product: String,
    val qtySold: BigDecimal,
    val qtyReturned: BigDecimal,
    val netQty: BigDecimal,
    val rateType: String?,
    val unitRate: BigDecimal?,
    val commission: BigDecimal,
    /** "Yes" | "No rate" | "Inactive" | "Rep not assigned". */
    val eligible: String,
    val lateFromPeriod: java.time.LocalDate?,
)

data class RunDetail(
    val header: RunHeader,
    val beneficiaries: List<RunBeneficiaryView>?,
    val lines: List<RunLineView>?,
)
