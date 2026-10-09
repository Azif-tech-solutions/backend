package com.zifiq.showroom.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.showroom.dto.*
import com.zifiq.showroom.service.AreaMoveService
import com.zifiq.showroom.service.CustomerReturnService
import com.zifiq.showroom.service.ShowroomRegisterService
import com.zifiq.showroom.service.ShowroomReportService
import com.zifiq.showroom.service.StockIntakeService
import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

private const val READ_STOCK = "@permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')"
private const val MANAGE_REGISTER = "@permissionAuthorizer.hasPermission(authentication, 'showroom.register.manage')"
private const val MANAGE_INTAKE = "@permissionAuthorizer.hasPermission(authentication, 'showroom.intake.manage')"
private const val MANAGE_RETURNS = "@permissionAuthorizer.hasPermission(authentication, 'showroom.returns.manage')"
private const val READ_RETURNS = "@permissionAuthorizer.hasPermission(authentication, 'showroom.returns.read')"

private fun List<String>?.flattened() = orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

@RestController
@RequestMapping("/api/v1/showroom/showrooms")
class ShowroomRegisterController(private val register: ShowroomRegisterService) : ShowroomRegisterApi {
    @GetMapping
    @PreAuthorize(READ_STOCK)
    override fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(required = false) stateCode: List<String>?,
    ): ApiEnvelope<ShowroomList> = ApiEnvelope(register.list(q, status.flattened(), stateCode.flattened()))

    @GetMapping("/lookup")
    @PreAuthorize(READ_STOCK)
    override fun lookup(@RequestParam(required = false) q: String?, @RequestParam(required = false) status: String?): ApiEnvelope<List<ShowroomLookupRow>> =
        ApiEnvelope(register.lookup(q, status))

    @GetMapping("/{id}")
    @PreAuthorize(READ_STOCK)
    override fun get(@PathVariable id: UUID): ApiEnvelope<ShowroomDetail> = ApiEnvelope(register.get(id))

    @PostMapping
    @PreAuthorize(MANAGE_REGISTER)
    override fun create(@Valid @RequestBody request: ShowroomRequest): ApiEnvelope<ShowroomDetail> = ApiEnvelope(register.create(request))

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE_REGISTER)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: ShowroomRequest): ApiEnvelope<ShowroomDetail> = ApiEnvelope(register.update(id, request))

    @PutMapping("/{id}/status")
    @PreAuthorize(MANAGE_REGISTER)
    override fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: ShowroomStatusRequest): ApiEnvelope<ShowroomDetail> =
        ApiEnvelope(register.changeStatus(id, request))

    @PostMapping("/{id}/areas")
    @PreAuthorize(MANAGE_REGISTER)
    override fun createArea(@PathVariable id: UUID, @Valid @RequestBody request: AreaRequest): ApiEnvelope<ShowroomDetail> = ApiEnvelope(register.createArea(id, request))

    @PutMapping("/areas/{areaId}")
    @PreAuthorize(MANAGE_REGISTER)
    override fun updateArea(@PathVariable areaId: UUID, @Valid @RequestBody request: AreaRequest): ApiEnvelope<ShowroomDetail> = ApiEnvelope(register.updateArea(areaId, request))

    @PutMapping("/areas/{areaId}/status")
    @PreAuthorize(MANAGE_REGISTER)
    override fun changeAreaStatus(@PathVariable areaId: UUID, @Valid @RequestBody request: AreaStatusRequest): ApiEnvelope<ShowroomDetail> =
        ApiEnvelope(register.changeAreaStatus(areaId, request.status!!))

    @PutMapping("/{id}/defaults")
    @PreAuthorize(MANAGE_REGISTER)
    override fun setDefaults(@PathVariable id: UUID, @RequestBody request: ShowroomDefaultsRequest): ApiEnvelope<ShowroomDetail> =
        ApiEnvelope(register.setDefaults(id, request))
}

@RestController
@RequestMapping("/api/v1/showroom")
class StockIntakeController(private val intake: StockIntakeService) : StockIntakeApi {
    @PostMapping("/direct-entries")
    @PreAuthorize(MANAGE_INTAKE)
    override fun directEntry(@Valid @RequestBody request: DirectEntryRequest): ApiEnvelope<StockEntrySummary> = ApiEnvelope(intake.directEntry(request))

