package com.zifiq.inventory.api

import com.zifiq.inventory.dto.*
import com.zifiq.inventory.service.InventoryStockService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryStockController(
    private val inventoryStockService: InventoryStockService,
) : InventoryStockApi {
    @GetMapping("/stock-items")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun listStockItems(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) itemType: String?,
        @RequestParam(required = false) status: String?,
    ): ApiEnvelope<List<StockItemSummary>> =
        ApiEnvelope(inventoryStockService.listStockItems(locationId, itemType, status))

    @PostMapping("/stock-items")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.manage')")
    override fun registerStockItem(@Valid @RequestBody request: StockItemRequest): ApiEnvelope<StockItemSummary> =
        ApiEnvelope(inventoryStockService.registerStockItem(request))

    @PutMapping("/stock-items/{id}/status")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.manage')")
    override fun updateStockItemStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody request: StockItemStatusRequest,
    ): ApiEnvelope<StockItemSummary> =
        ApiEnvelope(inventoryStockService.updateStockItemStatus(id, request.status))

    @PostMapping("/stock/refill/finished-product")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.manage')")
    override fun refillFinishedProduct(@Valid @RequestBody request: FinishedProductRefillRequest): ApiEnvelope<RefillSummary> =
        ApiEnvelope(inventoryStockService.refillFinishedProduct(request))

    @PostMapping("/stock/refill/raw-material")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.manage')")
    override fun refillRawMaterial(@Valid @RequestBody request: RawMaterialRefillRequest): ApiEnvelope<StockReceiptSummary> =
        ApiEnvelope(inventoryStockService.refillRawMaterial(request))

    @GetMapping("/stock/receipts")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun listReceipts(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) condition: String?,
    ): ApiEnvelope<List<StockReceiptSummary>> =
        ApiEnvelope(inventoryStockService.listReceipts(locationId, condition))

    @PostMapping("/stock/receipts/{id}/verify")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.manage')")
    override fun verifyReceipt(
        @PathVariable id: UUID,
        @Valid @RequestBody request: StockReceiptVerifyRequest,
    ): ApiEnvelope<StockReceiptSummary> =
        ApiEnvelope(inventoryStockService.verifyReceipt(id, request.condition))

    @GetMapping("/stock/card/{stockItemId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun stockCard(
        @PathVariable stockItemId: UUID,
        @RequestParam locationId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "true") includeAreas: Boolean,
    ): ApiEnvelope<List<StockCardRow>> =
        ApiEnvelope(inventoryStockService.stockCard(stockItemId, locationId, from, to, includeAreas))

    @GetMapping("/stock/bin-card/{stockItemId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun binCard(
        @PathVariable stockItemId: UUID,
        @RequestParam locationId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "true") includeAreas: Boolean,
    ): ApiEnvelope<List<BinCardRow>> =
        ApiEnvelope(inventoryStockService.binCard(stockItemId, locationId, from, to, includeAreas))

    @GetMapping("/stock/comprehensive-report")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun comprehensiveStockReport(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(defaultValue = "CATEGORY") view: String,
    ): ApiEnvelope<List<ComprehensiveStockRow>> =
        ApiEnvelope(inventoryStockService.comprehensiveStockReport(locationId, view))

    @GetMapping("/stock/movement-report")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun stockMovementReport(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) stockItemId: UUID?,
        @RequestParam(required = false) sourceType: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ApiEnvelope<List<StockMovementRow>> =
        ApiEnvelope(inventoryStockService.stockMovementReport(locationId, stockItemId, sourceType, from, to))

    @GetMapping("/stock/re-order-report")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun reOrderReport(@RequestParam(required = false) locationId: UUID?): ApiEnvelope<List<ReOrderRow>> =
        ApiEnvelope(inventoryStockService.reOrderReport(locationId))
}
