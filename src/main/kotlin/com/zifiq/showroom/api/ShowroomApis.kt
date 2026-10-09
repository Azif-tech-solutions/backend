package com.zifiq.showroom.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.showroom.dto.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID
import org.springframework.util.MultiValueMap

@Tag(name = "Showroom — register", description = "Showrooms, storage areas and defaults (docs/05 §4.1)")
@SecurityRequirement(name = "bearerAuth")
interface ShowroomRegisterApi {
    @Operation(summary = "List showrooms (q, status, stateCode) with header stats")
    fun list(q: String?, status: List<String>?, stateCode: List<String>?): ApiEnvelope<ShowroomList>

    @Operation(summary = "Showroom picker with areas")
    fun lookup(q: String?, status: String?): ApiEnvelope<List<ShowroomLookupRow>>

    @Operation(summary = "Showroom detail with areas and defaults")
    fun get(id: UUID): ApiEnvelope<ShowroomDetail>

    @Operation(summary = "Register a showroom (code SHR-n; default areas created; active=false = pending opening)")
    fun create(request: ShowroomRequest): ApiEnvelope<ShowroomDetail>

    @Operation(summary = "Edit a showroom (code and status don't change here)")
    fun update(id: UUID, request: ShowroomRequest): ApiEnvelope<ShowroomDetail>

    @Operation(summary = "ACTIVE / PENDING (pending opening) / SUSPENDED")
    fun changeStatus(id: UUID, request: ShowroomStatusRequest): ApiEnvelope<ShowroomDetail>

    @Operation(summary = "Add a storage area")
    fun createArea(id: UUID, request: AreaRequest): ApiEnvelope<ShowroomDetail>

    @Operation(summary = "Rename a storage area or change its kind")
    fun updateArea(areaId: UUID, request: AreaRequest): ApiEnvelope<ShowroomDetail>

    @Operation(summary = "Activate / deactivate a storage area (it must be empty)")
    fun changeAreaStatus(areaId: UUID, request: AreaStatusRequest): ApiEnvelope<ShowroomDetail>

    @Operation(summary = "Default receiving and selling areas")
    fun setDefaults(id: UUID, request: ShowroomDefaultsRequest): ApiEnvelope<ShowroomDetail>
}

@Tag(name = "Showroom — stock intake", description = "Stock Entry (Direct) and Outsource Stock (docs/05 §4.4–§4.5)")
@SecurityRequirement(name = "bearerAuth")
interface StockIntakeApi {
    @Operation(summary = "Receive third-party goods not in the product register (creates showroom-only items and prices them)")
    fun directEntry(request: DirectEntryRequest): ApiEnvelope<StockEntrySummary>

    @Operation(summary = "Direct entries, one row per line (paged, searchable)")
    fun listDirectEntries(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<StockEntryLineRow>>

    @Operation(summary = "Direct entries with an advanced filter tree")
    fun searchDirectEntries(request: ListSearchRequest): ApiEnvelope<PageResult<StockEntryLineRow>>

    @Operation(summary = "Filterable fields for direct entries")
    fun directEntryFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Receive registered outsourced products (costed at their registered landing cost)")
    fun outsourceReceipt(request: OutsourceReceiptRequest): ApiEnvelope<StockEntrySummary>

    @Operation(summary = "Outsource receipts, one row per line (paged, searchable)")
    fun listOutsourceReceipts(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<StockEntryLineRow>>

    @Operation(summary = "Outsource receipts with an advanced filter tree")
    fun searchOutsourceReceipts(request: ListSearchRequest): ApiEnvelope<PageResult<StockEntryLineRow>>

    @Operation(summary = "Filterable fields for outsource receipts")
    fun outsourceFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "One direct entry or outsource receipt")
    fun getEntry(id: UUID): ApiEnvelope<StockEntrySummary>
}

@Tag(name = "Showroom — area moves", description = "Moves between storage areas of one location (docs/05 §4.7)")
@SecurityRequirement(name = "bearerAuth")
interface AreaMovesApi {
    @Operation(summary = "Move stock between areas (e.g. Warehouse → Shop Floor)")
    fun move(request: AreaMoveRequest): ApiEnvelope<AreaMoveSummary>

    @Operation(summary = "Area moves at a showroom or factory, newest first")
    fun list(locationId: UUID?, from: LocalDate?, to: LocalDate?, page: Int?, size: Int?): ApiEnvelope<PageResult<AreaMoveSummary>>
}

@Tag(name = "Showroom — returns", description = "Customer returns credited to wallets, and Return Reports (docs/05 §4.8–§4.9)")
@SecurityRequirement(name = "bearerAuth")
interface ReturnsApi {
    @Operation(summary = "Record a dealer or non-dealer return; the value is credited to the customer's wallet")
    fun post(request: ReturnRequest): ApiEnvelope<ReturnSummary>

    @Operation(summary = "Reverse a return while the credit is unspent and the goods are still there (also needs customers.wallet.adjust)")
    fun reverse(id: UUID, request: ReversalRequest): ApiEnvelope<ReturnSummary>

    @Operation(summary = "One return")
    fun get(id: UUID): ApiEnvelope<ReturnSummary>

    @Operation(summary = "Return Reports: one row per line, with totals and a by-reason breakdown")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ReturnLineRow>>

    @Operation(summary = "Return Reports with an advanced filter tree")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<ReturnLineRow>>

    @Operation(summary = "Filterable fields for Return Reports")
    fun filters(): ApiEnvelope<FilterMetadata>
}

@Tag(name = "Showroom — reports", description = "Comprehensive Stock and General Transfer reports (docs/05 §4.11, §4.14)")
@SecurityRequirement(name = "bearerAuth")
interface ShowroomReportsApi {
    @Operation(summary = "Stock per item and area, with cost (masked without factory.costing.read) and retail value")
    fun comprehensiveStock(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ComprehensiveStockRow>>

    @Operation(summary = "Every transfer line between showrooms and factories, by route")
    fun transfers(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<TransferReportRow>>

    @Operation(summary = "Transfer report with an advanced filter tree")
    fun searchTransfers(request: ListSearchRequest): ApiEnvelope<PageResult<TransferReportRow>>

    @Operation(summary = "Filterable fields for the transfer report")
    fun transferFilters(): ApiEnvelope<FilterMetadata>
}
