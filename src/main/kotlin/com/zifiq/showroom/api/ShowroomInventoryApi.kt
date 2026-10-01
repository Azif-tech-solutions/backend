package com.zifiq.showroom.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.showroom.dto.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Showroom Operations", description = "Stock Entry, Transfers, Returns Processing, Outsource Stock & VAT Reports")
@SecurityRequirement(name = "bearerAuth")
interface ShowroomInventoryApi {
    @Operation(summary = "Get showroom stock position")
    fun listStock(): ApiEnvelope<List<ShowroomStockItem>>

    @Operation(summary = "Record direct stock entry at showroom")
    fun recordDirectEntry(request: DirectStockEntryRequest): ApiEnvelope<InventoryEntrySummary>

    @Operation(summary = "Confirm receipt of transferred stock into showroom")
    fun receiveTransfer(request: ReceiveTransferRequest): ApiEnvelope<ReceiveTransferSummary>

    @Operation(summary = "Initiate showroom-to-showroom stock transfer")
    fun transferBetweenShowrooms(request: ShowroomTransferRequest): ApiEnvelope<ShowroomTransferSummary>

    @Operation(summary = "Process customer or dealer return")
    fun processReturn(request: ReturnProcessingRequest): ApiEnvelope<ReturnProcessingSummary>

    @Operation(summary = "Get showroom VAT report")
    fun getVatReport(showroomCode: String): ApiEnvelope<ShowroomVatReport>
}
