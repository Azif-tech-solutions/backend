package com.zifiq.inventory.dto

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

// ---- Stock Transfer (docs/02 §4, extended by docs/05 §4.2–§4.3) ----

data class TransferLineInput(
    @field:NotNull val stockItemId: UUID,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
    val unitPrice: BigDecimal?,
    /** The source itself or one of its storage areas ("dispatch from area"); default the source. */
    val fromLocationId: UUID? = null,
    @field:Size(max = 40) val batchNo: String? = null,
)

data class StockTransferRequest(
    @field:NotNull val sourceLocationId: UUID,
    @field:NotNull val destinationLocationId: UUID,
    /** A registered driver; when given, driverName/driverVehicle are taken from it. */
    val driverId: UUID? = null,
    @field:Size(max = 120) val driverName: String?,
    @field:Size(max = 60) val driverVehicle: String?,
    /** Default today. */
    val transferDate: LocalDate? = null,
    @field:NotEmpty val lines: List<@Valid TransferLineInput>,
)

data class ReceiveLineInput(
    @field:NotNull val stockTransferLineId: UUID,
    @field:DecimalMin("0.0") val qtyReceived: BigDecimal,
    @field:DecimalMin("0.0") val qtyRejected: BigDecimal? = null,
    /** Required when qtyRejected > 0. */
    @field:Size(max = 300) val rejectionNote: String? = null,
    /** The destination or one of its storage areas; default the destination. */
    val receivedIntoLocationId: UUID? = null,
)

data class StockTransferReceiveRequest(
    /** Default today. */
    val receivedDate: LocalDate? = null,
    @field:Size(max = 300) val note: String? = null,
    @field:NotEmpty val lines: List<@Valid ReceiveLineInput>,
)

data class StockTransferLineSummary(
    val id: UUID,
    val stockItemId: UUID,
    val stockItemCode: String,
    val stockItemName: String,
    val unit: String?,
    val qtySent: BigDecimal,
    val qtyReceived: BigDecimal?,
    val qtyRejected: BigDecimal?,
    /** Sent − received − rejected. */
    val outstanding: BigDecimal,
    val fromLocationId: UUID?,
    val fromLocationName: String?,
    val receivedIntoLocationId: UUID?,
    val receivedIntoLocationName: String?,
    val batchNo: String?,
    val rejectionNote: String?,
)

data class StockTransferSummary(
    val id: UUID,
    val waybillRef: String,
    val sourceLocationId: UUID,
    val sourceLocationName: String,
    val sourceLocationType: String,
    val destinationLocationId: UUID,
    val destinationLocationName: String,
    val destinationLocationType: String,
    val status: String,
    val driverId: UUID?,
    val driverName: String?,
    val driverVehicle: String?,
    val transferDate: LocalDate,
    val dispatchedAt: Instant,
    val receivedDate: LocalDate?,
    val receivedAt: Instant?,
    val receiptNote: String?,
    val totalSent: BigDecimal,
    val totalRejected: BigDecimal,
    val waybillUrl: String,
    val lines: List<StockTransferLineSummary>,
)

// ---- Quantity Adjustment (docs/02, extended by docs/05 §4.6) ----

data class QuantityAdjustmentRequest(
    @field:NotNull val locationId: UUID,
    @field:NotNull val stockItemId: UUID,
    /** DELTA (default): direction + quantity. COUNTED: countedQuantity; the server works out the difference. */
    @field:Pattern(regexp = "DELTA|COUNTED") val mode: String? = null,
    @field:Pattern(regexp = "POSITIVE|NEGATIVE") val direction: String? = null,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal? = null,
    @field:DecimalMin("0.0") val countedQuantity: BigDecimal? = null,
    @field:Pattern(regexp = "STOCKTAKE_VARIANCE|DAMAGED_BROKEN|EXPIRED_WRITEOFF|SUSPECTED_THEFT|MISCOUNT_CORRECTION|UNRECORDED_RETURN|OTHER")
    val reason: String,
    @field:Size(max = 300) val note: String?,
    val otpChallengeId: UUID?,
)

data class QuantityAdjustmentSummary(
    val id: UUID,
    val ref: String,
    val locationId: UUID,
    val locationName: String,
    val rootLocationName: String,
    val stockItemId: UUID,
    val productCode: String,
    val productName: String,
    val unit: String?,
    val mode: String,
    val direction: String,
    val quantity: BigDecimal,
    /** On hand before / after (recorded for adjustments posted since V9_2). */
    val recordedQuantity: BigDecimal?,
    val newQuantity: BigDecimal?,
    val reason: String,
    val note: String?,
    val otpVerified: Boolean,
    val performedBy: UUID,
    val performedByName: String?,
    val at: Instant,
)
