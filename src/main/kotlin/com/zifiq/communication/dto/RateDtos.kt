package com.zifiq.communication.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A future-dated rate change previewed on a guide row. */
data class RateChangePreview(val effectiveFrom: LocalDate, val rateType: String, val rate: BigDecimal, val status: String)

/** One Comms. Guide row (docs/12 §4.1): the rate in force today for a product × beneficiary type. */
data class CommissionRateRow(
    val stockItem: Ref,
    val code: String,
    val product: String,
    val group: String?,
    val unit: String,
    val sellingPrice: BigDecimal?,
    val rateType: String?,
    val rate: BigDecimal?,
    val commissionPerUnit: BigDecimal?,
    val effectiveFrom: LocalDate?,
    /** "Active", "Inactive" or "Not set". */
    val status: String,
    val nextChange: RateChangePreview?,
)

/** A row of one product's rate history (its own id is fine to expose). */
data class CommissionRateHistoryRow(
    val id: UUID,
    val beneficiaryType: String,
    val rateType: String,
    val rate: BigDecimal,
    val referencePrice: BigDecimal?,
    val status: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val note: String?,
    val setBy: UserRef?,
    val createdAt: Instant,
)

data class SetRateRequest(
    @field:NotNull val stockItemId: UUID?,
    @field:Pattern(regexp = "DEALER|CORRESPONDENT") val beneficiaryType: String = "DEALER",
    @field:Pattern(regexp = "PER_UNIT|PERCENTAGE") val rateType: String,
    @field:NotNull @field:DecimalMin("0.0") val rate: BigDecimal?,
    val referencePrice: BigDecimal? = null,
    @field:NotNull val effectiveFrom: LocalDate?,
    @field:Pattern(regexp = "ACTIVE|INACTIVE") val status: String = "ACTIVE",
    @field:Size(max = 300) val note: String? = null,
)

data class BulkRateRow(
    @field:NotNull val stockItemId: UUID?,
    @field:Pattern(regexp = "PER_UNIT|PERCENTAGE") val rateType: String,
    @field:NotNull @field:DecimalMin("0.0") val rate: BigDecimal?,
    @field:Pattern(regexp = "ACTIVE|INACTIVE") val status: String = "ACTIVE",
)

data class BulkRateRequest(
    @field:Pattern(regexp = "DEALER|CORRESPONDENT") val beneficiaryType: String = "DEALER",
    @field:NotNull val effectiveFrom: LocalDate?,
    @field:NotEmpty @field:Size(max = 500) @field:Valid val rows: List<BulkRateRow>,
)

data class SkippedRow(val row: Int, val reason: String)
data class BulkRateResult(val created: Int, val skipped: List<SkippedRow>)

data class UpdateRateRequest(
    @field:Pattern(regexp = "PER_UNIT|PERCENTAGE") val rateType: String? = null,
    @field:DecimalMin("0.0") val rate: BigDecimal? = null,
    @field:Pattern(regexp = "ACTIVE|INACTIVE") val status: String? = null,
    val effectiveFrom: LocalDate? = null,
    @field:Size(max = 300) val note: String? = null,
)
