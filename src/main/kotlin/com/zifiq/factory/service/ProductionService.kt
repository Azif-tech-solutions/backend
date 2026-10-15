package com.zifiq.factory.service

import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SortOrder
import com.zifiq.common.paging.Paging
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.PageRequest
import com.zifiq.factory.dto.*
import com.zifiq.factory.model.*
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CostVisibility
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

@Service
class ProductionService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val documentSequenceService: DocumentSequenceService,
    private val stockLedgerRepository: com.zifiq.inventory.repository.StockLedgerRepository,
    private val costVisibility: CostVisibility,
    private val locationAccessService: LocationAccessService,
    private val moduleSettings: com.zifiq.platform.settings.ModuleSettings,
) {
    // ---- Recipes ----

    fun registerRecipe(request: RecipeRequest): RecipeSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.locationId)
        val code = request.code?.trim()?.takeIf(String::isNotEmpty) ?: documentSequenceService.next("RCP")

        val id = RecipesTable.insertAndGetId {
            it[RecipesTable.tenantId] = actor.tenantId
            it[RecipesTable.locationId] = request.locationId
            it[RecipesTable.code] = code
            it[RecipesTable.name] = request.name.trim()
            it[RecipesTable.description] = request.description
            it[RecipesTable.sourcing] = request.sourcing
            it[RecipesTable.yieldQty] = request.yieldQty
            it[RecipesTable.yieldUnitId] = request.yieldUnitId
            it[RecipesTable.shelfLifeDays] = request.shelfLifeDays
            it[RecipesTable.reorderLevel] = request.reorderLevel
            it[RecipesTable.status] = "ACTIVE"
            it[RecipesTable.createdByUserId] = actor.userId
            it[RecipesTable.createdAt] = Instant.now()
        }.value

        request.ingredients.forEach { ingredient ->
            RecipeIngredientsTable.insert {
                it[RecipeIngredientsTable.recipeId] = id
                it[RecipeIngredientsTable.rawMaterialStockItemId] = ingredient.rawMaterialStockItemId
                it[RecipeIngredientsTable.quantity] = ingredient.quantity
                it[RecipeIngredientsTable.unitId] = ingredient.unitId
            }
        }

        RecipeSummary(id, code, request.name.trim(), request.sourcing, request.yieldQty, "ACTIVE", request.ingredients)
    }

    /** Recipes, paged; the page's ingredients come from one query. */
    fun listRecipes(locationId: UUID?, status: String?, paging: PageRequest): PageResult<RecipeSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        val query = RecipesTable.selectAll().andWhere { RecipesTable.tenantId eq tenantId }
        if (effectiveLocationId != null) query.andWhere { RecipesTable.locationId inList effectiveLocationId }
        if (status != null) query.andWhere { RecipesTable.status eq status }
        val page = Paging.fetch(query.orderBy(RecipesTable.name to SortOrder.ASC, RecipesTable.id to SortOrder.ASC), paging) { it }
        val ingredientsByRecipe = RecipeIngredientsTable.selectAll()
            .where { RecipeIngredientsTable.recipeId inList page.items.map { it[RecipesTable.id].value } }
            .groupBy({ it[RecipeIngredientsTable.recipeId].value }) {
                RecipeIngredientInput(it[RecipeIngredientsTable.rawMaterialStockItemId].value, it[RecipeIngredientsTable.quantity], it[RecipeIngredientsTable.unitId])
            }
        page.withItems(page.items.map { row ->
            val ingredients = ingredientsByRecipe[row[RecipesTable.id].value].orEmpty()
            RecipeSummary(
                id = row[RecipesTable.id].value,
                code = row[RecipesTable.code],
                name = row[RecipesTable.name],
                sourcing = row[RecipesTable.sourcing],
                yieldQty = row[RecipesTable.yieldQty],
                status = row[RecipesTable.status],
                ingredients = ingredients,
            )
        })
    }

    // ---- Production Cost Calculator ----

    fun createCostCalculation(request: ProductionCostingRequest): ProductionCostingSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.locationId)

        val materialLines: List<MaterialLineInput> = request.materials?.takeIf(List<MaterialLineInput>::isNotEmpty)
            ?: request.recipeId?.let { recipeId ->
                val recipe = RecipesTable.selectAll().andWhere { RecipesTable.id eq recipeId }.firstOrNull()
                    ?: throw ApiErrors.recipeNotFound()
                val ratio = request.batchSizeQty.divide(recipe[RecipesTable.yieldQty], 6, java.math.RoundingMode.HALF_UP)
                RecipeIngredientsTable.selectAll().andWhere { RecipeIngredientsTable.recipeId eq recipeId }.map {
                    MaterialLineInput(it[RecipeIngredientsTable.rawMaterialStockItemId].value, it[RecipeIngredientsTable.quantity] * ratio)
                }
            }
            ?: throw ApiErrors.materialsRequired()

        var materialsCost = BigDecimal.ZERO
        val materialsSnapshot: JsonSnapshot = materialLines.map { line ->
            val item = StockItemsTable.selectAll().andWhere { StockItemsTable.id eq line.stockItemId }.firstOrNull()
                ?: throw ApiErrors.materialNotFound(line.stockItemId)
            val unitCost = item[StockItemsTable.landingCost] ?: BigDecimal.ZERO
            materialsCost += unitCost * line.quantity
            mapOf(
                "stockItemId" to line.stockItemId.toString(),
                "name" to item[StockItemsTable.name],
                "quantity" to line.quantity,
                "unitCost" to unitCost,
            )
        }

        val overheadTotal = request.overheads.fold(BigDecimal.ZERO) { acc, o -> acc + o.amount }
        val overheadSnapshot: JsonSnapshot = request.overheads.map { mapOf("label" to it.label, "amount" to it.amount) }

        val computedTotalCost = materialsCost + overheadTotal
        val computedUnitCost = computedTotalCost.divide(request.batchSizeQty, 2, java.math.RoundingMode.HALF_UP)

        val id = ProductionCostingsTable.insertAndGetId {
            it[ProductionCostingsTable.tenantId] = actor.tenantId
            it[ProductionCostingsTable.locationId] = request.locationId
            it[ProductionCostingsTable.targetStockItemId] = request.targetStockItemId
            it[ProductionCostingsTable.recipeId] = request.recipeId
            it[ProductionCostingsTable.batchSizeQty] = request.batchSizeQty
            it[ProductionCostingsTable.materialsSnapshot] = materialsSnapshot
            it[ProductionCostingsTable.overheadSnapshot] = overheadSnapshot
            it[ProductionCostingsTable.computedUnitCost] = computedUnitCost
            it[ProductionCostingsTable.computedTotalCost] = computedTotalCost
            it[ProductionCostingsTable.status] = "PENDING"
            it[ProductionCostingsTable.computedByUserId] = actor.userId
            it[ProductionCostingsTable.createdAt] = Instant.now()
        }.value

        ProductionCostingSummary(id, request.targetStockItemId, request.batchSizeQty, "PENDING", actor.userId, Instant.now())
    }

    fun getCostingDetail(id: UUID): ProductionCostingDetail = tenantScopedTransaction.execute {
        val row = ProductionCostingsTable.selectAll().andWhere { ProductionCostingsTable.id eq id }.firstOrNull()
            ?: throw ApiErrors.costingNotFound()
        locationAccessService.assertAccess(row[ProductionCostingsTable.locationId])
        rowToCostingDetail(row)
    }

    private fun rowToCostingDetail(row: ResultRow) = ProductionCostingDetail(
        id = row[ProductionCostingsTable.id].value,
        targetStockItemId = row[ProductionCostingsTable.targetStockItemId].value,
        recipeId = row[ProductionCostingsTable.recipeId]?.value,
        batchSizeQty = row[ProductionCostingsTable.batchSizeQty],
        materialsSnapshot = row[ProductionCostingsTable.materialsSnapshot],
        overheadSnapshot = row[ProductionCostingsTable.overheadSnapshot],
        computedUnitCost = row[ProductionCostingsTable.computedUnitCost],
        computedTotalCost = row[ProductionCostingsTable.computedTotalCost],
        status = row[ProductionCostingsTable.status],
        computedByUserId = row[ProductionCostingsTable.computedByUserId].value,
        approvedByUserId = row[ProductionCostingsTable.approvedByUserId]?.value,
        approvedAt = row[ProductionCostingsTable.approvedAt],
        rejectionReason = row[ProductionCostingsTable.rejectionReason],
    )

    fun approveCosting(id: UUID): ProductionCostingDetail = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val current = ProductionCostingsTable.selectAll().andWhere { ProductionCostingsTable.id eq id }.firstOrNull()
            ?: throw ApiErrors.costingNotFound()
        locationAccessService.assertAccess(current[ProductionCostingsTable.locationId])
        if (current[ProductionCostingsTable.status] != "PENDING") {
            throw ApiErrors.costingAlreadyDecided()
        }
        ProductionCostingsTable.update({ ProductionCostingsTable.id eq id }) {
            it[ProductionCostingsTable.status] = "APPROVED"
            it[ProductionCostingsTable.approvedByUserId] = actor.userId
            it[ProductionCostingsTable.approvedAt] = Instant.now()
        }
        rowToCostingDetail(ProductionCostingsTable.selectAll().andWhere { ProductionCostingsTable.id eq id }.single())
    }

    fun rejectCosting(id: UUID, reason: String): ProductionCostingDetail = tenantScopedTransaction.execute {
        val current = ProductionCostingsTable.selectAll().andWhere { ProductionCostingsTable.id eq id }.firstOrNull()
            ?: throw ApiErrors.costingNotFound()
        locationAccessService.assertAccess(current[ProductionCostingsTable.locationId])
        if (current[ProductionCostingsTable.status] != "PENDING") {
            throw ApiErrors.costingAlreadyDecided()
        }
        ProductionCostingsTable.update({ ProductionCostingsTable.id eq id }) {
            it[ProductionCostingsTable.status] = "REJECTED"
            it[ProductionCostingsTable.rejectionReason] = reason
        }
        rowToCostingDetail(ProductionCostingsTable.selectAll().andWhere { ProductionCostingsTable.id eq id }.single())
    }

    // ---- Production Runs ----

    fun commitProductionRun(request: ProductionRunRequest): ProductionRunSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val costing = ProductionCostingsTable.selectAll()
            .andWhere { ProductionCostingsTable.id eq request.productionCostingId }
            .firstOrNull() ?: throw ApiErrors.costingNotFound()
        if (costing[ProductionCostingsTable.status] != "APPROVED") {
            throw ApiErrors.costingNotApproved()
        }

        val locationId = costing[ProductionCostingsTable.locationId]
        locationAccessService.assertAccess(locationId)
        val batchRef = documentSequenceService.next("PB")
        val materialsSnapshot = costing[ProductionCostingsTable.materialsSnapshot]

        @Suppress("UNCHECKED_CAST")
        val materialLines = materialsSnapshot.map { line ->
            UUID.fromString(line["stockItemId"] as String) to BigDecimal(line["quantity"].toString())
        }
        materialLines.forEach { (stockItemId, quantity) ->
            if (stockLedgerRepository.currentBalance(locationId, stockItemId) < quantity) {
                throw ApiErrors.insufficientStockForProduction(stockItemId)
            }
        }

        materialLines.forEach { (stockItemId, quantity) ->
            stockLedgerRepository.postEntry(
                tenantId = actor.tenantId,
                locationId = locationId,
                stockItemId = stockItemId,
                sourceType = "PRODUCTION_RUN",
                sourceReference = batchRef,
                qtyOut = quantity,
                performedByUserId = actor.userId,
            )
        }

        stockLedgerRepository.postEntry(
            tenantId = actor.tenantId,
            locationId = locationId,
            stockItemId = costing[ProductionCostingsTable.targetStockItemId].value,
            sourceType = "PRODUCTION_RUN",
            sourceReference = batchRef,
            qtyIn = costing[ProductionCostingsTable.batchSizeQty],
            unitCost = costing[ProductionCostingsTable.computedUnitCost],
            performedByUserId = actor.userId,
        )

        ProductionRunsTable.insert {
            it[ProductionRunsTable.tenantId] = actor.tenantId
            it[ProductionRunsTable.locationId] = locationId
            it[ProductionRunsTable.batchRef] = batchRef
            it[ProductionRunsTable.productionCostingId] = request.productionCostingId
            it[ProductionRunsTable.quantityProduced] = costing[ProductionCostingsTable.batchSizeQty]
            it[ProductionRunsTable.unitLandingCost] = costing[ProductionCostingsTable.computedUnitCost]
            it[ProductionRunsTable.totalLandingCost] = costing[ProductionCostingsTable.computedTotalCost]
            it[ProductionRunsTable.materialsUsed] = materialsSnapshot
            it[ProductionRunsTable.runByUserId] = actor.userId
            it[ProductionRunsTable.runAt] = Instant.now()
        }

        // Keep the finished product's master-record landing cost in sync with its most recent
        // production run, so the Comprehensive Stock Report reflects real cost (per authorization)
        // rather than whatever was set (or left null) at stock_items registration time.
        StockItemsTable.update({ StockItemsTable.id eq costing[ProductionCostingsTable.targetStockItemId] }) {
            it[StockItemsTable.landingCost] = costing[ProductionCostingsTable.computedUnitCost]
        }

        val canSeeCost = permissionAuthorizer.hasPermission(actor.userId, "factory.costing.read")
        ProductionRunSummary(
            batchRef = batchRef,
            targetStockItemId = costing[ProductionCostingsTable.targetStockItemId].value,
            quantityProduced = costing[ProductionCostingsTable.batchSizeQty],
            unitLandingCost = costVisibility.maskIfUnauthorized(costing[ProductionCostingsTable.computedUnitCost], canSeeCost, BigDecimal.ZERO),
            totalLandingCost = costVisibility.maskIfUnauthorized(costing[ProductionCostingsTable.computedTotalCost], canSeeCost, BigDecimal.ZERO),
            runAt = Instant.now(),
        )
    }

    /** Production runs, newest first, paged; `from`/`to` bound the run date in the tenant's timezone. */
    fun listProductionRuns(locationId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageRequest): PageResult<ProductionRunSummary> =
        tenantScopedTransaction.execute {
            val actor = currentActorProvider.current()
            val canSeeCost = permissionAuthorizer.hasPermission(actor.userId, "factory.costing.read")
            val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
            val zone = moduleSettings.zone()
            val query = ProductionRunsTable.selectAll().andWhere { ProductionRunsTable.tenantId eq actor.tenantId }
            if (effectiveLocationId != null) query.andWhere { ProductionRunsTable.locationId inList effectiveLocationId }
            if (from != null) query.andWhere { ProductionRunsTable.runAt greaterEq from.atStartOfDay(zone).toInstant() }
            if (to != null) query.andWhere { ProductionRunsTable.runAt less to.plusDays(1).atStartOfDay(zone).toInstant() }
            Paging.fetch(query.orderBy(ProductionRunsTable.runAt to SortOrder.DESC, ProductionRunsTable.id to SortOrder.DESC), paging) { row ->
                ProductionRunSummary(
                    batchRef = row[ProductionRunsTable.batchRef],
                    targetStockItemId = row[ProductionRunsTable.productionCostingId].value,
                    quantityProduced = row[ProductionRunsTable.quantityProduced],
                    unitLandingCost = costVisibility.maskIfUnauthorized(row[ProductionRunsTable.unitLandingCost], canSeeCost, BigDecimal.ZERO),
                    totalLandingCost = costVisibility.maskIfUnauthorized(row[ProductionRunsTable.totalLandingCost], canSeeCost, BigDecimal.ZERO),
                    runAt = row[ProductionRunsTable.runAt],
                )
            }
        }

    fun totalCostForRecipes(recipeIds: List<UUID>): List<RecipeCostAggregate> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        recipeIds.map { recipeId ->
            val recipe = RecipesTable.selectAll().andWhere { RecipesTable.id eq recipeId }.single()
            val runs = ProductionRunsTable
                .join(ProductionCostingsTable, org.jetbrains.exposed.sql.JoinType.INNER) { ProductionRunsTable.productionCostingId eq ProductionCostingsTable.id }
                .selectAll()
                .andWhere { ProductionRunsTable.tenantId eq tenantId }
                .andWhere { ProductionCostingsTable.recipeId eq recipeId }
                .toList()
            RecipeCostAggregate(
                recipeId = recipeId,
                recipeName = recipe[RecipesTable.name],
                qtyUsed = runs.fold(BigDecimal.ZERO) { acc, row -> acc + row[ProductionRunsTable.quantityProduced] },
                totalCost = runs.fold(BigDecimal.ZERO) { acc, row -> acc + row[ProductionRunsTable.totalLandingCost] },
            )
        }
    }

    // ---- Special Production ----

    fun registerSpecialClient(request: SpecialProductionClientRequest): SpecialProductionClientSummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val id = SpecialProductionClientsTable.insertAndGetId {
            it[SpecialProductionClientsTable.tenantId] = tenantId
            it[SpecialProductionClientsTable.name] = request.name.trim()
            it[SpecialProductionClientsTable.terms] = request.terms
            it[SpecialProductionClientsTable.createdAt] = Instant.now()
        }.value
        SpecialProductionClientSummary(id, request.name.trim(), request.terms)
    }

    fun createSpecialProductionRun(request: SpecialProductionRunRequest): SpecialProductionRunSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.locationId)
        val orderRef = documentSequenceService.next("SO")
        val batchRef = documentSequenceService.next("SB")

        val materialsSnapshot: JsonSnapshot = request.materials.map {
            mapOf("stockItemId" to it.stockItemId.toString(), "quantity" to it.quantity)
        }

        request.materials.forEach { line ->
            if (stockLedgerRepository.currentBalance(request.locationId, line.stockItemId) < line.quantity) {
                throw ApiErrors.insufficientStockForProduction(line.stockItemId)
            }
        }

        val id = SpecialProductionRunsTable.insertAndGetId {
            it[SpecialProductionRunsTable.tenantId] = actor.tenantId
            it[SpecialProductionRunsTable.locationId] = request.locationId
            it[SpecialProductionRunsTable.orderRef] = orderRef
            it[SpecialProductionRunsTable.batchRef] = batchRef
            it[SpecialProductionRunsTable.clientId] = request.clientId
            it[SpecialProductionRunsTable.targetStockItemId] = request.targetStockItemId
            it[SpecialProductionRunsTable.productDescription] = request.productDescription
            it[SpecialProductionRunsTable.quantity] = request.quantity
            it[SpecialProductionRunsTable.unitId] = request.unitId
            it[SpecialProductionRunsTable.unitCost] = request.unitCost
            it[SpecialProductionRunsTable.price] = request.price
            it[SpecialProductionRunsTable.materialsSnapshot] = materialsSnapshot
            it[SpecialProductionRunsTable.status] = "IN_PRODUCTION"
            it[SpecialProductionRunsTable.createdByUserId] = actor.userId
            it[SpecialProductionRunsTable.createdAt] = Instant.now()
        }.value

        request.materials.forEach { line ->
            stockLedgerRepository.postEntry(
                tenantId = actor.tenantId,
                locationId = request.locationId,
                stockItemId = line.stockItemId,
                sourceType = "PRODUCTION_RUN",
                sourceReference = batchRef,
                qtyOut = line.quantity,
                performedByUserId = actor.userId,
            )
        }

        SpecialProductionRunSummary(id, orderRef, batchRef, request.clientId, request.quantity, request.price, "IN_PRODUCTION")
    }

    fun updateSpecialProductionStatus(id: UUID, status: String): SpecialProductionRunSummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val existing = SpecialProductionRunsTable.selectAll()
            .andWhere { (SpecialProductionRunsTable.id eq id) and (SpecialProductionRunsTable.tenantId eq tenantId) }
            .firstOrNull() ?: throw ApiErrors.specialProductionRunNotFound()
        locationAccessService.assertAccess(existing[SpecialProductionRunsTable.locationId])

        SpecialProductionRunsTable.update({
            (SpecialProductionRunsTable.id eq id) and (SpecialProductionRunsTable.tenantId eq tenantId)
        }) {
            it[SpecialProductionRunsTable.status] = status
        }
        val row = SpecialProductionRunsTable.selectAll().andWhere { SpecialProductionRunsTable.id eq id }.single()
        SpecialProductionRunSummary(
            id, row[SpecialProductionRunsTable.orderRef], row[SpecialProductionRunsTable.batchRef],
            row[SpecialProductionRunsTable.clientId].value, row[SpecialProductionRunsTable.quantity],
            row[SpecialProductionRunsTable.price], row[SpecialProductionRunsTable.status],
        )
    }

    fun listSpecialProductionRuns(locationId: UUID?, clientId: UUID?, paging: PageRequest): PageResult<SpecialProductionRunSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        val query = SpecialProductionRunsTable.selectAll().andWhere { SpecialProductionRunsTable.tenantId eq tenantId }
        if (effectiveLocationId != null) query.andWhere { SpecialProductionRunsTable.locationId inList effectiveLocationId }
        if (clientId != null) query.andWhere { SpecialProductionRunsTable.clientId eq clientId }
        Paging.fetch(query.orderBy(SpecialProductionRunsTable.createdAt to SortOrder.DESC, SpecialProductionRunsTable.id to SortOrder.DESC), paging) { row ->
            SpecialProductionRunSummary(
                row[SpecialProductionRunsTable.id].value, row[SpecialProductionRunsTable.orderRef], row[SpecialProductionRunsTable.batchRef],
                row[SpecialProductionRunsTable.clientId].value, row[SpecialProductionRunsTable.quantity],
                row[SpecialProductionRunsTable.price], row[SpecialProductionRunsTable.status],
            )
        }
    }
}
