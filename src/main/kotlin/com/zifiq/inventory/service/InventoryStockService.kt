package com.zifiq.inventory.service

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.inventory.repository.StockSql
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.TextColumnType
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.escapeLike
import com.zifiq.common.search.RawExpression
import com.zifiq.common.paging.Paging
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.PageRequest
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
import com.zifiq.platform.tenancy.LocationHierarchy
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

private fun rowToStockItemSummary(row: ResultRow, quantityOnHand: BigDecimal) = StockItemSummary(
    id = row[StockItemsTable.id].value,
    location = Ref.location(row[StockItemsTable.locationId]),
    itemType = row[StockItemsTable.itemType],
    code = row[StockItemsTable.code],
    name = row[StockItemsTable.name],
    category = ref(row[StockItemsTable.categoryId], Ref::category),
    brand = row[StockItemsTable.brand],
    unitOfMeasure = Ref.unit(row[StockItemsTable.unitOfMeasureId]),
    reorderLevel = row[StockItemsTable.reorderLevel],
    barcode = row[StockItemsTable.barcode],
    landingCost = row[StockItemsTable.landingCost],
    markupCost = row[StockItemsTable.markupCost],
    vatCategory = Ref.vatCategory(row[StockItemsTable.vatCategoryId]),
    hsnSection = ref(row[StockItemsTable.hsnSectionId], Ref::hsnSection),
    isicSection = ref(row[StockItemsTable.isicSectionId], Ref::isicSection),
    status = row[StockItemsTable.status],
    createdAt = row[StockItemsTable.createdAt],
    quantityOnHand = quantityOnHand,
)

/**
 * Stock on hand per item, summed over [locationIds] (null = every location). Runs in the caller's
 * transaction; items with no balance row are absent (treat as zero).
 */
