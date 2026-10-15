package com.zifiq.inventory.api

import com.zifiq.common.paging.PageParams
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.inventory.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap

@Tag(name = "Inventory & Stock — Movement", description = "Stock transfers and waybills, quantity adjustments (docs/02, docs/05 §4.2–§4.6)")
@SecurityRequirement(name = "bearerAuth")
interface InventoryOperationsApi {
    @Operation(summary = "Dispatch a stock transfer to another factory or showroom (from chosen areas, with a registered driver)")
    fun dispatchTransfer(request: StockTransferRequest): ApiEnvelope<StockTransferSummary>

    @Operation(summary = "Receive a transfer: accept and/or reject per line; rejected goods go back to the source")
    fun receiveTransfer(id: UUID, request: StockTransferReceiveRequest): ApiEnvelope<StockTransferSummary>

    @Operation(summary = "List transfers (status repeatable: DISPATCHED,PARTIALLY_RECEIVED = incoming consignments)")
    fun listTransfers(locationId: UUID?, status: List<String>?, direction: String?, q: String?, from: LocalDate?, to: LocalDate?, paging: PageParams): ApiEnvelope<PageResult<StockTransferSummary>>

    @Operation(summary = "One transfer with its lines")
    fun getTransfer(id: UUID): ApiEnvelope<StockTransferSummary>

    @Operation(summary = "The transfer waybill as a PDF (DUPLICATE COPY once a reprint is recorded)")
    fun waybillPdf(id: UUID): ResponseEntity<ByteArray>

    @Operation(summary = "Post a quantity adjustment by delta or by counted quantity (OTP-gated above threshold)")
    fun postAdjustment(request: QuantityAdjustmentRequest): ApiEnvelope<QuantityAdjustmentSummary>

    @Operation(summary = "Adjustments Report: paged, searchable, with totals (a showroom includes its areas)")
    fun listAdjustments(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<QuantityAdjustmentSummary>>

    @Operation(summary = "Adjustments Report with an advanced filter tree")
    fun searchAdjustments(request: ListSearchRequest): ApiEnvelope<PageResult<QuantityAdjustmentSummary>>

    @Operation(summary = "Filterable fields for the Adjustments Report")
    fun adjustmentFilters(): ApiEnvelope<FilterMetadata>
}
