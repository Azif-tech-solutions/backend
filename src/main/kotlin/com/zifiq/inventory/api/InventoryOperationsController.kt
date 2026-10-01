package com.zifiq.inventory.api

import com.zifiq.inventory.dto.*
import com.zifiq.inventory.service.InventoryOperationsService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryOperationsController(
    private val inventoryOperationsService: InventoryOperationsService,
) : InventoryOperationsApi {
    @PostMapping("/stock/transfers")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.transfers.manage')")
    override fun dispatchTransfer(@Valid @RequestBody request: StockTransferRequest): ApiEnvelope<StockTransferSummary> =
        ApiEnvelope(inventoryOperationsService.dispatchTransfer(request))

    @PostMapping("/stock/transfers/{id}/receive")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.transfers.manage')")
    override fun receiveTransfer(
        @PathVariable id: UUID,
        @Valid @RequestBody request: StockTransferReceiveRequest,
    ): ApiEnvelope<StockTransferSummary> =
        ApiEnvelope(inventoryOperationsService.receiveTransfer(id, request))

    @GetMapping("/stock/transfers")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun listTransfers(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) status: String?,
    ): ApiEnvelope<List<StockTransferSummary>> =
        ApiEnvelope(inventoryOperationsService.listTransfers(locationId, status))

    @PostMapping("/quantity-adjustments")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.adjustments.manage')")
    override fun postAdjustment(@Valid @RequestBody request: QuantityAdjustmentRequest): ApiEnvelope<QuantityAdjustmentSummary> =
        ApiEnvelope(inventoryOperationsService.postAdjustment(request))

    @GetMapping("/quantity-adjustments")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun listAdjustments(@RequestParam(required = false) locationId: UUID?): ApiEnvelope<List<QuantityAdjustmentSummary>> =
        ApiEnvelope(inventoryOperationsService.listAdjustments(locationId))
}
