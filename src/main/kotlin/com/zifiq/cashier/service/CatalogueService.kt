package com.zifiq.cashier.service

import com.zifiq.cashier.dto.CatalogueItem
import com.zifiq.cashier.model.TillSessionsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerType
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.StockSql
import com.zifiq.inventory.service.StockAvailability
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/**
 * The till's catalogue (docs/09 §4.2, §9 "Till catalogue search"): active items with a price in
 * force at the showroom, by code/barcode (exact first, then prefix) or name (typo-tolerant), with
 * what the showroom has to sell. In-stock only by default — driven from the showroom's balances, so
 * the page is index-backed whatever the size of the item master.
 */
@Service
class CatalogueService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val hierarchy: LocationHierarchy,
    private val availability: StockAvailability,
    private val customers: CustomerDirectory,
) {
    private val search = SearchSpec(
        listOf(
            SearchField(StockItemsTable.code, SearchKind.CODE, "code"),
            SearchField(StockItemsTable.barcode, SearchKind.CODE, "barcode"),
            SearchField(StockItemsTable.name, SearchKind.TRIGRAM, "name"),
        ),
    )

    fun catalogue(locationId: UUID?, q: String?, categoryId: UUID?, customerId: UUID?, includeOutOfStock: Boolean, paging: PageRequest): PageResult<CatalogueItem> = tx.execute {
        val root = locationId ?: ownTillLocation()
        support.assertAccess(root)
        val dealer = customerId?.let { customers.get(it).type == CustomerType.AUTHORISED_DEALER } ?: false
        val today = support.today()
        val retail = StockSql.retailPriceAt(today)
        val dealerPrice = StockSql.dealerPriceAt(today)
        val unit = StockSql.unitName()
        val category = StockSql.categoryName()
        val locations = hierarchy.withAreas(root)
        val parsed = search.parse(q)
        val onHand = StockBalancesTable.quantityOnHand.sum()
        val inStock = StockBalancesTable.select(StockBalancesTable.stockItemId)
            .where { StockBalancesTable.locationId inList locations }
            .groupBy(StockBalancesTable.stockItemId)
            .having { onHand greater BigDecimal.ZERO }
        val condition = listOfNotNull(
            StockItemsTable.tenantId eq support.tenantId(),
            StockItemsTable.status eq "ACTIVE",
            (StockItemsTable.itemType neq "DIRECT_ENTRY") or (StockItemsTable.locationId eq root),
            retail.isNotNull(),
            categoryId?.let { StockItemsTable.categoryId eq it },
            if (includeOutOfStock) null else StockItemsTable.id inSubQuery inStock,
            search.condition(parsed),
        ).allOf()!!
        val order = search.rank(parsed) + listOf(StockItemsTable.name to SortOrder.ASC, StockItemsTable.id to SortOrder.ASC)
        Paging.limitStatementTime()
        val page = Paging.fetch(
            StockItemsTable.select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.barcode, StockItemsTable.name, StockItemsTable.reorderLevel,
                retail, dealerPrice, unit, category).where(condition).orderBy(*order.toTypedArray()),
            paging,
        ) { it }
        val have = availability.available(root, page.items.map { it[StockItemsTable.id].value })
        page.withItems(page.items.map { row ->
            val id = row[StockItemsTable.id].value
            val retailPrice = Money.round(row[retail] ?: BigDecimal.ZERO)
            val dealerPriceValue = Money.round(row[dealerPrice] ?: retailPrice)
            val available = have[id] ?: BigDecimal.ZERO
            CatalogueItem(
                stockItem = Ref.stockItem(id), code = row[StockItemsTable.code], barcode = row[StockItemsTable.barcode], name = row[StockItemsTable.name],
                category = row[category], unit = row[unit] ?: "Unit", price = if (dealer) dealerPriceValue else retailPrice,
                retailPrice = retailPrice, dealerPrice = dealerPriceValue, available = available,
                lowStock = available < row[StockItemsTable.reorderLevel],
            )
        })
    }

    private fun ownTillLocation(): UUID =
        TillSessionsTable.select(TillSessionsTable.locationId)
            .where { (TillSessionsTable.tenantId eq support.tenantId()) and (TillSessionsTable.cashierUserId eq support.userId()) and (TillSessionsTable.status eq "OPEN") }
            .firstOrNull()?.get(TillSessionsTable.locationId)?.value
            ?: throw ApiErrors.validationFailed(mapOf("locationId" to "give the showroom, or open a till first"))
}
