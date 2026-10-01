package com.zifiq.inventory.api

import com.zifiq.inventory.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Inventory & Stock — Movement", description = "Stock Transfers, Quantity Adjustments")
@SecurityRequirement(name = "bearerAuth")
interface InventoryOperationsApi {
    @Operation(summary = "Dispatch a stock transfer to another factory or showroom")
    fun dispatchTransfer(request: StockTransferRequest): ApiEnvelope<StockTransferSummary>

    @Operation(summary = "Confirm receipt of a dispatched stock transfer")
    fun receiveTransfer(id: UUID, request: StockTransferReceiveRequest): ApiEnvelope<StockTransferSummary>

    @Operation(summary = "List stock transfers")
    fun listTransfers(locationId: UUID?, status: String?): ApiEnvelope<List<StockTransferSummary>>

    @Operation(summary = "Post a quantity adjustment (OTP-gated above threshold, Stock Keeper only)")
    fun postAdjustment(request: QuantityAdjustmentRequest): ApiEnvelope<QuantityAdjustmentSummary>

    @Operation(summary = "List quantity adjustments")
    fun listAdjustments(locationId: UUID?): ApiEnvelope<List<QuantityAdjustmentSummary>>
}
