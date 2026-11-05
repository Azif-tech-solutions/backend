package com.zifiq.barcode.service

import com.zifiq.inventory.model.StockItemCodesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.springframework.stereotype.Component

/**
 * Global search: an exact barcode/QR code typed into the header search resolves to its item
 * (docs/11 §9). This is an exact `code_normalized` equality (unique index), never fuzzy — a fuzzy
 * barcode match would point at the wrong product. Runs in the caller's transaction.
 */
@Component
class BarcodeSearchProvider(
    private val support: BarcodeSupport,
) : SearchProvider {
    override val type = "barcode"
    override val label = "Barcodes"
    override val listRoute = "/inventory/stock-items"

    override fun canSearch() = support.can("barcode.lookup")

    override fun search(q: String, limit: Int): List<SearchHit> {
        val normalized = q.uppercase().replace(Regex("\\s"), "")
        return StockItemCodesTable
            .join(StockItemsTable, JoinType.INNER, StockItemCodesTable.stockItemId, StockItemsTable.id)
            .select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, StockItemCodesTable.symbology, StockItemCodesTable.status, StockItemsTable.status)
            .where { (StockItemCodesTable.tenantId eq support.tenantId()) and (StockItemCodesTable.codeNormalized eq normalized) }
            .limit(limit)
            .map { r ->
                val itemId = r[StockItemsTable.id].value
                SearchHit(
                    type = type, id = itemId, title = r[StockItemsTable.name],
                    subtitle = listOf(r[StockItemsTable.code], r[StockItemCodesTable.symbology], r[StockItemsTable.status].lowercase()).joinToString(" · "),
                    ref = r[StockItemsTable.code], route = "/inventory/stock-items/$itemId",
                )
            }
    }
}
