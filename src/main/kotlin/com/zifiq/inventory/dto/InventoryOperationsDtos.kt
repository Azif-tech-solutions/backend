package com.zifiq.inventory.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ---- Stock Transfer ----

data class TransferLineInput(
    @field:NotNull val stockItemId: UUID,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
    val unitPrice: BigDecimal?,
)

data class StockTransferRequest(
    @field:NotNull val sourceLocationId: UUID,
    @field:NotNull val destinationLocationId: UUID,
    val driverName: String?,
    val driverVehicle: String?,
    @field:NotEmpty val lines: List<@Valid TransferLineInput>,
)

data class ReceiveLineInput(
    @field:NotNull val stockTransferLineId: UUID,
    @field:DecimalMin("0.0") val qtyReceived: BigDecimal,
    val qtyRejected: BigDecimal?,
)

data class StockTransferReceiveRequest(
    @field:NotEmpty val lines: List<@Valid ReceiveLineInput>,
)

data class StockTransferLineSummary(
    val id: UUID,
    val stockItemId: UUID,
    val qtySent: BigDecimal,
    val qtyReceived: BigDecimal?,
    val qtyRejected: BigDecimal?,
)

data class StockTransferSummary(
    val id: UUID,
    val waybillRef: String,
    val sourceLocationId: UUID,
    val destinationLocationId: UUID,
    val status: String,
    val dispatchedAt: Instant,
    val receivedAt: Instant?,
    val lines: List<StockTransferLineSummary>,
)

// ---- Quantity Adjustment ----

data class QuantityAdjustmentRequest(
    @field:NotNull val locationId: UUID,
    @field:NotNull val stockItemId: UUID,
    @field:Pattern(regexp = "POSITIVE|NEGATIVE") val direction: String,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
    @field:Pattern(regexp = "STOCKTAKE_VARIANCE|DAMAGED_BROKEN|EXPIRED_WRITEOFF|SUSPECTED_THEFT|MISCOUNT_CORRECTION|UNRECORDED_RETURN|OTHER")
    val reason: String,
    val note: String?,
    val otpChallengeId: UUID?,
)

data class QuantityAdjustmentSummary(
    val ref: String,
    val stockItemId: UUID,
    val direction: String,
    val quantity: BigDecimal,
    val reason: String,
    val note: String?,
    val performedBy: UUID,
    val at: Instant,
)
