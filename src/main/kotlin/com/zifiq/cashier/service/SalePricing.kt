package com.zifiq.cashier.service

import com.zifiq.account.spi.VatRates
import com.zifiq.account.spi.VatSetting
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.tax.LineAmounts
import com.zifiq.common.tax.VatCategoriesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.PriceListRepository
import com.zifiq.inventory.repository.StockSql
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component

/** One priced cart line: what is sold, at what price, and its money (docs/09 §2.3). */
data class PricedSaleLine(
    val lineNo: Int,
    val stockItemId: UUID,
    val code: String,
    val name: String,
    val unit: String,
    val category: String?,
    val priceBasis: String,
    val priceListRef: String?,
    val vatCategoryCode: String,
    val amounts: LineAmounts,
)

/**
 * Prices a cart (docs/09 §1.2-7): every price comes from the inventory price list in force on the
 * sale date — the dealer price for an authorised dealer, retail for everyone else — and the cashier
 * never types one. VAT is the selling showroom's rate, per line, with the same arithmetic as the
 * invoice the sale becomes ([LineAmounts]). Caller's transaction.
 */
@Component
class SalePricing(
    private val prices: PriceListRepository,
    private val vatRates: VatRates,
) {
    fun vatAt(locationId: UUID, date: LocalDate): VatSetting = vatRates.forLocation(locationId, date)

    /**
     * Prices [inputs] (item → quantity, merged per item in first-seen order) for a sale at [locationId].
     * Unknown, suspended or another showroom's direct-entry items are not sellable (rule 1); an item
     * without a price in force can't be sold (409 NO_PRICE_SET).
     */
    fun price(locationId: UUID, dealer: Boolean, inputs: List<Pair<UUID, BigDecimal>>, date: LocalDate, vat: VatSetting): List<PricedSaleLine> {
        if (inputs.isEmpty()) return emptyList()
        val merged = LinkedHashMap<UUID, BigDecimal>()
        inputs.forEach { (itemId, qty) -> merged.merge(itemId, qty.setScale(3, RoundingMode.HALF_UP), BigDecimal::add) }
        val unitName = StockSql.unitName()
        val categoryName = StockSql.categoryName()
        val items = StockItemsTable.join(VatCategoriesTable, JoinType.INNER, StockItemsTable.vatCategoryId, VatCategoriesTable.id)
            .select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, StockItemsTable.status, StockItemsTable.itemType,
                StockItemsTable.locationId, VatCategoriesTable.code, unitName, categoryName)
            .where { StockItemsTable.id inList merged.keys }
            .associateBy { it[StockItemsTable.id].value }
        val priced = prices.currentPrices(merged.keys, date)
        return merged.entries.mapIndexed { index, (itemId, quantity) ->
            val item = items[itemId] ?: throw ApiErrors.itemNotSellable(itemId.toString(), "no such item")
            val code = item[StockItemsTable.code]
            if (item[StockItemsTable.status] != "ACTIVE") throw ApiErrors.itemNotSellable(code, "it is ${item[StockItemsTable.status].lowercase()}")
            if (item[StockItemsTable.itemType] == "DIRECT_ENTRY" && item[StockItemsTable.locationId] != locationId) {
                throw ApiErrors.itemNotSellable(code, "it is held only at the showroom that entered it")
            }
            val price = priced[itemId] ?: throw ApiErrors.noPriceSet(code)
            val vatCode = item[VatCategoriesTable.code]
            PricedSaleLine(
                lineNo = index + 1, stockItemId = itemId, code = code, name = item[StockItemsTable.name], unit = item[unitName] ?: "Unit",
                category = item[categoryName], priceBasis = if (dealer) "DEALER" else "RETAIL", priceListRef = price.batchRef, vatCategoryCode = vatCode,
                amounts = LineAmounts.of(quantity, price.priceFor(dealer), BigDecimal.ZERO, LineAmounts.vatRate(vatCode, vat.enabled, vat.rate)),
            )
        }
    }

    /** Replaces the cart's lines and header totals, and bumps its version (the till and any watcher reload). */
    fun write(saleId: UUID, tenantId: UUID, lines: List<PricedSaleLine>, vat: VatSetting, dealer: Boolean) {
        SaleLinesTable.deleteWhere { SaleLinesTable.saleId eq saleId }
        SaleLinesTable.batchInsert(lines, shouldReturnGeneratedValues = false) { line ->
            this[SaleLinesTable.tenantId] = tenantId
            this[SaleLinesTable.saleId] = saleId
            this[SaleLinesTable.lineNo] = line.lineNo
            this[SaleLinesTable.stockItemId] = line.stockItemId
            this[SaleLinesTable.itemCode] = line.code.take(40)
            this[SaleLinesTable.itemName] = line.name.take(200)
            this[SaleLinesTable.unitName] = line.unit.take(60)
            this[SaleLinesTable.categoryName] = line.category?.take(120)
            this[SaleLinesTable.quantity] = line.amounts.quantity
            this[SaleLinesTable.unitPrice] = line.amounts.unitPrice
            this[SaleLinesTable.priceBasis] = line.priceBasis
            this[SaleLinesTable.priceListRef] = line.priceListRef?.take(30)
            this[SaleLinesTable.lineDiscount] = line.amounts.discount
            this[SaleLinesTable.instantDiscountShare] = BigDecimal.ZERO.setScale(2)
            this[SaleLinesTable.lineNet] = line.amounts.net
            this[SaleLinesTable.vatCategoryCode] = line.vatCategoryCode
            this[SaleLinesTable.vatRate] = line.amounts.vatRate
            this[SaleLinesTable.vatAmount] = line.amounts.vat
            this[SaleLinesTable.lineTotal] = line.amounts.total
            this[SaleLinesTable.supplyNowQty] = BigDecimal.ZERO
            this[SaleLinesTable.suppliedQty] = BigDecimal.ZERO
        }
        val totals = SaleTotals.of(lines.map { it.amounts })
        SalesTable.update({ SalesTable.id eq saleId }) {
            it[priceBasis] = if (dealer) "DEALER" else "RETAIL"
            it[grossAmount] = totals.gross
            it[lineDiscountTotal] = totals.discount
            it[instantDiscountTotal] = BigDecimal.ZERO.setScale(2)
            it[netAmount] = totals.net
            it[vatEnabled] = vat.enabled
            it[vatRate] = vat.rate.setScale(2, RoundingMode.HALF_UP)
            it[vatAmount] = totals.vat
            it[total] = totals.total
            it[version] = version + 1
            it[updatedAt] = Instant.now()
        }
    }
}

/** A sale's header totals: sums of its lines, so the header always equals the lines (and the invoice). */
data class SaleTotals(val gross: BigDecimal, val discount: BigDecimal, val net: BigDecimal, val vat: BigDecimal, val total: BigDecimal) {
    companion object {
        fun of(lines: List<LineAmounts>): SaleTotals {
            fun sum(f: (LineAmounts) -> BigDecimal) = lines.fold(BigDecimal.ZERO) { s, l -> s + f(l) }
            return SaleTotals(Money.round(sum { it.value }), Money.round(sum { it.discount }), Money.round(sum { it.net }), Money.round(sum { it.vat }), Money.round(sum { it.total }))
        }
    }
}
