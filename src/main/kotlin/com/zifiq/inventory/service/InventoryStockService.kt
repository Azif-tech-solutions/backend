package com.zifiq.inventory.service

import com.zifiq.inventory.dto.*
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import com.zifiq.inventory.model.StockReceiptsTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.tax.TaxClassificationRepository
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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

private fun rowToStockItemSummary(row: ResultRow) = StockItemSummary(
    id = row[StockItemsTable.id].value,
    locationId = row[StockItemsTable.locationId],
    itemType = row[StockItemsTable.itemType],
    code = row[StockItemsTable.code],
    name = row[StockItemsTable.name],
    categoryId = row[StockItemsTable.categoryId],
    brand = row[StockItemsTable.brand],
    unitOfMeasureId = row[StockItemsTable.unitOfMeasureId],
    reorderLevel = row[StockItemsTable.reorderLevel],
    barcode = row[StockItemsTable.barcode],
    landingCost = row[StockItemsTable.landingCost],
    markupCost = row[StockItemsTable.markupCost],
    vatCategoryId = row[StockItemsTable.vatCategoryId],
    hsnSectionId = row[StockItemsTable.hsnSectionId],
    isicSectionId = row[StockItemsTable.isicSectionId],
    status = row[StockItemsTable.status],
    createdAt = row[StockItemsTable.createdAt],
)

