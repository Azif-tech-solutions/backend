package com.zifiq.showroom.service

import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.QueryAlias
import org.jetbrains.exposed.sql.ColumnSet
import com.zifiq.common.sql.Aggregates
import com.zifiq.inventory.repository.StockSql
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import com.zifiq.inventory.model.StockTransferLinesTable
import com.zifiq.inventory.model.StockTransfersTable
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.showroom.dto.ComprehensiveStockRow
import com.zifiq.showroom.dto.TransferReportRow
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Sum
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/** Showroom reports: Comprehensive Stock (docs/05 §4.11) and the General Transfer Report (§4.14). */
@Service
class ShowroomReportService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
    private val hierarchy: LocationHierarchy,
    private val register: ShowroomRegisterService,
    private val domainEvents: DomainEvents,
) {
    private fun today(): LocalDate = LocalDate.now(moduleSettings.zone())

    // ---- Comprehensive Stock Report ----

    /**
     * One row per item × area at the showroom(s) on [asAt] (today: live balances; earlier: the
     * ledger up to that day). Filtering, `q`, sorting, paging and the totals are one SQL query each,
     * however many items and areas there are. Cost columns are 0 for callers without cost
     * visibility; the response's summary says which (`costVisible`), so the UI can hide them.
     */
    fun comprehensiveStock(request: ListSearchRequest): PageResult<ComprehensiveStockRow> = tx.execute {
        fun param(name: String) = request.params[name]?.firstOrNull()?.takeIf { it.isNotBlank() }
        fun uuidParam(name: String) = param(name)?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ApiErrors.invalidFilter(name, "Not a valid id") } }
        val known = setOf("locationId", "areaId", "source", "categoryId", "asAt", "includeZero")
        request.params.keys.firstOrNull { it !in known }?.let { throw ApiErrors.invalidFilter(it, "Unknown filter", known.toList()) }
        val locationId = uuidParam("locationId")
        val areaId = uuidParam("areaId")
        val categoryIds = request.params["categoryId"].orEmpty().flatMap { it.split(',') }.filter { it.isNotBlank() }
            .map { runCatching { UUID.fromString(it.trim()) }.getOrElse { throw ApiErrors.invalidFilter("categoryId", "Not a valid id") } }.toSet()
        val sources = request.params["source"].orEmpty().flatMap { it.split(',') }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        sources.firstOrNull { it !in SOURCE_LABELS }?.let { throw ApiErrors.invalidFilter("source", "Unknown source '$it'", SOURCE_LABELS.keys.toList()) }
        val asAt = param("asAt")?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ApiErrors.invalidFilter("asAt", "Expected a date (yyyy-MM-dd)") } } ?: today()
        val includeZero = param("includeZero") == "true"

        // Which locations: one showroom (+ areas) or one area; otherwise every showroom in scope.
        val locations: Set<UUID> = when {
            areaId != null -> {
                val area = LocationsTable.selectAll().where { (LocationsTable.id eq areaId) and (LocationsTable.type eq "STORAGE_AREA") }.firstOrNull()
                    ?: throw ApiErrors.invalidFilter("areaId", "Storage area not found")
                register.showroomRow(area[LocationsTable.parentLocationId]!!)
                if (locationId != null && locationId != area[LocationsTable.parentLocationId]) throw ApiErrors.invalidFilter("areaId", "Not an area of that showroom")
                setOf(areaId)
            }
            locationId != null -> { register.showroomRow(locationId); register.withAreas(locationId) }
            else -> {
                val allowed = locationAccess.allowedLocationIds()
                val showrooms = LocationsTable.select(LocationsTable.id)
                    .where { (LocationsTable.tenantId eq tenantContext.requireTenantId()) and (LocationsTable.type eq "SHOWROOM") }
                    .map { it[LocationsTable.id].value }.filter { allowed == null || it in allowed }
                hierarchy.withAreas(showrooms)
            }
        }
        val visible = permissionAuthorizer.hasPermission(currentActorProvider.current().userId, "factory.costing.read")
        val source = stockSource(locations.ifEmpty { setOf(UUID(0, 0)) }, asAt)
        val qty = source.qty
        val categoryName = RawExpression<String>({ +"coalesce((select c.name from categories c where c.id = "; +StockItemsTable.categoryId; +"), 'Ungrouped')" })
        val unitName = StockSql.unitName(StockItemsTable.unitOfMeasureId)
        val area = RawExpression<String>({ +"(case when "; +LocationsTable.type; +" = 'STORAGE_AREA' then "; +LocationsTable.name; +" else 'Unallocated' end)" })
        val price = StockSql.retailPriceAt(asAt)
        val totalCost = RawExpression<BigDecimal>({ +"round(coalesce("; +StockItemsTable.landingCost; +", 0) * "; +qty; +", 2)" })
        val retailValue = RawExpression<BigDecimal>({ +"round(coalesce("; +price; +", 0) * "; +qty; +", 2)" })
        val parsed = request.q?.takeIf { it.isNotBlank() }?.let(STOCK_SEARCH::parse)
        val condition = listOfNotNull(
            StockItemsTable.tenantId eq tenantContext.requireTenantId(),
            if (includeZero) null else RawOp { +qty; +" <> 0" },
            sources.takeIf { it.isNotEmpty() }?.let { StockItemsTable.itemType inList it },
            categoryIds.takeIf { it.isNotEmpty() }?.let { StockItemsTable.categoryId inList it },
            parsed?.let(STOCK_SEARCH::condition),
        ).allOf()!!

        val sortKey = request.sort.firstOrNull()
        val dir = if (sortKey?.dir.equals("desc", ignoreCase = true)) SortOrder.DESC else SortOrder.ASC
        val order: List<Pair<org.jetbrains.exposed.sql.Expression<*>, SortOrder>> = when (sortKey?.field) {
            null, "group" -> listOf(categoryName to dir, StockItemsTable.name to dir, area to dir)
            "qty" -> listOf(qty to dir)
            "retailValue" -> listOf(retailValue to dir)
            "totalCost" -> if (visible) listOf(totalCost to dir) else throw ApiErrors.invalidSortField("totalCost", listOf("group", "qty", "retailValue", "detail"))
            "detail" -> listOf(StockItemsTable.name to dir)
            else -> throw ApiErrors.invalidSortField(sortKey.field, listOf("group", "qty", "retailValue", "detail") + if (visible) listOf("totalCost") else emptyList())
        }
        val query = source.rows
            .select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, StockItemsTable.itemType, StockItemsTable.landingCost,
                categoryName, unitName, area, source.location, qty, totalCost, price, retailValue)
            .where(condition)
            .orderBy(*(order + listOf(StockItemsTable.id to SortOrder.ASC, source.location to SortOrder.ASC)).toTypedArray())
        val page = Paging.fetchNumbered(query, PageRequest.of(request.page, request.size)) { sn, row ->
            val type = row[StockItemsTable.itemType]
            ComprehensiveStockRow(
                ln = sn, stockItemId = row[StockItemsTable.id].value, code = row[StockItemsTable.code], detail = row[StockItemsTable.name], group = row[categoryName],
                source = SOURCE_LABELS[type] ?: type.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase),
                locationId = row[source.location], area = row[area], qty = row[qty] ?: BigDecimal.ZERO, unit = row.getOrNull(unitName),
                unitCost = if (visible) (row[StockItemsTable.landingCost] ?: BigDecimal.ZERO).setScale(2) else BigDecimal.ZERO.setScale(2),
                totalCost = if (visible) row[totalCost] else BigDecimal.ZERO.setScale(2),
                sellingPrice = row[price], retailValue = row[retailValue],
            )
        }

        if (visible) {
            domainEvents.record(
                type = "security.cost-data.viewed", aggregateType = "report", aggregateId = UUID.nameUUIDFromBytes("showroom.comprehensive-stock".toByteArray()),
                aggregateRef = "showroom.comprehensive-stock", summary = "Comprehensive stock report with costs viewed", locationId = locationId,
            )
        }
        val lines = Aggregates.count()
        val totalQuantity = Aggregates.sum(qty)
        val stockValue = Aggregates.sum(totalCost)
        val retailTotal = Aggregates.sum(retailValue)
        val totals = source.rows.select(lines, totalQuantity, stockValue, retailTotal).where(condition).single()
        val storageAreas = if (locations.isEmpty()) 0L
        else LocationsTable.select(LocationsTable.id).where { (LocationsTable.id inList locations) and (LocationsTable.type eq "STORAGE_AREA") }.count()
        page.copy(
            summary = mapOf(
                "costVisible" to visible, "asAt" to asAt.toString(), "lineItems" to Aggregates.long(totals, lines),
                "totalQuantity" to totals[totalQuantity],
                "totalStockValue" to if (visible) Aggregates.money(totals, stockValue) else BigDecimal.ZERO.setScale(2),
                "retailValue" to Aggregates.money(totals, retailTotal),
                "storageAreas" to storageAreas,
            ),
        )
    }

    /** Stock rows for the report: the live balances (as at today), or the ledger summed up to [asAt]; each joined to its item and location. */
    private class StockSource(val rows: ColumnSet, val qty: org.jetbrains.exposed.sql.Expression<BigDecimal?>, val location: org.jetbrains.exposed.sql.Expression<UUID>)

    private fun stockSource(locations: Set<UUID>, asAt: LocalDate): StockSource {
        if (!asAt.isBefore(today())) {
            val balances = StockBalancesTable.select(StockBalancesTable.locationId, StockBalancesTable.stockItemId, StockBalancesTable.quantityOnHand)
                .where { StockBalancesTable.locationId inList locations }.alias("stock")
            return joined(balances, balances[StockBalancesTable.locationId], balances[StockBalancesTable.stockItemId], balances[StockBalancesTable.quantityOnHand])
        }
        val net = StockSql.net().alias("qty")
        val ledger = StockLedgerEntriesTable.select(StockLedgerEntriesTable.locationId, StockLedgerEntriesTable.stockItemId, net)
            .where {
                (StockLedgerEntriesTable.tenantId eq tenantContext.requireTenantId()) and (StockLedgerEntriesTable.locationId inList locations) and
                    (StockLedgerEntriesTable.entryDate lessEq asAt)
            }
            .groupBy(StockLedgerEntriesTable.locationId, StockLedgerEntriesTable.stockItemId).alias("stock")
        return joined(ledger, ledger[StockLedgerEntriesTable.locationId], ledger[StockLedgerEntriesTable.stockItemId], ledger[net])
    }

    @Suppress("UNCHECKED_CAST")
    private fun joined(stock: QueryAlias, location: org.jetbrains.exposed.sql.Expression<UUID>, item: org.jetbrains.exposed.sql.Expression<*>, qty: org.jetbrains.exposed.sql.Expression<*>) =
        StockSource(
            stock.join(StockItemsTable, JoinType.INNER, item, StockItemsTable.id).join(LocationsTable, JoinType.INNER, location, LocationsTable.id),
            qty as org.jetbrains.exposed.sql.Expression<BigDecimal?>, location,
        )


    private val STOCK_SEARCH = SearchSpec(
        listOf(
            SearchField(StockItemsTable.code, SearchKind.REFERENCE, "Code"),
            SearchField(StockItemsTable.barcode, SearchKind.CODE, "Barcode"),
            SearchField(StockItemsTable.name, SearchKind.TRIGRAM, "Name"),
        ),
    )

    // ---- General Transfer Report ----

    private fun rootType(column: org.jetbrains.exposed.sql.Column<UUID>) = RawExpression<String>({
        +"(select coalesce(p.type, l.type) from locations l left join locations p on p.id = l.parent_location_id where l.id = "; +column; +")"
    })
    private fun locationLabel(column: org.jetbrains.exposed.sql.Column<UUID>) = RawExpression<String>({
        +"(select case when p.id is null then l.name else p.name || ' — ' || l.name end from locations l left join locations p on p.id = l.parent_location_id where l.id = "; +column; +")"
    })
    private val sourceType = rootType(StockTransfersTable.sourceLocationId)
    private val destinationType = rootType(StockTransfersTable.destinationLocationId)
    private val sourceName = locationLabel(StockTransfersTable.sourceLocationId)
    private val destinationName = locationLabel(StockTransfersTable.destinationLocationId)
    private val route = RawExpression<String>({ +"("; +sourceType; +" || '_TO_' || "; +destinationType; +")" })
    private val reportStatus = RawExpression<String>({ +"(CASE WHEN "; +StockTransfersTable.status; +" = 'RECEIVED' THEN 'RECEIVED' ELSE 'IN_TRANSIT' END)" })
    private val unitName = StockSql.unitName(StockItemsTable.unitOfMeasureId)

    private val transferFilters = FilterSpec(
        fields = listOf(
            FilterField("route", "Route", FilterType.ENUM, route,
                listOf("SHOWROOM_TO_SHOWROOM", "FACTORY_TO_SHOWROOM", "SHOWROOM_TO_FACTORY", "FACTORY_TO_FACTORY")
                    .map { FilterOption(it, it.lowercase().replace("_to_", " → ").replace('_', ' ').replaceFirstChar(Char::titlecase)) }, facetable = true),
            FilterField("sourceLocationId", "From", FilterType.LOCATION, StockTransfersTable.sourceLocationId, aliases = setOf("from")),
            FilterField("destinationLocationId", "To", FilterType.LOCATION, StockTransfersTable.destinationLocationId, aliases = setOf("to")),
            FilterField("driverId", "Driver", FilterType.UUID, StockTransfersTable.driverId, aliases = setOf("driver"), lookup = "/api/v1/inventory/drivers/lookup"),
            FilterField("status", "Status", FilterType.ENUM, reportStatus, listOf(FilterOption("IN_TRANSIT", "In transit"), FilterOption("RECEIVED", "Received")), facetable = true),
            FilterField("hasRejections", "Has rejections", FilterType.BOOLEAN, RawExpression<Boolean>({ +"(coalesce("; +StockTransferLinesTable.qtyRejected; +", 0) > 0)" })),
            FilterField("qtyRejected", "Rejected quantity", FilterType.NUMBER, StockTransferLinesTable.qtyRejected),
            FilterField("stockItemId", "Product", FilterType.UUID, StockTransferLinesTable.stockItemId, aliases = setOf("item")),
            FilterField("transferDate", "Transfer date", FilterType.DATE, StockTransfersTable.transferDate, sortable = true),
            FilterField("waybillRef", "Waybill", FilterType.REF, StockTransfersTable.waybillRef, sortable = true),
        ),
        primaryDateKey = "transferDate",
        defaultSort = listOf("transferDate" to SortOrder.DESC, "waybillRef" to SortOrder.DESC),
        tiebreaker = StockTransferLinesTable.id,
    )

    private val transferSearch = SearchSpec(
        fields = listOf(
            SearchField(StockTransfersTable.waybillRef, SearchKind.REFERENCE, "Waybill"),
            SearchField(StockItemsTable.name, SearchKind.TRIGRAM, "Product"),
            SearchField(StockTransfersTable.driverName, SearchKind.TRIGRAM, "Driver"),
            SearchField(StockTransfersTable.driverVehicle, SearchKind.CODE, "Vehicle"),
        ),
        qualifiers = transferFilters,
    )

    private fun transferContext() = FilterContext(costVisible = true, zone = moduleSettings.zone(), expandLocations = hierarchy::withAreas)

    fun transferFilterMetadata(): FilterMetadata = tx.execute { transferFilters.metadata(transferContext()) }

    /** One row per transfer line; a single-location user sees transfers where either end is their location. */
    fun transfers(request: ListSearchRequest): PageResult<TransferReportRow> = tx.execute {
        val context = transferContext()
        val parsed = transferSearch.parse(request.q)
        val parts = mutableListOf<Op<Boolean>>(StockTransfersTable.tenantId eq tenantContext.requireTenantId())
        locationAccess.allowedLocationIds()?.let { parts += (StockTransfersTable.sourceLocationId inList it) or (StockTransfersTable.destinationLocationId inList it) }
        transferSearch.condition(parsed)?.let { parts += it }
        transferFilters.fromParams(request.params, context)?.let { parts += it }
        transferFilters.fromJson(request.filter, context)?.let { parts += it }
        val condition = parts.allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) transferSearch.rank(parsed) + transferFilters.sort(emptyList(), context)
        else transferFilters.sort(request.sortParams, context)
        val base = StockTransferLinesTable
            .join(StockTransfersTable, JoinType.INNER, StockTransferLinesTable.stockTransferId, StockTransfersTable.id)
            .join(StockItemsTable, JoinType.INNER, StockTransferLinesTable.stockItemId, StockItemsTable.id)
        val query = base.select(StockTransferLinesTable.columns + StockTransfersTable.columns + StockItemsTable.code + StockItemsTable.name +
            sourceType + destinationType + sourceName + destinationName + route + unitName)
            .where(condition).orderBy(*sort.toTypedArray())
        val page = Paging.fetch(query, PageRequest.of(request.page, request.size)) {
            val status = it[StockTransfersTable.status]
            TransferReportRow(
                transferId = it[StockTransfersTable.id].value, lineId = it[StockTransferLinesTable.id].value, date = it[StockTransfersTable.transferDate],
                waybillRef = it[StockTransfersTable.waybillRef], source = it[sourceName], sourceType = it[sourceType],
                destination = it[destinationName], destinationType = it[destinationType], route = it[route],
                driver = it[StockTransfersTable.driverName], vehicle = it[StockTransfersTable.driverVehicle],
                productCode = it[StockItemsTable.code], product = it[StockItemsTable.name], qtySent = it[StockTransferLinesTable.qtySent],
                qtyReceived = it[StockTransferLinesTable.qtyReceived], qtyRejected = it[StockTransferLinesTable.qtyRejected], unit = it.getOrNull(unitName),
                receivedDate = it[StockTransfersTable.receivedDate],
                status = when (status) { "RECEIVED" -> "Received"; "PARTIALLY_RECEIVED" -> "Partially received"; else -> "In transit" },
            )
        }
        val summary = if (request.summary) {
            val transfers = RawExpression<Long>({ +"count(distinct "; +StockTransfersTable.id; +")" })
            val quantity = RawExpression<BigDecimal>({ +"coalesce(sum("; +StockTransferLinesTable.qtySent; +"), 0)" })
            val inTransit = RawExpression<Long>({ +"count(distinct "; +StockTransfersTable.id; +") filter (where "; +StockTransfersTable.status; +" <> 'RECEIVED')" })
            val row = base.select(transfers, quantity, inTransit).where(condition).single()
            val locationsInvolved = base.select(StockTransfersTable.sourceLocationId, StockTransfersTable.destinationLocationId).where(condition)
                .flatMap { listOf(it[StockTransfersTable.sourceLocationId], it[StockTransfersTable.destinationLocationId]) }.toSet().size
            mapOf(
                "transfers" to (row[transfers] as Number).toLong(), "totalQuantity" to BigDecimal(row[quantity].toString()),
                "inTransit" to (row[inTransit] as Number).toLong(), "locationsInvolved" to locationsInvolved,
            )
        } else null
        page.copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    companion object {
        val SOURCE_LABELS = mapOf("FINISHED_PRODUCT" to "Finished Product", "OUTSOURCED" to "Outsource", "DIRECT_ENTRY" to "Direct entry")
    }
}
