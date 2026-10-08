package com.zifiq.inventory.repository

import com.zifiq.inventory.model.StockItemPricesTable
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

data class PriceRef(
    val priceId: UUID,
    val stockItemId: UUID,
    val retailPrice: BigDecimal,
    val dealerPrice: BigDecimal,
    val effectiveFrom: LocalDate,
    val batchRef: String,
) {
    /** What a customer pays: dealers the dealer price, everyone else the retail price. */
    fun priceFor(dealer: Boolean): BigDecimal = if (dealer) dealerPrice else retailPrice
}

/**
 * Selling prices for other modules (Showroom returns, POS, pro-formas, labels) — docs/05 §4.12.
 * The price of an item on a date is the row with the latest `effective_from` on or before it.
 * Runs in the caller's transaction; RLS scopes it to the tenant.
 */
@Component
class PriceListRepository {
    fun currentPrice(stockItemId: UUID, date: LocalDate): PriceRef? =
        StockItemPricesTable.selectAll()
            .where { (StockItemPricesTable.stockItemId eq stockItemId) and (StockItemPricesTable.effectiveFrom lessEq date) }
            .orderBy(StockItemPricesTable.effectiveFrom to SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.let(::toRef)

    fun currentPrices(stockItemIds: Collection<UUID>, date: LocalDate): Map<UUID, PriceRef> {
        if (stockItemIds.isEmpty()) return emptyMap()
        return StockItemPricesTable.selectAll()
            .where { (StockItemPricesTable.stockItemId inList stockItemIds) and (StockItemPricesTable.effectiveFrom lessEq date) }
            .orderBy(StockItemPricesTable.effectiveFrom to SortOrder.DESC)
            .map(::toRef)
            .groupBy { it.stockItemId }
            .mapValues { (_, prices) -> prices.first() }
    }

    private fun toRef(row: org.jetbrains.exposed.sql.ResultRow) = PriceRef(
        priceId = row[StockItemPricesTable.id].value,
        stockItemId = row[StockItemPricesTable.stockItemId].value,
        retailPrice = row[StockItemPricesTable.retailPrice],
        dealerPrice = row[StockItemPricesTable.dealerPrice],
        effectiveFrom = row[StockItemPricesTable.effectiveFrom],
        batchRef = row[StockItemPricesTable.batchRef],
    )
}
