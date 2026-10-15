package com.zifiq.inventory.service

import com.zifiq.platform.directory.UserRef
import com.zifiq.inventory.repository.StockSql
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
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
import com.zifiq.common.search.text
import com.zifiq.inventory.dto.PriceChange
import com.zifiq.inventory.dto.PriceEntryUpdateRequest
import com.zifiq.inventory.dto.PriceHistoryRow
import com.zifiq.inventory.dto.PriceListRow
import com.zifiq.inventory.dto.PricePublishRequest
import com.zifiq.inventory.dto.PricePublishResult
import com.zifiq.inventory.model.StockItemPricesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.Alias
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** The price list (docs/05 §4.12): effective-dated retail and dealer prices, with margins for cost-authorised users. */
@Service
class PriceListService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
    private val sequences: DocumentSequenceService,
    private val domainEvents: DomainEvents,
    private val notify: Notify,
) {
    private val current: Alias<StockItemPricesTable> = StockItemPricesTable.alias("current_price")
    private val next: Alias<StockItemPricesTable> = StockItemPricesTable.alias("next_price")

    private fun today(): LocalDate = LocalDate.now(moduleSettings.zone())

    /** stock_items ⟕ the price in force on [asAt] ⟕ the next scheduled change after it. */
    private fun joined(asAt: LocalDate) = StockItemsTable
        .join(current, JoinType.LEFT, additionalConstraint = {
            RawOp {
                +current[StockItemPricesTable.id]; +" = (select p.id from stock_item_prices p where p.stock_item_id = "; +StockItemsTable.id
                +" and p.effective_from <= "; date(asAt); +" order by p.effective_from desc limit 1)"
            }
        })
        .join(next, JoinType.LEFT, additionalConstraint = {
            RawOp {
                +next[StockItemPricesTable.id]; +" = (select p.id from stock_item_prices p where p.stock_item_id = "; +StockItemsTable.id
                +" and p.effective_from > "; date(asAt); +" order by p.effective_from asc limit 1)"
            }
        })

    private fun QueryBuilder.date(value: LocalDate) = registerArgument(JavaLocalDateColumnType(), value)

    private val categoryName = RawExpression<String>({ +"coalesce((select c.name from categories c where c.id = "; +StockItemsTable.categoryId; +"), 'Ungrouped')" })
    private val unitName = StockSql.unitName(StockItemsTable.unitOfMeasureId)

    private fun filterSpec(): FilterSpec = FilterSpec(
        fields = listOf(
            FilterField("itemType", "Item type", FilterType.ENUM, StockItemsTable.itemType,
                listOf("FINISHED_PRODUCT", "RAW_MATERIAL", "IN_HOUSE_RECIPE", "OUTSOURCED", "DIRECT_ENTRY").map { FilterOption(it, it.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)) },
                aliases = setOf("type"), facetable = true),
            FilterField("categoryId", "Group", FilterType.UUID, StockItemsTable.categoryId, aliases = setOf("category"), lookup = "/api/v1/factory/setup/categories"),
            FilterField("status", "Product status", FilterType.ENUM, StockItemsTable.status, listOf(FilterOption("ACTIVE", "Active"), FilterOption("SUSPENDED", "Suspended"))),
            FilterField("product", "Product", FilterType.TEXT, StockItemsTable.name, sortable = true),
            FilterField("code", "Code", FilterType.REF, StockItemsTable.code, sortable = true),
            FilterField("retailPrice", "Retail price", FilterType.MONEY, current[StockItemPricesTable.retailPrice], sortable = true),
            FilterField("dealerPrice", "Dealer price", FilterType.MONEY, current[StockItemPricesTable.dealerPrice], sortable = true),
            FilterField("effectiveFrom", "Effective from", FilterType.DATE, current[StockItemPricesTable.effectiveFrom], sortable = true),
            FilterField("hasPrice", "Has a price", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +current[StockItemPricesTable.id]; +" IS NOT NULL)" })),
            FilterField("hasNextChange", "Has a scheduled change", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +next[StockItemPricesTable.id]; +" IS NOT NULL)" })),
            FilterField("batchRef", "Publication", FilterType.REF, current[StockItemPricesTable.batchRef]),
            FilterField("belowCost", "Priced below cost", FilterType.BOOLEAN,
                RawExpression<Boolean>({ +"coalesce("; +current[StockItemPricesTable.retailPrice]; +" < "; +StockItemsTable.landingCost; +", false)" }), cost = true),
            FilterField("marginPct", "Margin %", FilterType.NUMBER,
                RawExpression<BigDecimal>({ +"(("; +current[StockItemPricesTable.retailPrice]; +" - "; +StockItemsTable.landingCost; +") * 100 / nullif("; +current[StockItemPricesTable.retailPrice]; +", 0))" }),
                sortable = true, cost = true),
        ),
        defaultSort = listOf("product" to SortOrder.ASC),
        tiebreaker = StockItemsTable.id,
    )

    private val searchFields = listOf(
        SearchField(StockItemsTable.code, SearchKind.CODE, "Code"),
        SearchField(StockItemsTable.barcode, SearchKind.CODE, "Barcode"),
        SearchField(StockItemsTable.name, SearchKind.TRIGRAM, "Product"),
    )

    private fun costVisible() = permissionAuthorizer.hasPermission(currentActorProvider.current().userId, "factory.costing.read")

    fun filters(): FilterMetadata = tx.execute { filterSpec().metadata(FilterContext(costVisible(), moduleSettings.zone())) }

    fun list(request: ListSearchRequest): PageResult<PriceListRow> = tx.execute {
        val costVisible = costVisible()
        val context = FilterContext(costVisible, moduleSettings.zone())
        val asAt = request.params["asAt"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ApiErrors.invalidFilter("asAt", "Expected a date (yyyy-MM-dd)") } } ?: today()
        val locationId = request.params["locationId"]?.firstOrNull()?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ApiErrors.invalidFilter("locationId", "Not a valid id") } }
        val params = request.params - setOf("asAt", "locationId")
        val filters = filterSpec()
        val search = SearchSpec(searchFields, filters)
        val parsed = search.parse(request.q, costVisible)

        val parts = mutableListOf<Op<Boolean>>(StockItemsTable.tenantId eq tenantContext.requireTenantId())
        // Direct-entry items belong to one showroom: only listed for callers scoped to it (or unrestricted callers).
        locationAccess.resolveFilterSet(locationId)?.let { scope ->
            parts += RawOp { +"("; +StockItemsTable.itemType; +" <> 'DIRECT_ENTRY' OR "; +StockItemsTable.locationId; +" IN ("
                scope.forEachIndexed { i, id -> if (i > 0) +", "; registerArgument(org.jetbrains.exposed.sql.UUIDColumnType(), id) }; +"))" }
        }
        search.condition(parsed)?.let { parts += it }
        filters.fromParams(params, context)?.let { parts += it }
        filters.fromJson(request.filter, context)?.let { parts += it }
        val condition = parts.allOf()!!

        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) search.rank(parsed) + filters.sort(emptyList(), context)
        else filters.sort(request.sortParams, context)
        val base = joined(asAt)
        val query = base.select(StockItemsTable.columns + current.columns + next.columns + categoryName + unitName)
            .where(condition).orderBy(*sort.toTypedArray())
        val page = Paging.fetch(query, PageRequest.of(request.page, request.size)) { toRow(it, costVisible) }

        val summary = if (request.summary) {
            val total = RawExpression<Long>({ +"count(*)" })
            val priced = RawExpression<Long>({ +"count("; +current[StockItemPricesTable.id]; +")" })
            val below = RawExpression<Long>({ +"count(*) filter (where "; +current[StockItemPricesTable.retailPrice]; +" < "; +StockItemsTable.landingCost; +")" })
            val row = base.select(total, priced, below).where(condition).single()
            val items = (row[total] as Number).toLong()
            val pricedCount = (row[priced] as Number).toLong()
            mapOf("items" to items, "priced" to pricedCount, "unpriced" to items - pricedCount,
                "belowCost" to if (costVisible) (row[below] as Number).toLong() else 0L, "costVisible" to costVisible, "asAt" to asAt.toString())
        } else null
        page.copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun publish(request: PricePublishRequest): PricePublishResult = tx.execute {
        val effectiveFrom = request.effectiveFrom!!
        if (effectiveFrom.isBefore(today())) throw ApiErrors.validationFailed(mapOf("effectiveFrom" to "must be today or later"))
        val ids = request.entries.map { it.stockItemId!! }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) throw ApiErrors.validationFailed(mapOf("entries" to "Each product may appear once per publication"))

        val items = StockItemsTable.selectAll()
            .where { (StockItemsTable.tenantId eq tenantContext.requireTenantId()) and (StockItemsTable.id inList ids) }
            .associateBy { it[StockItemsTable.id].value }
        ids.firstOrNull { it !in items }?.let { throw ApiErrors.stockItemNotFound() }
        request.entries.forEach { entry ->
            val item = items.getValue(entry.stockItemId!!)
            val label = "${item[StockItemsTable.code]} ${item[StockItemsTable.name]}"
            if (entry.dealerPrice!! > entry.retailPrice!!) throw ApiErrors.dealerPriceAboveRetail(label)
        }
        val existing = StockItemPricesTable.select(StockItemPricesTable.stockItemId)
            .where { (StockItemPricesTable.stockItemId inList ids) and (StockItemPricesTable.effectiveFrom eq effectiveFrom) }
            .map { it[StockItemPricesTable.stockItemId].value }
        existing.firstOrNull()?.let { id ->
            val item = items.getValue(id)
            throw ApiErrors.priceAlreadySetForDate("${item[StockItemsTable.code]} ${item[StockItemsTable.name]}", effectiveFrom)
        }

        val batchRef = sequences.next("PL")
        val userId = currentActorProvider.current().userId
        val now = Instant.now()
        StockItemPricesTable.batchInsert(request.entries, shouldReturnGeneratedValues = false) { entry ->
            this[StockItemPricesTable.tenantId] = tenantContext.requireTenantId()
            this[StockItemPricesTable.stockItemId] = entry.stockItemId!!
            this[StockItemPricesTable.retailPrice] = entry.retailPrice!!.setScale(2, RoundingMode.HALF_UP)
            this[StockItemPricesTable.dealerPrice] = entry.dealerPrice!!.setScale(2, RoundingMode.HALF_UP)
            this[StockItemPricesTable.effectiveFrom] = effectiveFrom
            this[StockItemPricesTable.batchRef] = batchRef
            this[StockItemPricesTable.note] = request.note?.trim()?.ifEmpty { null }
            this[StockItemPricesTable.setByUserId] = userId
            this[StockItemPricesTable.createdAt] = now
        }

        val costVisible = costVisible()
        val belowCost = request.entries.count { entry ->
            val cost = items.getValue(entry.stockItemId!!)[StockItemsTable.landingCost]
            cost != null && entry.retailPrice!! < cost
        }
        val count = request.entries.size
        val id = UUID.nameUUIDFromBytes("${tenantContext.requireTenantId()}:$batchRef".toByteArray())
        domainEvents.record(
            type = "inventory.price-list.published",
            aggregateType = "price-list",
            aggregateId = id,
            aggregateRef = batchRef,
            summary = "$batchRef: prices for $count item(s) effective $effectiveFrom",
            payload = mapOf("count" to count, "effectiveFrom" to effectiveFrom.toString()),
        )
        notify.holdersOf(
            permission = "inventory.pricing.read",
            locationId = null,
            category = NotificationCategory.INFO,
            title = "New prices — $batchRef",
            body = "New prices effective $effectiveFrom for $count item(s).",
            linkType = "inventory.price-list",
            linkRef = batchRef,
            exclude = userId,
        )
        PricePublishResult(batchRef, effectiveFrom, count, if (costVisible) belowCost else 0, costVisible)
    }

    /** Only a future price can change; one already in force is history (publish a new one instead). */
    fun updateEntry(priceId: UUID, request: PriceEntryUpdateRequest): PriceHistoryRow = tx.execute {
        val row = StockItemPricesTable.selectAll()
            .where { (StockItemPricesTable.id eq priceId) and (StockItemPricesTable.tenantId eq tenantContext.requireTenantId()) }
            .firstOrNull() ?: throw ApiErrors.validationFailed(mapOf("priceId" to "Price entry not found"))
        if (!row[StockItemPricesTable.effectiveFrom].isAfter(today())) throw ApiErrors.priceAlreadyEffective()
        if (request.dealerPrice!! > request.retailPrice!!) throw ApiErrors.dealerPriceAboveRetail("This item")
        StockItemPricesTable.update({ StockItemPricesTable.id eq priceId }) {
            it[retailPrice] = request.retailPrice.setScale(2, RoundingMode.HALF_UP)
            it[dealerPrice] = request.dealerPrice.setScale(2, RoundingMode.HALF_UP)
            request.note?.let { note -> it[StockItemPricesTable.note] = note.trim().ifEmpty { null } }
        }
        domainEvents.record(
            type = "inventory.price-list.entry-changed",
            aggregateType = "price-list",
            aggregateId = priceId,
            aggregateRef = row[StockItemPricesTable.batchRef],
            summary = "Scheduled price for ${row[StockItemPricesTable.effectiveFrom]} changed (${row[StockItemPricesTable.batchRef]})",
        )
        val updated = StockItemPricesTable.selectAll().where { StockItemPricesTable.id eq priceId }.single()
        toHistoryRow(updated, today(), currentPriceId(updated[StockItemPricesTable.stockItemId].value, today()))
    }

    fun itemHistory(stockItemId: UUID, paging: PageRequest = PageRequest.of(null, null)): PageResult<PriceHistoryRow> = tx.execute {
        StockItemsTable.selectAll()
            .where { (StockItemsTable.id eq stockItemId) and (StockItemsTable.tenantId eq tenantContext.requireTenantId()) }
            .firstOrNull() ?: throw ApiErrors.stockItemNotFound()
        val today = today()
        val currentId = currentPriceId(stockItemId, today)
        val query = StockItemPricesTable.selectAll().where { StockItemPricesTable.stockItemId eq stockItemId }
            .orderBy(StockItemPricesTable.effectiveFrom to SortOrder.DESC, StockItemPricesTable.id to SortOrder.DESC)
        Paging.fetch(query, paging) { toHistoryRow(it, today, currentId) }
    }

    /** The price in force today: the latest one effective on or before today (one indexed row). */
    private fun currentPriceId(stockItemId: UUID, today: java.time.LocalDate): UUID? =
        StockItemPricesTable.select(StockItemPricesTable.id)
            .where { (StockItemPricesTable.stockItemId eq stockItemId) and (StockItemPricesTable.effectiveFrom lessEq today) }
            .orderBy(StockItemPricesTable.effectiveFrom to SortOrder.DESC).limit(1).firstOrNull()?.get(StockItemPricesTable.id)?.value

    private fun toHistoryRow(row: ResultRow, today: java.time.LocalDate, currentId: UUID?): PriceHistoryRow {
        val id = row[StockItemPricesTable.id].value
        return PriceHistoryRow(
            priceId = id,
            retailPrice = row[StockItemPricesTable.retailPrice],
            dealerPrice = row[StockItemPricesTable.dealerPrice],
            effectiveFrom = row[StockItemPricesTable.effectiveFrom],
            batchRef = row[StockItemPricesTable.batchRef],
            note = row[StockItemPricesTable.note],
            setBy = UserRef(row[StockItemPricesTable.setByUserId].value),
            createdAt = row[StockItemPricesTable.createdAt],
            state = when {
                row[StockItemPricesTable.effectiveFrom].isAfter(today) -> "SCHEDULED"
                id == currentId -> "CURRENT"
                else -> "SUPERSEDED"
            },
        )
    }

    private fun toRow(row: ResultRow, costVisible: Boolean): PriceListRow {
        val retail = row.getOrNull(current[StockItemPricesTable.retailPrice])
        val dealer = row.getOrNull(current[StockItemPricesTable.dealerPrice])
        val cost = row[StockItemsTable.landingCost] ?: BigDecimal.ZERO
        val margin = if (retail != null) retail - cost else BigDecimal.ZERO
        val marginPct = if (retail != null && retail.signum() > 0) margin.multiply(BigDecimal(100)).divide(retail, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val dealerMargin = if (dealer != null) dealer - cost else BigDecimal.ZERO
        fun mask(value: BigDecimal) = if (costVisible) value.setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)
        val nextId = row.getOrNull(next[StockItemPricesTable.id])
        return PriceListRow(
            stockItemId = row[StockItemsTable.id].value,
            code = row[StockItemsTable.code],
            product = row[StockItemsTable.name],
            itemType = row[StockItemsTable.itemType],
            categoryId = row[StockItemsTable.categoryId],
            group = row[categoryName],
            unit = row.getOrNull(unitName),
            status = row[StockItemsTable.status],
            priceId = row.getOrNull(current[StockItemPricesTable.id])?.value,
            retailPrice = retail,
            dealerPrice = dealer,
            effectiveFrom = row.getOrNull(current[StockItemPricesTable.effectiveFrom]),
            batchRef = row.getOrNull(current[StockItemPricesTable.batchRef]),
            nextChange = nextId?.let {
                PriceChange(it.value, row[next[StockItemPricesTable.effectiveFrom]], row[next[StockItemPricesTable.retailPrice]], row[next[StockItemPricesTable.dealerPrice]])
            },
            landingCost = mask(cost),
            margin = mask(margin),
            marginPct = mask(marginPct),
            dealerMargin = mask(dealerMargin),
            belowCost = costVisible && retail != null && retail < cost,
        )
    }
}