private fun quantitiesOnHand(tenantId: UUID, itemIds: Collection<UUID>, locationIds: Collection<UUID>?): Map<UUID, BigDecimal> {
    if (itemIds.isEmpty()) return emptyMap()
    val total = StockBalancesTable.quantityOnHand.sum()
    return StockBalancesTable.select(StockBalancesTable.stockItemId, total)
        .andWhere { StockBalancesTable.tenantId eq tenantId }
        .andWhere { StockBalancesTable.stockItemId inList itemIds }
        .apply { if (locationIds != null) andWhere { StockBalancesTable.locationId inList locationIds } }
        .groupBy(StockBalancesTable.stockItemId)
        .associate { it[StockBalancesTable.stockItemId].value to (it[total] ?: BigDecimal.ZERO) }
}

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
    /**
     * The stock register, paged (docs/03 §5.1). `q` matches the code (prefix) or the name (accent- and
     * case-insensitive, trigram-indexed). Quantities on hand are summed for the page's items only.
     */
    fun listStockItems(locationId: UUID?, itemType: String?, status: String?, q: String?, paging: PageRequest): PageResult<StockItemSummary> =
        tenantScopedTransaction.execute {
            val tenantId = currentActorProvider.current().tenantId
            val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
            val query = StockItemsTable.selectAll().andWhere { StockItemsTable.tenantId eq tenantId }
            if (effectiveLocationId != null) query.andWhere { StockItemsTable.locationId inList effectiveLocationId }
            if (itemType != null) query.andWhere { StockItemsTable.itemType eq itemType }
            if (status != null) query.andWhere { StockItemsTable.status eq status }
            q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { term ->
                query.andWhere {
                    RawOp {
                        +"(lower("; +StockItemsTable.code; +") like "; registerArgument(TextColumnType(), escapeLike(term) + "%")
                        +" or f_unaccent(lower("; +StockItemsTable.name; +")) like f_unaccent("; registerArgument(TextColumnType(), "%" + escapeLike(term) + "%"); +"))"
                    }
                }
            }
            val page = Paging.fetch(query.orderBy(StockItemsTable.name to SortOrder.ASC, StockItemsTable.id to SortOrder.ASC), paging) { it }
            // At the requested location (and its areas); with no filter, everywhere the caller can see.
            val quantities = quantitiesOnHand(tenantId, page.items.map { it[StockItemsTable.id].value }, effectiveLocationId ?: locationAccessService.allowedLocationIds())
            page.withItems(page.items.map { rowToStockItemSummary(it, quantities[it[StockItemsTable.id].value] ?: BigDecimal.ZERO) })
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
        singleSummary(id)
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
        singleSummary(id)
    }

    /** One item, with its stock at its home location (and that location's areas). */
    private fun singleSummary(id: UUID): StockItemSummary {
        val row = StockItemsTable.selectAll().andWhere { StockItemsTable.id eq id }.single()
        val home = row[StockItemsTable.locationId]
        val quantity = quantitiesOnHand(row[StockItemsTable.tenantId].value, listOf(id), locationHierarchy.withAreas(home))[id] ?: BigDecimal.ZERO
        return rowToStockItemSummary(row, quantity)
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
        RefillSummary(ref, Ref.stockItem(request.stockItemId), request.quantity, UserRef(actor.userId), Instant.now())
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

    fun listReceipts(locationId: UUID?, condition: String?, paging: PageRequest): PageResult<StockReceiptSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        val query = StockReceiptsTable.selectAll().andWhere { StockReceiptsTable.tenantId eq tenantId }
        if (effectiveLocationId != null) query.andWhere { StockReceiptsTable.locationId inList effectiveLocationId }
        if (condition != null) query.andWhere { StockReceiptsTable.condition eq condition }
        Paging.fetch(query.orderBy(StockReceiptsTable.createdAt to SortOrder.DESC, StockReceiptsTable.id to SortOrder.DESC), paging, ::rowToReceiptSummary)
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
        stockItem = Ref.stockItem(row[StockReceiptsTable.stockItemId].value),
        supplier = ref(row[StockReceiptsTable.supplierId], Ref::supplier),
        manufacturer = ref(row[StockReceiptsTable.manufacturerId], Ref::manufacturer),
        quantity = row[StockReceiptsTable.quantity],
        unitCost = row[StockReceiptsTable.unitCost],
        manufacturedDate = row[StockReceiptsTable.manufacturedDate],
        expiryDate = row[StockReceiptsTable.expiryDate],
        verifiedBy = UserRef.of(row[StockReceiptsTable.verifiedByUserId]?.value),
        offloadedAt = row[StockReceiptsTable.offloadedAt],
        condition = row[StockReceiptsTable.condition],
    )

    /**
     * Stock Card: every movement of one item with a running balance. A factory or showroom
     * includes its storage areas unless [includeAreas] is false; an area is just itself.
     */
    /**
     * Stock Card, paged: every movement of one item at a location in date order, with the running
     * balance computed in SQL (opening balance before `from` + a window sum), so a page never reads
     * the item's whole history into memory.
     */
    fun stockCard(stockItemId: UUID, locationId: UUID, from: LocalDate?, to: LocalDate?, includeAreas: Boolean = true, paging: PageRequest): PageResult<StockCardRow> =
        tenantScopedTransaction.execute {
            val tenantId = currentActorProvider.current().tenantId
            locationAccessService.assertAccess(locationId)
            val locations = if (includeAreas) locationHierarchy.withAreas(locationId) else setOf(locationId)
            val opening = openingBalance(tenantId, stockItemId, locations, from)
            val running = StockSql.runningBalance()
            val query = StockLedgerEntriesTable.select(StockLedgerEntriesTable.columns + running)
                .andWhere { StockLedgerEntriesTable.tenantId eq tenantId }
                .andWhere { StockLedgerEntriesTable.stockItemId eq stockItemId }
                .andWhere { StockLedgerEntriesTable.locationId inList locations }
            if (from != null) query.andWhere { StockLedgerEntriesTable.entryDate greaterEq from }
            if (to != null) query.andWhere { StockLedgerEntriesTable.entryDate lessEq to }
            query.orderBy(StockLedgerEntriesTable.entryDate to SortOrder.ASC, StockLedgerEntriesTable.createdAt to SortOrder.ASC, StockLedgerEntriesTable.id to SortOrder.ASC)
            Paging.fetchNumbered(query, paging) { sn, row ->
                StockCardRow(
                    sn = sn,
                    date = row[StockLedgerEntriesTable.entryDate],
                    remark = row[StockLedgerEntriesTable.remark] ?: "${row[StockLedgerEntriesTable.sourceType]} ${row[StockLedgerEntriesTable.sourceReference]}",
                    qtyIn = row[StockLedgerEntriesTable.qtyIn],
                    qtyOut = row[StockLedgerEntriesTable.qtyOut],
                    runningBalance = opening + row[running],
                )
            }
        }

    /** Bin Card, paged: daily totals with the running balance, grouped and summed in SQL. */
    fun binCard(stockItemId: UUID, locationId: UUID, from: LocalDate?, to: LocalDate?, includeAreas: Boolean = true, paging: PageRequest): PageResult<BinCardRow> =
        tenantScopedTransaction.execute {
            val tenantId = currentActorProvider.current().tenantId
            locationAccessService.assertAccess(locationId)
            val locations = if (includeAreas) locationHierarchy.withAreas(locationId) else setOf(locationId)
            val opening = openingBalance(tenantId, stockItemId, locations, from)
            val qtyIn = StockLedgerEntriesTable.qtyIn.sum()
            val qtyOut = StockLedgerEntriesTable.qtyOut.sum()
            val running = StockSql.dailyRunningBalance()
            val query = StockLedgerEntriesTable.select(StockLedgerEntriesTable.entryDate, qtyIn, qtyOut, running)
                .andWhere { StockLedgerEntriesTable.tenantId eq tenantId }
                .andWhere { StockLedgerEntriesTable.stockItemId eq stockItemId }
                .andWhere { StockLedgerEntriesTable.locationId inList locations }
            if (from != null) query.andWhere { StockLedgerEntriesTable.entryDate greaterEq from }
            if (to != null) query.andWhere { StockLedgerEntriesTable.entryDate lessEq to }
            query.groupBy(StockLedgerEntriesTable.entryDate).orderBy(StockLedgerEntriesTable.entryDate to SortOrder.ASC)
            Paging.fetchNumbered(query, paging) { sn, row ->
                BinCardRow(sn, row[StockLedgerEntriesTable.entryDate], row[qtyIn] ?: BigDecimal.ZERO, row[qtyOut] ?: BigDecimal.ZERO, opening + row[running])
            }
        }

    /** Σ(in − out) before [from], in SQL; zero when there's no `from`. */
    private fun openingBalance(tenantId: UUID, stockItemId: UUID, locations: Set<UUID>, from: LocalDate?): BigDecimal {
        if (from == null) return BigDecimal.ZERO
        val net = StockSql.net()
        return StockLedgerEntriesTable.select(net)
            .andWhere { StockLedgerEntriesTable.tenantId eq tenantId }
            .andWhere { StockLedgerEntriesTable.stockItemId eq stockItemId }
            .andWhere { StockLedgerEntriesTable.locationId inList locations }
            .andWhere { StockLedgerEntriesTable.entryDate less from }
            .single()[net] ?: BigDecimal.ZERO
    }

    /** Comprehensive Stock Report, paged; `summary` totals the whole report in SQL (costs masked without factory.costing.read). */
    fun comprehensiveStockReport(locationId: UUID?, view: String, paging: PageRequest): PageResult<ComprehensiveStockRow> = tenantScopedTransaction.execute {
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
        val joined = StockItemsTable.join(StockBalancesTable, joinType) { StockItemsTable.id eq StockBalancesTable.stockItemId }
        fun scoped(query: org.jetbrains.exposed.sql.Query) = query.apply {
            andWhere { StockItemsTable.tenantId eq tenantId }
            if (effectiveLocationId != null) andWhere { StockBalancesTable.locationId inList effectiveLocationId }
            if (view == "GROUP") andWhere { StockItemsTable.itemType eq "FINISHED_PRODUCT" }
        }
        val query = scoped(joined.selectAll()).orderBy(StockItemsTable.name to SortOrder.ASC, StockItemsTable.id to SortOrder.ASC)
        val totalQty = RawExpression<BigDecimal?>({ +"coalesce(sum("; +StockBalancesTable.quantityOnHand; +"), 0)" })
        val totalCost = RawExpression<BigDecimal?>({ +"coalesce(sum("; +StockBalancesTable.quantityOnHand; +" * coalesce("; +StockItemsTable.landingCost; +", 0)), 0)" })
        val totals = scoped(joined.select(totalQty, totalCost)).single()
        Paging.fetchNumbered(query, paging) { sn, row ->
            val qty = row.getOrNull(StockBalancesTable.quantityOnHand) ?: BigDecimal.ZERO
            val unitCost = row[StockItemsTable.landingCost] ?: BigDecimal.ZERO
            ComprehensiveStockRow(
                lineNumber = sn,
                groupingLabel = row[StockItemsTable.categoryId]?.toString() ?: "Uncategorized",
                stockDetail = row[StockItemsTable.name],
                unitCost = costVisibility.maskIfUnauthorized(unitCost, canSeeCost, BigDecimal.ZERO),
                quantityLeft = qty,
                totalCost = costVisibility.maskIfUnauthorized(unitCost * qty, canSeeCost, BigDecimal.ZERO),
            )
        }.copy(summary = mapOf(
            "totalQuantity" to (totals[totalQty] ?: BigDecimal.ZERO),
            "totalCost" to costVisibility.maskIfUnauthorized(totals[totalCost] ?: BigDecimal.ZERO, canSeeCost, BigDecimal.ZERO),
        ))
    }

    /**
     * Factory & Production > Reports: a cross-item, cross-movement-type ledger view (unlike Stock
     * Card, which is scoped to a single item). Every stock-affecting action across every module
     * writes to StockLedgerEntriesTable, so this report is just that table filtered and joined to
     * the item it affected -- see docs/implemented/02-factory.md §3.10 and com.zifiq.inventory.repository.
     * StockLedgerRepository's own doc comment on why it's the single sanctioned write path.
     */
    fun stockMovementReport(
        locationId: UUID?,
        stockItemId: UUID?,
        sourceType: String?,
        from: LocalDate?,
        to: LocalDate?,
        paging: PageRequest,
    ): PageResult<StockMovementRow> = tenantScopedTransaction.execute {
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
        query.orderBy(StockLedgerEntriesTable.entryDate to SortOrder.ASC, StockLedgerEntriesTable.createdAt to SortOrder.ASC, StockLedgerEntriesTable.id to SortOrder.ASC)
        Paging.fetchNumbered(query, paging) { sn, row ->
            StockMovementRow(
                sn = sn,
                date = row[StockLedgerEntriesTable.entryDate],
                location = Ref.location(row[StockLedgerEntriesTable.locationId]),
                stockItem = Ref.stockItem(row[StockItemsTable.id].value),
                sourceType = row[StockLedgerEntriesTable.sourceType],
                sourceReference = row[StockLedgerEntriesTable.sourceReference],
                qtyIn = row[StockLedgerEntriesTable.qtyIn],
                qtyOut = row[StockLedgerEntriesTable.qtyOut],
            )
        }
    }

    fun reOrderReport(locationId: UUID?, paging: PageRequest): PageResult<ReOrderRow> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        val query = StockItemsTable
            .join(StockBalancesTable, org.jetbrains.exposed.sql.JoinType.INNER) { StockItemsTable.id eq StockBalancesTable.stockItemId }
            .selectAll()
            .andWhere { StockItemsTable.tenantId eq tenantId }
            .andWhere { StockBalancesTable.quantityOnHand lessEq StockItemsTable.reorderLevel }
        if (effectiveLocationId != null) query.andWhere { StockBalancesTable.locationId inList effectiveLocationId }
        query.orderBy(StockItemsTable.name to SortOrder.ASC, StockBalancesTable.locationId to SortOrder.ASC)
        Paging.fetch(query, paging) { row ->
            ReOrderRow(
                stockItem = Ref.stockItem(row[StockItemsTable.id].value),
                quantityOnHand = row[StockBalancesTable.quantityOnHand],
                reorderLevel = row[StockItemsTable.reorderLevel],
            )
        }
    }

}