    @GetMapping("/direct-entries")
    @PreAuthorize(READ_STOCK)
    override fun listDirectEntries(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<StockEntryLineRow>> =
        ApiEnvelope(intake.list("DIRECT_ENTRY", ListSearchRequest.fromQuery(query)))

    @PostMapping("/direct-entries/search")
    @PreAuthorize(READ_STOCK)
    override fun searchDirectEntries(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<StockEntryLineRow>> = ApiEnvelope(intake.list("DIRECT_ENTRY", request))

    @GetMapping("/direct-entries/filters")
    @PreAuthorize(READ_STOCK)
    override fun directEntryFilters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(intake.filters("DIRECT_ENTRY"))

    @PostMapping("/outsource-receipts")
    @PreAuthorize(MANAGE_INTAKE)
    override fun outsourceReceipt(@Valid @RequestBody request: OutsourceReceiptRequest): ApiEnvelope<StockEntrySummary> = ApiEnvelope(intake.outsourceReceipt(request))

    @GetMapping("/outsource-receipts")
    @PreAuthorize(READ_STOCK)
    override fun listOutsourceReceipts(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<StockEntryLineRow>> =
        ApiEnvelope(intake.list("OUTSOURCE_RECEIPT", ListSearchRequest.fromQuery(query)))

    @PostMapping("/outsource-receipts/search")
    @PreAuthorize(READ_STOCK)
    override fun searchOutsourceReceipts(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<StockEntryLineRow>> =
        ApiEnvelope(intake.list("OUTSOURCE_RECEIPT", request))

    @GetMapping("/outsource-receipts/filters")
    @PreAuthorize(READ_STOCK)
    override fun outsourceFilters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(intake.filters("OUTSOURCE_RECEIPT"))

    @GetMapping("/stock-entries/{id}")
    @PreAuthorize(READ_STOCK)
    override fun getEntry(@PathVariable id: UUID): ApiEnvelope<StockEntrySummary> = ApiEnvelope(intake.get(id))
}

@RestController
@RequestMapping("/api/v1/showroom/area-moves")
class AreaMovesController(private val moves: AreaMoveService) : AreaMovesApi {
    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'showroom.stock.manage')")
    override fun move(@Valid @RequestBody request: AreaMoveRequest): ApiEnvelope<AreaMoveSummary> = ApiEnvelope(moves.move(request))

    @GetMapping
    @PreAuthorize(READ_STOCK)
    override fun list(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<AreaMoveSummary>> = ApiEnvelope(moves.list(locationId, from, to, page, size))
}

@RestController
@RequestMapping("/api/v1/showroom/returns")
class ReturnsController(private val returns: CustomerReturnService) : ReturnsApi {
    @PostMapping
    @PreAuthorize(MANAGE_RETURNS)
    override fun post(@Valid @RequestBody request: ReturnRequest): ApiEnvelope<ReturnSummary> = ApiEnvelope(returns.post(request))

    @PostMapping("/{id}/reverse")
    @PreAuthorize(MANAGE_RETURNS)
    override fun reverse(@PathVariable id: UUID, @Valid @RequestBody request: ReversalRequest): ApiEnvelope<ReturnSummary> = ApiEnvelope(returns.reverse(id, request.reason))

    @GetMapping("/{id}")
    @PreAuthorize(READ_RETURNS)
    override fun get(@PathVariable id: UUID): ApiEnvelope<ReturnSummary> = ApiEnvelope(returns.get(id))

    @GetMapping
    @PreAuthorize(READ_RETURNS)
    override fun list(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ReturnLineRow>> =
        ApiEnvelope(returns.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ_RETURNS)
    override fun search(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<ReturnLineRow>> = ApiEnvelope(returns.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ_RETURNS)
    override fun filters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(returns.filters())
}

@RestController
@RequestMapping("/api/v1/showroom/reports")
class ShowroomReportsController(private val reports: ShowroomReportService) : ShowroomReportsApi {
    @GetMapping("/comprehensive-stock")
    @PreAuthorize(READ_STOCK)
    override fun comprehensiveStock(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ComprehensiveStockRow>> =
        ApiEnvelope(reports.comprehensiveStock(ListSearchRequest.fromQuery(query)))

    @GetMapping("/transfers")
    @PreAuthorize(READ_STOCK)
    override fun transfers(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<TransferReportRow>> =
        ApiEnvelope(reports.transfers(ListSearchRequest.fromQuery(query)))

    @PostMapping("/transfers/search")
    @PreAuthorize(READ_STOCK)
    override fun searchTransfers(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<TransferReportRow>> = ApiEnvelope(reports.transfers(request))

    @GetMapping("/transfers/filters")
    @PreAuthorize(READ_STOCK)
    override fun transferFilters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(reports.transferFilterMetadata())
}
