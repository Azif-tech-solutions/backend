package com.zifiq.inventory.api

import com.zifiq.inventory.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID

@Tag(name = "Inventory & Stock", description = "Stock Register, Refills, Stock/Bin Card, Comprehensive Stock Report, Stock Movement Report, Re-Order Report")
@SecurityRequirement(name = "bearerAuth")
interface InventoryStockApi {
    @Operation(summary = "List stock items (products/materials/recipes)")
    fun listStockItems(locationId: UUID?, itemType: String?, status: String?): ApiEnvelope<List<StockItemSummary>>

    @Operation(summary = "Register a product/material/recipe/outsourced item")
    fun registerStockItem(request: StockItemRequest): ApiEnvelope<StockItemSummary>

    @Operation(summary = "Activate or suspend a stock item")
    fun updateStockItemStatus(id: UUID, request: StockItemStatusRequest): ApiEnvelope<StockItemSummary>

    @Operation(summary = "Record incoming finished product into stock")
    fun refillFinishedProduct(request: FinishedProductRefillRequest): ApiEnvelope<RefillSummary>

    @Operation(summary = "Record incoming raw material/recipe (also the Material Receive Report source)")
    fun refillRawMaterial(request: RawMaterialRefillRequest): ApiEnvelope<StockReceiptSummary>

    @Operation(summary = "Material/Recipe Receive Report — linked from Factory & Production's Reports menu")
    fun listReceipts(locationId: UUID?, condition: String?): ApiEnvelope<List<StockReceiptSummary>>

    @Operation(summary = "Stock Keeper verifies/offloads a raw material receipt")
    fun verifyReceipt(id: UUID, request: StockReceiptVerifyRequest): ApiEnvelope<StockReceiptSummary>

    @Operation(summary = "Stock Card — per-transaction ledger with running balance (a showroom/factory includes its storage areas unless includeAreas=false)")
    fun stockCard(stockItemId: UUID, locationId: UUID, from: LocalDate?, to: LocalDate?, includeAreas: Boolean): ApiEnvelope<List<StockCardRow>>

    @Operation(summary = "Bin Card — daily-total ledger with running balance (includes storage areas unless includeAreas=false)")
    fun binCard(stockItemId: UUID, locationId: UUID, from: LocalDate?, to: LocalDate?, includeAreas: Boolean): ApiEnvelope<List<BinCardRow>>

    @Operation(summary = "Comprehensive Stock Report (by Category or, for finished goods, by Group)")
    fun comprehensiveStockReport(locationId: UUID?, view: String): ApiEnvelope<List<ComprehensiveStockRow>>

    @Operation(summary = "Stock Movement Report — cross-item ledger view across every movement type (refills, production, transfers, trades, adjustments)")
    fun stockMovementReport(locationId: UUID?, stockItemId: UUID?, sourceType: String?, from: LocalDate?, to: LocalDate?): ApiEnvelope<List<StockMovementRow>>

    @Operation(summary = "Items at or below their reorder level — linked from Factory & Production's Reports menu")
    fun reOrderReport(locationId: UUID?): ApiEnvelope<List<ReOrderRow>>
}
