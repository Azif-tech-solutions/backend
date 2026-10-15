package com.zifiq.inventory.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---- Stock Register ----

data class StockItemRequest(
    @field:NotNull val locationId: UUID,
    @field:Pattern(regexp = "FINISHED_PRODUCT|RAW_MATERIAL|IN_HOUSE_RECIPE|OUTSOURCED") val itemType: String,
    val code: String?,
    @field:NotBlank val name: String,
    val categoryId: UUID?,
    val brand: String?,
    @field:NotNull val unitOfMeasureId: UUID,
    @field:DecimalMin("0.0") val reorderLevel: BigDecimal?,
    val barcode: String?,
    val landingCost: BigDecimal?,
    val markupCost: BigDecimal?,
    // Every product carries a tax/trade classification -- see com.zifiq.account's VAT Control
    // (GET /api/v1/account/vat-control/{vat-categories,hsn-sections,isic-sections} for valid IDs).
    // vatCategoryId must be one the active tenant has activated via VAT Control.
    @field:NotNull val vatCategoryId: UUID,
    @field:NotNull val hsnSectionId: UUID,
    @field:NotNull val isicSectionId: UUID,
)

data class StockItemStatusRequest(
    @field:Pattern(regexp = "ACTIVE|SUSPENDED") val status: String,
)

data class StockItemSummary(
    val id: UUID,
    val locationId: UUID,
    val itemType: String,
    val code: String,
    val name: String,
    val categoryId: UUID?,
    val brand: String?,
    val unitOfMeasureId: UUID,
    val reorderLevel: BigDecimal,
    val barcode: String?,
    val landingCost: BigDecimal?,
    val markupCost: BigDecimal?,
    val vatCategoryId: UUID,
    /** Null only for DIRECT_ENTRY items (docs/05 §2.2). */
    val hsnSectionId: UUID?,
    val isicSectionId: UUID?,
    val status: String,
    val createdAt: Instant,
    /**
     * Stock on hand: at the requested location (including its storage areas) when the list is
     * filtered by locationId, otherwise across every location the caller can see; for a single
     * item (register/status change), at its home location.
     */
    val quantityOnHand: BigDecimal,
)

// ---- Stock Refill ----

data class FinishedProductRefillRequest(
    @field:NotNull val locationId: UUID,
    @field:NotNull val stockItemId: UUID,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
    val note: String?,
)

data class RawMaterialRefillRequest(
    @field:NotNull val locationId: UUID,
    @field:NotNull val stockItemId: UUID,
    val supplierId: UUID?,
    val manufacturerId: UUID?,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
    @field:DecimalMin("0.0", inclusive = false) val unitCost: BigDecimal,
    val manufacturedDate: LocalDate?,
    val expiryDate: LocalDate?,
)

data class RefillSummary(
    val ref: String,
    val stockItemId: UUID,
    val quantity: BigDecimal,
    val performedBy: UUID,
    val at: Instant,
)

// ---- Material/Recipe Receive Report ----

data class StockReceiptVerifyRequest(
    @field:Pattern(regexp = "ACCEPTED|ACCEPTED_WITH_EXCEPTIONS") val condition: String,
)

data class StockReceiptSummary(
    val id: UUID,
    val ref: String,
    val stockItemId: UUID,
    val supplierId: UUID?,
    val manufacturerId: UUID?,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val manufacturedDate: LocalDate?,
    val expiryDate: LocalDate?,
    val verifiedByUserId: UUID?,
    val offloadedAt: Instant?,
    val condition: String,
)

// ---- Stock/Bin Card ----

data class StockCardRow(
    val sn: Int,
    val date: LocalDate,
    val remark: String?,
    val qtyIn: BigDecimal,
    val qtyOut: BigDecimal,
    val runningBalance: BigDecimal,
)

data class BinCardRow(
    val sn: Int,
    val date: LocalDate,
    val qtyIn: BigDecimal,
    val qtyOut: BigDecimal,
    val balance: BigDecimal,
)

// ---- Comprehensive Stock Report ----

data class ComprehensiveStockRow(
    val lineNumber: Int,
    val groupingLabel: String,
    val stockDetail: String,
    val unitCost: BigDecimal,
    val quantityLeft: BigDecimal,
    val totalCost: BigDecimal,
)

// ---- Stock Movement Report ----

data class StockMovementRow(
    val sn: Int,
    val date: LocalDate,
    val locationId: UUID,
    val stockItemId: UUID,
    val itemCode: String,
    val itemName: String,
    val sourceType: String,
    val sourceReference: String,
    val qtyIn: BigDecimal,
    val qtyOut: BigDecimal,
)

// ---- Re-Order Report ----

data class ReOrderRow(
    val stockItemId: UUID,
    val itemName: String,
    val quantityOnHand: BigDecimal,
    val reorderLevel: BigDecimal,
)

// ---- NRS classification (docs/08 §2.1) ----

/** PATCH: an omitted field is unchanged; an empty string clears it. */
data class StockItemClassificationRequest(
    @field:jakarta.validation.constraints.Size(max = 10) val hsnCode: String? = null,
    @field:jakarta.validation.constraints.Size(max = 4) val isicCode: String? = null,
    @field:jakarta.validation.constraints.Size(max = 10) val nrsUnitCode: String? = null,
    val isService: Boolean? = null,
)

data class StockItemClassification(
    val id: UUID,
    val code: String,
    val name: String,
    val hsnSectionCode: String?,
    val hsnCode: String?,
    val isicSectionCode: String?,
    val isicCode: String?,
    val nrsUnitCode: String?,
    val isService: Boolean,
)
