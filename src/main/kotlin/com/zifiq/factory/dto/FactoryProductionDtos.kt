package com.zifiq.factory.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ---- In-House Recipes ----

data class RecipeIngredientInput(
    @field:NotNull val rawMaterialStockItemId: UUID,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
    @field:NotNull val unitId: UUID,
)

data class RecipeRequest(
    @field:NotNull val locationId: UUID,
    val code: String?,
    @field:NotBlank val name: String,
    val description: String?,
    @field:Pattern(regexp = "IN_HOUSE_PRODUCTION|DIRECT_IMPORTATION") val sourcing: String,
    @field:DecimalMin("0.0", inclusive = false) val yieldQty: BigDecimal,
    @field:NotNull val yieldUnitId: UUID,
    val shelfLifeDays: Int?,
    val reorderLevel: BigDecimal?,
    @field:NotEmpty val ingredients: List<RecipeIngredientInput>,
)

data class RecipeSummary(
    val id: UUID,
    val code: String,
    val name: String,
    val sourcing: String,
    val yieldQty: BigDecimal,
    val status: String,
    val ingredients: List<RecipeIngredientInput>,
)

// ---- Production Cost Calculator ----

data class MaterialLineInput(
    @field:NotNull val stockItemId: UUID,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
)

data class OverheadLineInput(
    @field:NotBlank val label: String,
    @field:DecimalMin("0.0") val amount: BigDecimal,
)

data class ProductionCostingRequest(
    @field:NotNull val locationId: UUID,
    @field:NotNull val targetStockItemId: UUID,
    val recipeId: UUID?,
    @field:DecimalMin("0.0", inclusive = false) val batchSizeQty: BigDecimal,
    val materials: List<MaterialLineInput>?,
    val overheads: List<OverheadLineInput> = emptyList(),
)

/** Returned to the caller of the calculator (QC) — deliberately omits cost figures. */
data class ProductionCostingSummary(
    val id: UUID,
    val targetStockItemId: UUID,
    val batchSizeQty: BigDecimal,
    val status: String,
    val computedByUserId: UUID,
    val createdAt: Instant,
)

/** Returned only to callers holding factory.costing.read. */
data class ProductionCostingDetail(
    val id: UUID,
    val targetStockItemId: UUID,
    val recipeId: UUID?,
    val batchSizeQty: BigDecimal,
    val materialsSnapshot: List<Map<String, Any?>>,
    val overheadSnapshot: List<Map<String, Any?>>,
    val computedUnitCost: BigDecimal,
    val computedTotalCost: BigDecimal,
    val status: String,
    val computedByUserId: UUID,
    val approvedByUserId: UUID?,
    val approvedAt: Instant?,
    val rejectionReason: String?,
)

data class RejectCostingRequest(
    @field:NotBlank val rejectionReason: String,
)

// ---- Production Runs ----

data class ProductionRunRequest(
    @field:NotNull val productionCostingId: UUID,
)

data class ProductionRunSummary(
    val batchRef: String,
    val targetStockItemId: UUID,
    val quantityProduced: BigDecimal,
    val unitLandingCost: BigDecimal,
    val totalLandingCost: BigDecimal,
    val runAt: Instant,
)

data class RecipeCostAggregate(
    val recipeId: UUID,
    val recipeName: String,
    val qtyUsed: BigDecimal,
    val totalCost: BigDecimal,
)

// ---- Special Production ----

data class SpecialProductionClientRequest(
    @field:NotBlank val name: String,
    val terms: String?,
)

data class SpecialProductionClientSummary(
    val id: UUID,
    val name: String,
    val terms: String?,
)

data class SpecialProductionRunRequest(
    @field:NotNull val locationId: UUID,
    @field:NotNull val clientId: UUID,
    val targetStockItemId: UUID?,
    val productDescription: String?,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
    @field:NotNull val unitId: UUID,
    @field:DecimalMin("0.0") val unitCost: BigDecimal,
    @field:DecimalMin("0.0") val price: BigDecimal,
    val materials: List<MaterialLineInput> = emptyList(),
)

data class SpecialProductionStatusRequest(
    @field:Pattern(regexp = "IN_PRODUCTION|AWAITING_DISPATCH|DELIVERED") val status: String,
)

data class SpecialProductionRunSummary(
    val id: UUID,
    val orderRef: String,
    val batchRef: String,
    val clientId: UUID,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val status: String,
)