@Service
class InventoryStockService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val documentSequenceService: DocumentSequenceService,
    private val stockLedgerRepository: StockLedgerRepository,
    private val costVisibility: CostVisibility,
    private val taxClassificationRepository: TaxClassificationRepository,
    private val locationAccessService: LocationAccessService,
    private val locationHierarchy: com.zifiq.platform.tenancy.LocationHierarchy,
    private val domainEvents: com.zifiq.platform.events.DomainEvents,
    private val stockItemCodes: StockItemCodes,
) {
    fun listStockItems(locationId: UUID?, itemType: String?, status: String?): List<StockItemSummary> =
        tenantScopedTransaction.execute {
            val tenantId = currentActorProvider.current().tenantId
            val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
            val query = StockItemsTable.selectAll().andWhere { StockItemsTable.tenantId eq tenantId }
            if (effectiveLocationId != null) query.andWhere { StockItemsTable.locationId inList effectiveLocationId }
            if (itemType != null) query.andWhere { StockItemsTable.itemType eq itemType }
            if (status != null) query.andWhere { StockItemsTable.status eq status }
            query.orderBy(StockItemsTable.name).map(::rowToStockItemSummary)
        }

    fun registerStockItem(request: StockItemRequest): StockItemSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.locationId)
        val code = request.code?.trim()?.takeIf(String::isNotEmpty) ?: stockItemCodes.next()

        if (!taxClassificationRepository.isVatCategoryActiveForTenant(actor.tenantId, request.vatCategoryId)) {
            throw ApiErrors.vatCategoryNotActive()
        }
        if (!taxClassificationRepository.hsnSectionExists(request.hsnSectionId)) {
            throw ApiErrors.unknownHsnSection()
        }
        if (!taxClassificationRepository.isicSectionExists(request.isicSectionId)) {
            throw ApiErrors.unknownIsicSection()
        }

        val id = StockItemsTable.insertAndGetId {
            it[StockItemsTable.tenantId] = actor.tenantId
            it[StockItemsTable.locationId] = request.locationId
            it[StockItemsTable.itemType] = request.itemType
            it[StockItemsTable.code] = code
            it[StockItemsTable.name] = request.name.trim()
            it[StockItemsTable.categoryId] = request.categoryId
            it[StockItemsTable.brand] = request.brand?.trim()?.takeIf(String::isNotEmpty)
            it[StockItemsTable.unitOfMeasureId] = request.unitOfMeasureId
            it[StockItemsTable.reorderLevel] = request.reorderLevel ?: BigDecimal.ZERO
            it[StockItemsTable.barcode] = request.barcode?.trim()?.takeIf(String::isNotEmpty)
            it[StockItemsTable.landingCost] = request.landingCost
            it[StockItemsTable.markupCost] = request.markupCost
            it[StockItemsTable.vatCategoryId] = request.vatCategoryId
            it[StockItemsTable.hsnSectionId] = request.hsnSectionId
            it[StockItemsTable.isicSectionId] = request.isicSectionId
            it[StockItemsTable.status] = "ACTIVE"
            it[StockItemsTable.createdByUserId] = actor.userId
            it[StockItemsTable.createdAt] = Instant.now()
        }.value

        domainEvents.record(
            type = "inventory.stock-item.registered", aggregateType = "stock-item", aggregateId = id, aggregateRef = code,
            summary = "Stock item $code registered — ${request.name.trim()}", payload = mapOf("itemType" to request.itemType), locationId = request.locationId,
        )
        StockItemsTable.selectAll().andWhere { StockItemsTable.id eq id }.single().let(::rowToStockItemSummary)
    }

    fun updateStockItemStatus(id: UUID, status: String): StockItemSummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val existing = StockItemsTable.selectAll()
            .andWhere { (StockItemsTable.id eq id) and (StockItemsTable.tenantId eq tenantId) }
            .firstOrNull() ?: throw ApiErrors.stockItemNotFound()
        locationAccessService.assertAccess(existing[StockItemsTable.locationId])

        StockItemsTable.update({ (StockItemsTable.id eq id) and (StockItemsTable.tenantId eq tenantId) }) {
            it[StockItemsTable.status] = status
        }
        domainEvents.record(
            type = "inventory.stock-item.status-changed", aggregateType = "stock-item", aggregateId = id, aggregateRef = existing[StockItemsTable.code],
            summary = "Stock item ${existing[StockItemsTable.code]} ${status.lowercase()}", payload = mapOf("status" to status), locationId = existing[StockItemsTable.locationId],
        )
        StockItemsTable.selectAll().andWhere { StockItemsTable.id eq id }.single().let(::rowToStockItemSummary)
    }

    private fun requireActiveItem(itemId: UUID): ResultRow {
        val row = StockItemsTable.selectAll().andWhere { StockItemsTable.id eq itemId }.firstOrNull()
            ?: throw ApiErrors.stockItemNotFound()
        if (row[StockItemsTable.status] != "ACTIVE") {
            throw ApiErrors.stockItemSuspended()
        }
        return row
    }

    fun refillFinishedProduct(request: FinishedProductRefillRequest): RefillSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.locationId)
        requireActiveItem(request.stockItemId)
        val ref = documentSequenceService.next("RF")

        stockLedgerRepository.postEntry(
            tenantId = actor.tenantId,
            locationId = request.locationId,
            stockItemId = request.stockItemId,
            sourceType = "REFILL",
            sourceReference = ref,
            qtyIn = request.quantity,
            remark = request.note,
            performedByUserId = actor.userId,
        )

        domainEvents.record(
            type = "inventory.refill.posted", aggregateType = "stock-refill", aggregateId = request.stockItemId, aggregateRef = ref,
            summary = "$ref: ${request.quantity.stripTrailingZeros().toPlainString()} finished product received into stock",
            payload = mapOf("quantity" to request.quantity), locationId = request.locationId,
        )
        RefillSummary(ref, request.stockItemId, request.quantity, actor.userId, Instant.now())
    }

    fun refillRawMaterial(request: RawMaterialRefillRequest): StockReceiptSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.locationId)
        requireActiveItem(request.stockItemId)
        val ref = documentSequenceService.next("DL")

        val id = StockReceiptsTable.insertAndGetId {
            it[StockReceiptsTable.tenantId] = actor.tenantId
            it[StockReceiptsTable.locationId] = request.locationId
            it[StockReceiptsTable.ref] = ref
            it[StockReceiptsTable.stockItemId] = request.stockItemId
            it[StockReceiptsTable.supplierId] = request.supplierId
            it[StockReceiptsTable.manufacturerId] = request.manufacturerId
            it[StockReceiptsTable.quantity] = request.quantity
            it[StockReceiptsTable.unitCost] = request.unitCost
            it[StockReceiptsTable.manufacturedDate] = request.manufacturedDate
            it[StockReceiptsTable.expiryDate] = request.expiryDate
            it[StockReceiptsTable.condition] = "PENDING_VERIFICATION"
            it[StockReceiptsTable.createdByUserId] = actor.userId
            it[StockReceiptsTable.createdAt] = Instant.now()
        }.value

        stockLedgerRepository.postEntry(
            tenantId = actor.tenantId,
            locationId = request.locationId,
            stockItemId = request.stockItemId,
            sourceType = "RECEIVE",
            sourceReference = ref,
            qtyIn = request.quantity,
            unitCost = request.unitCost,
            performedByUserId = actor.userId,
        )

        domainEvents.record(
            type = "inventory.receipt.posted", aggregateType = "stock-receipt", aggregateId = id, aggregateRef = ref,
            summary = "$ref: ${request.quantity.stripTrailingZeros().toPlainString()} raw material received, pending verification",
            payload = mapOf("quantity" to request.quantity), locationId = request.locationId,
        )
        rowToReceiptSummary(StockReceiptsTable.selectAll().andWhere { StockReceiptsTable.id eq id }.single())
    }

    fun listReceipts(locationId: UUID?, condition: String?): List<StockReceiptSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        val query = StockReceiptsTable.selectAll().andWhere { StockReceiptsTable.tenantId eq tenantId }
        if (effectiveLocationId != null) query.andWhere { StockReceiptsTable.locationId inList effectiveLocationId }
        if (condition != null) query.andWhere { StockReceiptsTable.condition eq condition }
        query.orderBy(StockReceiptsTable.createdAt).map(::rowToReceiptSummary)
    }

    fun verifyReceipt(id: UUID, condition: String): StockReceiptSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val existing = StockReceiptsTable.selectAll()
            .andWhere { (StockReceiptsTable.id eq id) and (StockReceiptsTable.tenantId eq actor.tenantId) }
            .firstOrNull() ?: throw ApiErrors.stockReceiptNotFound()
        locationAccessService.assertAccess(existing[StockReceiptsTable.locationId])

        val updated = StockReceiptsTable.update({
            (StockReceiptsTable.id eq id) and (StockReceiptsTable.tenantId eq actor.tenantId)
        }) {
            it[StockReceiptsTable.condition] = condition
            it[StockReceiptsTable.verifiedByUserId] = actor.userId
            it[StockReceiptsTable.offloadedAt] = Instant.now()
        }
        if (updated == 0) throw ApiErrors.stockReceiptNotFound()
        domainEvents.record(
            type = "inventory.receipt.verified", aggregateType = "stock-receipt", aggregateId = id, aggregateRef = existing[StockReceiptsTable.ref],
            summary = "${existing[StockReceiptsTable.ref]} verified: ${condition.lowercase().replace('_', ' ')}",
            payload = mapOf("condition" to condition), locationId = existing[StockReceiptsTable.locationId],
        )
        rowToReceiptSummary(StockReceiptsTable.selectAll().andWhere { StockReceiptsTable.id eq id }.single())
    }

    private fun rowToReceiptSummary(row: ResultRow) = StockReceiptSummary(
        id = row[StockReceiptsTable.id].value,
        ref = row[StockReceiptsTable.ref],
        stockItemId = row[StockReceiptsTable.stockItemId].value,
        supplierId = row[StockReceiptsTable.supplierId],
        manufacturerId = row[StockReceiptsTable.manufacturerId],
        quantity = row[StockReceiptsTable.quantity],
        unitCost = row[StockReceiptsTable.unitCost],
        manufacturedDate = row[StockReceiptsTable.manufacturedDate],
        expiryDate = row[StockReceiptsTable.expiryDate],
        verifiedByUserId = row[StockReceiptsTable.verifiedByUserId]?.value,
        offloadedAt = row[StockReceiptsTable.offloadedAt],
        condition = row[StockReceiptsTable.condition],
    )

    /**
     * Stock Card: every movement of one item with a running balance. A factory or showroom
     * includes its storage areas unless [includeAreas] is false; an area is just itself.
     */
    fun stockCard(stockItemId: UUID, locationId: UUID, from: LocalDate?, to: LocalDate?, includeAreas: Boolean = true): List<StockCardRow> =
        tenantScopedTransaction.execute {
            val tenantId = currentActorProvider.current().tenantId
            locationAccessService.assertAccess(locationId)
            val locations = if (includeAreas) locationHierarchy.withAreas(locationId) else setOf(locationId)
            val openingBalance = if (from != null) {
                StockLedgerEntriesTable.selectAll()
                    .andWhere { StockLedgerEntriesTable.tenantId eq tenantId }
                    .andWhere { StockLedgerEntriesTable.locationId inList locations }
                    .andWhere { StockLedgerEntriesTable.stockItemId eq stockItemId }
                    .andWhere { StockLedgerEntriesTable.entryDate less from }
                    .fold(BigDecimal.ZERO) { acc, row -> acc + row[StockLedgerEntriesTable.qtyIn] - row[StockLedgerEntriesTable.qtyOut] }
            } else {
                BigDecimal.ZERO
            }

            var running = openingBalance
            var sn = 0
            val query = StockLedgerEntriesTable.selectAll()
                .andWhere { StockLedgerEntriesTable.tenantId eq tenantId }
                .andWhere { StockLedgerEntriesTable.locationId inList locations }
                .andWhere { StockLedgerEntriesTable.stockItemId eq stockItemId }
            if (from != null) query.andWhere { StockLedgerEntriesTable.entryDate greaterEq from }
            if (to != null) query.andWhere { StockLedgerEntriesTable.entryDate lessEq to }

            query.orderBy(StockLedgerEntriesTable.entryDate to SortOrder.ASC, StockLedgerEntriesTable.createdAt to SortOrder.ASC).map { row ->
                sn += 1
                running += row[StockLedgerEntriesTable.qtyIn] - row[StockLedgerEntriesTable.qtyOut]
                StockCardRow(
                    sn = sn,
                    date = row[StockLedgerEntriesTable.entryDate],
                    remark = row[StockLedgerEntriesTable.remark] ?: row[StockLedgerEntriesTable.sourceReference],
                    qtyIn = row[StockLedgerEntriesTable.qtyIn],
                    qtyOut = row[StockLedgerEntriesTable.qtyOut],
                    runningBalance = running,
                )
            }
        }

    fun binCard(stockItemId: UUID, locationId: UUID, from: LocalDate?, to: LocalDate?, includeAreas: Boolean = true): List<BinCardRow> {
        val transactions = stockCard(stockItemId, locationId, from, to, includeAreas)
        val byDay = transactions.groupBy { it.date }.toSortedMap()
        var running = transactions.firstOrNull()?.let { it.runningBalance - it.qtyIn + it.qtyOut } ?: BigDecimal.ZERO
        var sn = 0
        return byDay.map { (date, rows) ->
            sn += 1
            val qtyIn = rows.sumOf { it.qtyIn }
            val qtyOut = rows.sumOf { it.qtyOut }
            running += qtyIn - qtyOut
            BinCardRow(sn, date, qtyIn, qtyOut, running)
        }
    }

    fun comprehensiveStockReport(locationId: UUID?, view: String): List<ComprehensiveStockRow> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        // factory.costing.read (not an Inventory-owned code) deliberately gates cost visibility
        // here too: cost figures ultimately derive from Factory's production costing, so Inventory
        // reuses that permission string rather than minting a parallel inventory.costing.read.
        val canSeeCost = permissionAuthorizer.hasPermission(currentActorProvider.current().userId, "factory.costing.read")

        // Joined/filtered on StockBalancesTable.locationId, not StockItemsTable.locationId: a
        // stock item's "home" registration location and where it currently carries a balance are
        // different things once stock moves via transfer/trade — the report must reflect the
        // latter. When no locationId filter is given, INNER-join so an item's row appears once
        // per location it actually has stock at (rather than home-location-only).
        val joinType = if (effectiveLocationId != null) org.jetbrains.exposed.sql.JoinType.INNER else org.jetbrains.exposed.sql.JoinType.LEFT
        val itemsQuery = StockItemsTable
            .join(StockBalancesTable, joinType) { StockItemsTable.id eq StockBalancesTable.stockItemId }
            .selectAll()
            .andWhere { StockItemsTable.tenantId eq tenantId }
        if (effectiveLocationId != null) itemsQuery.andWhere { StockBalancesTable.locationId inList effectiveLocationId }
        if (view == "GROUP") itemsQuery.andWhere { StockItemsTable.itemType eq "FINISHED_PRODUCT" }

        itemsQuery.orderBy(StockItemsTable.name).mapIndexed { index, row ->
            val qty = row.getOrNull(StockBalancesTable.quantityOnHand) ?: BigDecimal.ZERO
            val unitCost = row[StockItemsTable.landingCost] ?: BigDecimal.ZERO
            ComprehensiveStockRow(
                lineNumber = index + 1,
                groupingLabel = row[StockItemsTable.categoryId]?.toString() ?: "Uncategorized",
                stockDetail = row[StockItemsTable.name],
                unitCost = costVisibility.maskIfUnauthorized(unitCost, canSeeCost, BigDecimal.ZERO),
                quantityLeft = qty,
                totalCost = costVisibility.maskIfUnauthorized(unitCost * qty, canSeeCost, BigDecimal.ZERO),
            )
        }
    }

    /**
     * Factory & Production > Reports: a cross-item, cross-movement-type ledger view (unlike Stock
     * Card, which is scoped to a single item). Every stock-affecting action across every module
     * writes to StockLedgerEntriesTable, so this report is just that table filtered and joined to
     * the item it affected -- see docs/02-factory.md §3.10 and com.zifiq.inventory.repository.
     * StockLedgerRepository's own doc comment on why it's the single sanctioned write path.
     */
    fun stockMovementReport(
        locationId: UUID?,
        stockItemId: UUID?,
        sourceType: String?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<StockMovementRow> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        val query = StockLedgerEntriesTable
            .join(StockItemsTable, org.jetbrains.exposed.sql.JoinType.INNER) { StockLedgerEntriesTable.stockItemId eq StockItemsTable.id }
            .selectAll()
            .andWhere { StockLedgerEntriesTable.tenantId eq tenantId }
        if (effectiveLocationId != null) query.andWhere { StockLedgerEntriesTable.locationId inList effectiveLocationId }
        if (stockItemId != null) query.andWhere { StockLedgerEntriesTable.stockItemId eq stockItemId }
        if (sourceType != null) query.andWhere { StockLedgerEntriesTable.sourceType eq sourceType }
        if (from != null) query.andWhere { StockLedgerEntriesTable.entryDate greaterEq from }
        if (to != null) query.andWhere { StockLedgerEntriesTable.entryDate lessEq to }

        query.orderBy(StockLedgerEntriesTable.entryDate).orderBy(StockLedgerEntriesTable.createdAt)
            .mapIndexed { index, row ->
                StockMovementRow(
                    sn = index + 1,
                    date = row[StockLedgerEntriesTable.entryDate],
                    locationId = row[StockLedgerEntriesTable.locationId],
                    stockItemId = row[StockItemsTable.id].value,
                    itemCode = row[StockItemsTable.code],
                    itemName = row[StockItemsTable.name],
                    sourceType = row[StockLedgerEntriesTable.sourceType],
                    sourceReference = row[StockLedgerEntriesTable.sourceReference],
                    qtyIn = row[StockLedgerEntriesTable.qtyIn],
                    qtyOut = row[StockLedgerEntriesTable.qtyOut],
                )
            }
    }

    fun reOrderReport(locationId: UUID?): List<ReOrderRow> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        val query = StockItemsTable
            .join(StockBalancesTable, org.jetbrains.exposed.sql.JoinType.INNER) { StockItemsTable.id eq StockBalancesTable.stockItemId }
            .selectAll()
            .andWhere { StockItemsTable.tenantId eq tenantId }
            .andWhere { StockBalancesTable.quantityOnHand lessEq StockItemsTable.reorderLevel }
        if (effectiveLocationId != null) query.andWhere { StockBalancesTable.locationId inList effectiveLocationId }

        query.map { row ->
            ReOrderRow(
                stockItemId = row[StockItemsTable.id].value,
                itemName = row[StockItemsTable.name],
                quantityOnHand = row[StockBalancesTable.quantityOnHand],
                reorderLevel = row[StockItemsTable.reorderLevel],
            )
        }
    }
}
