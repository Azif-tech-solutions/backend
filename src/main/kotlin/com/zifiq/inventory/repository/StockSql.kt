package com.zifiq.inventory.repository

import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import java.time.LocalDate
import com.zifiq.common.search.RawExpression
import com.zifiq.common.sql.RunningBalance
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import java.math.BigDecimal
import org.jetbrains.exposed.sql.Expression

/**
 * SQL fragments every stock query shares. Each call returns a new expression: keep it in a `val`
 * and use that same instance in `select(...)` and when reading the row.
 */
object StockSql {
    /** The unit's name for a unit-of-measure id column (a stock item's unit by default). */
    fun unitName(unitColumn: Expression<*> = StockItemsTable.unitOfMeasureId): Expression<String> =
        RawExpression({ +"(select u.name from units_of_measure u where u.id = "; +unitColumn; +")" })

    /** Running Σ(in − out) over the selected ledger rows in posting order (window; add the opening balance). */
    fun runningBalance(): Expression<BigDecimal> = RunningBalance.window(
        RawExpression<BigDecimal>({ +StockLedgerEntriesTable.qtyIn; +" - "; +StockLedgerEntriesTable.qtyOut }),
        StockLedgerEntriesTable.entryDate, StockLedgerEntriesTable.createdAt, StockLedgerEntriesTable.id,
    )

    /** The same per day, for a query grouped by entry date. */
    fun dailyRunningBalance(): Expression<BigDecimal> = RawExpression({
        +"(sum(sum("; +StockLedgerEntriesTable.qtyIn; +") - sum("; +StockLedgerEntriesTable.qtyOut; +")) over (order by "; +StockLedgerEntriesTable.entryDate; +"))"
    })

    /**
     * The retail price of the item in [itemColumn] in force on [date]: the latest price effective on or
     * before it, one row of stock_item_prices_lookup_idx. Null when the item has never been priced.
     */
    fun retailPriceAt(date: LocalDate, itemColumn: Expression<*> = StockItemsTable.id, tenantColumn: Expression<*> = StockItemsTable.tenantId): Expression<BigDecimal?> =
        RawExpression({
            +"(select p.retail_price from stock_item_prices p where p.tenant_id = "; +tenantColumn; +" and p.stock_item_id = "; +itemColumn
            +" and p.effective_from <= "; registerArgument(JavaLocalDateColumnType(), date); +" order by p.effective_from desc limit 1)"
        })

    /** Σ(in − out), zero when no rows. */
    fun net(): Expression<BigDecimal?> = RawExpression({ +"coalesce(sum("; +StockLedgerEntriesTable.qtyIn; +" - "; +StockLedgerEntriesTable.qtyOut; +"), 0)" })

    fun received(): Expression<BigDecimal?> = RawExpression({ +"coalesce(sum("; +StockLedgerEntriesTable.qtyIn; +"), 0)" })

    fun issued(): Expression<BigDecimal?> = RawExpression({ +"coalesce(sum("; +StockLedgerEntriesTable.qtyOut; +"), 0)" })
}
