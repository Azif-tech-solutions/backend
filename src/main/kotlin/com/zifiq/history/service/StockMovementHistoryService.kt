package com.zifiq.history.service

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
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.history.dto.StockMovementHistoryRow
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.security.CostVisibility
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/**
 * Stock Movement History (docs/10 §4.3): the long-range, all-locations view over the single stock
 * ledger every module writes to. Index-backed and paged; the summary is computed with SQL
 * aggregates (never by scanning rows into memory). Cost columns are zeroed without
 * `factory.costing.read`.
 */
@Service
class StockMovementHistoryService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
    private val costVisibility: CostVisibility,
) {
    fun list(request: ListSearchRequest): PageResult<StockMovementHistoryRow> = tx.execute {
        val context = support.filterContext()
        val parsed = search.parse(request.q)
        val condition = listOfNotNull(
            StockLedgerEntriesTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { StockLedgerEntriesTable.locationId inList it },
            search.condition(parsed),
            filters.fromParams(request.params, context),
            filters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) search.rank(parsed) + filters.sort(emptyList(), context) else filters.sort(request.sortParams, context)
        val page = Paging.fetch(joined().selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) stats(condition) else null
        page.withItems(page.items.map(::toRow)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filters.metadata(support.filterContext()) }

    private fun joined() = StockLedgerEntriesTable.join(StockItemsTable, JoinType.INNER) { StockLedgerEntriesTable.stockItemId eq StockItemsTable.id }

    private fun stats(condition: org.jetbrains.exposed.sql.Op<Boolean>): Map<String, Any?> {
        val movements = StockLedgerEntriesTable.id.count()
        val qtyIn = StockLedgerEntriesTable.qtyIn.sum()
        val qtyOut = StockLedgerEntriesTable.qtyOut.sum()
        val row = joined().select(movements, qtyIn, qtyOut).where(condition).single()
        val adjCount = StockLedgerEntriesTable.id.count()
        val adjustments = joined().select(adjCount)
            .where(condition and (StockLedgerEntriesTable.sourceType eq "QUANTITY_ADJUSTMENT")).single()[adjCount]
        return mapOf(
            "movements" to row[movements],
            "quantityIn" to (row[qtyIn] ?: BigDecimal.ZERO),
            "quantityOut" to (row[qtyOut] ?: BigDecimal.ZERO),
            "adjustments" to adjustments,
        )
    }

    private fun toRow(r: ResultRow): StockMovementHistoryRow {
        val qtyIn = r[StockLedgerEntriesTable.qtyIn]
        val qtyOut = r[StockLedgerEntriesTable.qtyOut]
        val into = qtyIn.signum() > 0
        val unitCost = r[StockLedgerEntriesTable.unitCost]
        val value = unitCost?.let { (if (into) qtyIn else qtyOut) * it }
        val visible = support.costVisible()
        return StockMovementHistoryRow(
            date = r[StockLedgerEntriesTable.entryDate],
            ref = r[StockLedgerEntriesTable.sourceReference],
            product = Ref.stockItem(r[StockItemsTable.id].value),
            location = Ref.location(r[StockLedgerEntriesTable.locationId]),
            source = SOURCE_LABELS[r[StockLedgerEntriesTable.sourceType]] ?: r[StockLedgerEntriesTable.sourceType],
            sourceType = r[StockLedgerEntriesTable.sourceType],
            direction = if (into) "IN" else "OUT",
            quantity = qtyIn - qtyOut,
            recordedBy = UserRef(r[StockLedgerEntriesTable.createdByUserId].value),
            unitCost = costVisibility.maskIfUnauthorized(unitCost, visible, BigDecimal.ZERO),
            value = costVisibility.maskIfUnauthorized(value, visible, BigDecimal.ZERO),
            remark = r[StockLedgerEntriesTable.remark],
        )
    }

    private val directionExpr = RawExpression<String>({ +"(case when stock_ledger_entries.qty_in > 0 then 'IN' else 'OUT' end)" })

    private val filters = FilterSpec(
        fields = listOf(
            FilterField("stockItemId", "Product", FilterType.UUID, StockLedgerEntriesTable.stockItemId, aliases = setOf("stockItem", "product")),
            FilterField("categoryId", "Category", FilterType.UUID, StockItemsTable.categoryId, aliases = setOf("category")),
            FilterField("itemType", "Item type", FilterType.ENUM, StockItemsTable.itemType,
                listOf("FINISHED_PRODUCT", "RAW_MATERIAL", "IN_HOUSE_RECIPE", "OUTSOURCED").map { FilterOption(it, it.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) }),
            FilterField("locationId", "Location", FilterType.LOCATION, StockLedgerEntriesTable.locationId),
            FilterField("sourceType", "Source", FilterType.ENUM, StockLedgerEntriesTable.sourceType,
                SOURCE_LABELS.map { FilterOption(it.key, it.value) }, facetable = true),
            FilterField("direction", "Direction", FilterType.ENUM, directionExpr, listOf(FilterOption("IN", "In"), FilterOption("OUT", "Out")), facetable = true),
            FilterField("recordedBy", "Recorded by", FilterType.UUID, StockLedgerEntriesTable.createdByUserId),
            FilterField("entryDate", "Date", FilterType.DATE, StockLedgerEntriesTable.entryDate, sortable = true),
            FilterField("createdAt", "Recorded", FilterType.DATETIME, StockLedgerEntriesTable.createdAt, sortable = true),
        ),
        primaryDateKey = "entryDate",
        defaultSort = listOf("entryDate" to SortOrder.DESC, "createdAt" to SortOrder.DESC),
        tiebreaker = StockLedgerEntriesTable.id,
    )

    private val search = SearchSpec(
        fields = listOf(
            SearchField(StockLedgerEntriesTable.sourceReference, SearchKind.REFERENCE, "Reference"),
            SearchField(StockItemsTable.code, SearchKind.CODE, "Item code"),
            SearchField(StockItemsTable.name, SearchKind.TRIGRAM, "Product"),
        ),
        qualifiers = filters,
    )

    companion object {
        val SOURCE_LABELS = linkedMapOf(
            "REFILL" to "Stock Refill",
            "RECEIVE" to "Raw-material receipt",
            "PRODUCTION_RUN" to "Production run",
            "TRANSFER_OUT" to "Stock transfer out",
            "TRANSFER_IN" to "Stock transfer in",
            "TRANSFER_REJECTED_IN" to "Transfer rejected (returned)",
            "QUANTITY_ADJUSTMENT" to "Quantity adjustment",
            "TRADE_OUT" to "Factory trade out",
            "TRADE_IN" to "Factory trade in",
            "DIRECT_ENTRY" to "Direct entry",
            "OUTSOURCE_RECEIPT" to "Outsource receipt",
            "CUSTOMER_RETURN" to "Customer return",
            "AREA_MOVE_OUT" to "Area move out",
            "AREA_MOVE_IN" to "Area move in",
            "POS_SALE" to "POS sale",
            "CUSTOMER_WAYBILL" to "Customer waybill",
            "CUSTOMER_WAYBILL_CANCEL" to "Waybill cancelled",
            "SALE_VOID" to "Sale void",
            "PURGE_WRITE_OFF" to "Purge write-off",
        )
    }
}
