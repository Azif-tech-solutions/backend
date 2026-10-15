package com.zifiq.factory.api

import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.PageParams
import com.zifiq.factory.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID

/**
 * OpenAPI documentation for [FactoryProductionController], kept out of the controller itself so
 * routing/security/implementation stay readable. springdoc-openapi merges [Operation] metadata
 * declared here with the implementing method's own `@RequestParam`/`@PathVariable`/`@RequestBody`
 * annotations (which must stay on the controller -- Spring MVC needs them there to bind requests).
 */
@Tag(name = "Factory Production", description = "In-House Recipes, Production Cost Calculator, Production Runs, Special Production")
@SecurityRequirement(name = "bearerAuth")
interface FactoryProductionApi {
    @Operation(summary = "Register an in-house recipe")
    fun registerRecipe(request: RecipeRequest): ApiEnvelope<RecipeSummary>

    @Operation(summary = "List in-house recipes")
    fun listRecipes(locationId: UUID?, status: String?, paging: PageParams): ApiEnvelope<PageResult<RecipeSummary>>

    @Operation(summary = "Run the Production Cost Calculator (QC — response omits cost figures)")
    fun createCostCalculation(request: ProductionCostingRequest): ApiEnvelope<ProductionCostingSummary>

    @Operation(summary = "View a costing's computed figures (Management/Admin only)")
    fun getCostingDetail(id: UUID): ApiEnvelope<ProductionCostingDetail>

    @Operation(summary = "Approve a production costing")
    fun approveCosting(id: UUID): ApiEnvelope<ProductionCostingDetail>

    @Operation(summary = "Reject a production costing")
    fun rejectCosting(id: UUID, request: RejectCostingRequest): ApiEnvelope<ProductionCostingDetail>

    @Operation(summary = "Commit an approved costing into an actual production batch")
    fun commitProductionRun(request: ProductionRunRequest): ApiEnvelope<ProductionRunSummary>

    @Operation(summary = "List production runs")
    fun listProductionRuns(locationId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageParams): ApiEnvelope<PageResult<ProductionRunSummary>>

    @Operation(summary = "Total Cost for Selected Recipes over their production history")
    fun totalCostForRecipes(recipeIds: List<UUID>): ApiEnvelope<List<RecipeCostAggregate>>

    @Operation(summary = "Register a special-production client")
    fun registerSpecialClient(request: SpecialProductionClientRequest): ApiEnvelope<SpecialProductionClientSummary>

    @Operation(summary = "Create a special (custom client order) production run")
    fun createSpecialProductionRun(request: SpecialProductionRunRequest): ApiEnvelope<SpecialProductionRunSummary>

    @Operation(summary = "Update a special production run's dispatch status")
    fun updateSpecialProductionStatus(id: UUID, request: SpecialProductionStatusRequest): ApiEnvelope<SpecialProductionRunSummary>

    @Operation(summary = "List special production runs")
    fun listSpecialProductionRuns(locationId: UUID?, clientId: UUID?, paging: PageParams): ApiEnvelope<PageResult<SpecialProductionRunSummary>>
}
