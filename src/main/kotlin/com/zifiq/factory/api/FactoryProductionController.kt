package com.zifiq.factory.api

import org.springdoc.core.annotations.ParameterObject
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.PageParams
import com.zifiq.factory.dto.*
import com.zifiq.factory.service.ProductionService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/factory")
class FactoryProductionController(
    private val productionService: ProductionService,
) : FactoryProductionApi {
    @PostMapping("/recipes")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.recipes.manage')")
    override fun registerRecipe(@Valid @RequestBody request: RecipeRequest): ApiEnvelope<RecipeSummary> =
        ApiEnvelope(productionService.registerRecipe(request))

    @GetMapping("/recipes")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun listRecipes(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) status: String?,
        @ParameterObject paging: PageParams,
    ): ApiEnvelope<PageResult<RecipeSummary>> =
        ApiEnvelope(productionService.listRecipes(locationId, status, paging.toRequest()))

    @PostMapping("/production/cost-calculator")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.production.manage')")
    override fun createCostCalculation(@Valid @RequestBody request: ProductionCostingRequest): ApiEnvelope<ProductionCostingSummary> =
        ApiEnvelope(productionService.createCostCalculation(request))

    @GetMapping("/production/cost-calculator/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.costing.read')")
    override fun getCostingDetail(@PathVariable id: UUID): ApiEnvelope<ProductionCostingDetail> =
        ApiEnvelope(productionService.getCostingDetail(id))

    @PostMapping("/production/cost-calculator/{id}/approve")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.costing.approve')")
    override fun approveCosting(@PathVariable id: UUID): ApiEnvelope<ProductionCostingDetail> =
        ApiEnvelope(productionService.approveCosting(id))

    @PostMapping("/production/cost-calculator/{id}/reject")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.costing.approve')")
    override fun rejectCosting(@PathVariable id: UUID, @Valid @RequestBody request: RejectCostingRequest): ApiEnvelope<ProductionCostingDetail> =
        ApiEnvelope(productionService.rejectCosting(id, request.rejectionReason))

    @PostMapping("/production/runs")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.production.manage')")
    override fun commitProductionRun(@Valid @RequestBody request: ProductionRunRequest): ApiEnvelope<ProductionRunSummary> =
        ApiEnvelope(productionService.commitProductionRun(request))

    @GetMapping("/production/runs")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun listProductionRuns(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @ParameterObject paging: PageParams,
    ): ApiEnvelope<PageResult<ProductionRunSummary>> =
        ApiEnvelope(productionService.listProductionRuns(locationId, from, to, paging.toRequest()))

    @GetMapping("/production/total-cost")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.costing.read')")
    override fun totalCostForRecipes(@RequestParam recipeIds: List<UUID>): ApiEnvelope<List<RecipeCostAggregate>> =
        ApiEnvelope(productionService.totalCostForRecipes(recipeIds))

    @PostMapping("/special-production/clients")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.production.manage')")
    override fun registerSpecialClient(@Valid @RequestBody request: SpecialProductionClientRequest): ApiEnvelope<SpecialProductionClientSummary> =
        ApiEnvelope(productionService.registerSpecialClient(request))

    @PostMapping("/special-production/runs")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.production.manage')")
    override fun createSpecialProductionRun(@Valid @RequestBody request: SpecialProductionRunRequest): ApiEnvelope<SpecialProductionRunSummary> =
        ApiEnvelope(productionService.createSpecialProductionRun(request))

    @PutMapping("/special-production/runs/{id}/status")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.production.manage')")
    override fun updateSpecialProductionStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SpecialProductionStatusRequest,
    ): ApiEnvelope<SpecialProductionRunSummary> =
        ApiEnvelope(productionService.updateSpecialProductionStatus(id, request.status))

    @GetMapping("/special-production/runs")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun listSpecialProductionRuns(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) clientId: UUID?,
        @ParameterObject paging: PageParams,
    ): ApiEnvelope<PageResult<SpecialProductionRunSummary>> =
        ApiEnvelope(productionService.listSpecialProductionRuns(locationId, clientId, paging.toRequest()))
}
