package com.zifiq.showroom.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ShowroomStockItem(
    val productCode: String,
    val productName: String,
    val quantity: Double,
    val unit: String,
    val warehouseCode: String,
    val status: String,
)

data class DirectStockEntryRequest(
    @field:NotBlank val warehouseCode: String,
    @field:NotBlank val productCode: String,
    @field:Min(1) val quantity: Double,
    @field:NotBlank val unit: String,
    @field:NotBlank val source: String,
)

data class InventoryEntrySummary(
    val id: UUID,
    val warehouseCode: String,
    val productCode: String,
    val quantity: Double,
    val unit: String,
    val source: String,
    val status: String,
)

data class ReceiveTransferRequest(
    @field:NotBlank val transferWaybillNumber: String,
    @field:NotBlank val showroomCode: String,
)

data class ReceiveTransferSummary(
    val receiptId: UUID,
    val transferWaybillNumber: String,
    val showroomCode: String,
    val totalItemsReceived: Int,
    val status: String,
    val receivedAt: Instant,
)

data class ShowroomTransferRequest(
    @field:NotBlank val sourceShowroomCode: String,
    @field:NotBlank val targetShowroomCode: String,
    @field:NotBlank val productCode: String,
    @field:Positive val quantity: Double,
)

data class ShowroomTransferSummary(
    val transferId: UUID,
    val waybillNumber: String,
    val status: String,
    val transferredAt: Instant,
)

data class ReturnProcessingRequest(
    @field:NotBlank val invoiceNumber: String,
    @field:NotBlank val productCode: String,
    @field:Positive val quantity: Double,
    @field:NotBlank val returnReason: String,
    val isDealerReturn: Boolean = false,
)

data class ReturnProcessingSummary(
    val returnId: UUID,
    val refundAmount: BigDecimal,
    val status: String, // APPROVED, RESTOCKED
    val processedAt: Instant,
)

data class ShowroomVatReport(
    val showroomCode: String,
    val grossSalesAmount: BigDecimal,
    val vatRate: BigDecimal,
    val totalVatCollected: BigDecimal,
    val reportingPeriod: String,
)
