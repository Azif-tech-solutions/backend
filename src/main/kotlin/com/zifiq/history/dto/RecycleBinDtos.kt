package com.zifiq.history.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---- requests ----

data class DeleteProductRequest(
    @field:NotNull val stockItemId: UUID?,
    @field:NotBlank @field:Size(max = 300) val reason: String?,
)

data class SuspendProductRequest(
    @field:NotNull val stockItemId: UUID?,
    @field:NotBlank @field:Size(max = 300) val reason: String?,
    val reviewOn: LocalDate? = null,
)

data class RecycleNoteRequest(@field:Size(max = 300) val note: String? = null)

data class PurgeRequest(
    @field:NotBlank val confirmCode: String?,
    val writeOffConfirmed: Boolean = false,
    @field:NotBlank @field:Size(max = 300) val reason: String?,
)

data class ReviewRequest(@field:NotNull val reviewOn: LocalDate?)

data class RepriceAndResumeRequest(
    @field:NotNull val retailPrice: BigDecimal?,
    @field:NotNull val dealerPrice: BigDecimal?,
    @field:NotNull val effectiveFrom: LocalDate?,
)

// ---- views ----

/** The result of a lifecycle action: the product reference and its new status. */
data class RecycleActionView(val stockItem: Ref, val status: String, val action: String, val at: Instant)

/** A deleted product awaiting purge or restore (§4.9). unitCost/valueHeld are cost figures. */
data class DeletedItemRow(
    val stockItem: Ref,
    val quantityHeld: BigDecimal,
    val unitCost: BigDecimal?,
    val valueHeld: BigDecimal?,
    val reason: String?,
    val deletedBy: UserRef?,
    val deletedOn: Instant?,
    val heldUntil: LocalDate?,
    val purgeEligible: Boolean,
)

/** A product suspended from sale (§4.9). */
data class SuspendedItemRow(
    val stockItem: Ref,
    val stockHeld: BigDecimal,
    val unitCost: BigDecimal?,
    val valueHeld: BigDecimal?,
    val reason: String?,
    val suspendedOn: Instant?,
    val reviewOn: LocalDate?,
)
