package com.zifiq.showroom.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.showroom.dto.*
import com.zifiq.showroom.service.ShowroomInventoryService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/showroom")
class ShowroomInventoryController(
    private val showroomInventoryService: ShowroomInventoryService,
) : ShowroomInventoryApi {
    @GetMapping("/stock")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')")
    override fun listStock(): ApiEnvelope<List<ShowroomStockItem>> =
        ApiEnvelope(showroomInventoryService.listStock())

    @PostMapping("/stock/direct")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'showroom.stock.manage')")
    override fun recordDirectEntry(
        @Valid @RequestBody request: DirectStockEntryRequest,
    ): ApiEnvelope<InventoryEntrySummary> =
        ApiEnvelope(showroomInventoryService.recordDirectEntry(request))

    @PostMapping("/transfers/receive")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'showroom.stock.manage')")
    override fun receiveTransfer(
        @Valid @RequestBody request: ReceiveTransferRequest,
    ): ApiEnvelope<ReceiveTransferSummary> =
        ApiEnvelope(showroomInventoryService.receiveTransfer(request))

    @PostMapping("/transfers")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'showroom.stock.manage')")
    override fun transferBetweenShowrooms(
        @Valid @RequestBody request: ShowroomTransferRequest,
    ): ApiEnvelope<ShowroomTransferSummary> =
        ApiEnvelope(showroomInventoryService.transferBetweenShowrooms(request))

    @PostMapping("/returns")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'showroom.stock.manage')")
    override fun processReturn(
        @Valid @RequestBody request: ReturnProcessingRequest,
    ): ApiEnvelope<ReturnProcessingSummary> =
        ApiEnvelope(showroomInventoryService.processReturn(request))

    @GetMapping("/vat-report/{showroomCode}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun getVatReport(@PathVariable showroomCode: String): ApiEnvelope<ShowroomVatReport> =
        ApiEnvelope(showroomInventoryService.getVatReport(showroomCode))
}
