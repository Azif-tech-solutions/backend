package com.zifiq.inventory.repository

import com.zifiq.common.search.RawExpression
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
    fun runningBalance(): Expression<BigDecimal> = RawExpression({
        +"(sum("; +StockLedgerEntriesTable.qtyIn; +" - "; +StockLedgerEntriesTable.qtyOut; +") over (order by "
        +StockLedgerEntriesTable.entryDate; +", "; +StockLedgerEntriesTable.createdAt; +", "; +StockLedgerEntriesTable.id; +"))"
    })

    /** The same per day, for a query grouped by entry date. */
    fun dailyRunningBalance(): Expression<BigDecimal> = RawExpression({
        +"(sum(sum("; +StockLedgerEntriesTable.qtyIn; +") - sum("; +StockLedgerEntriesTable.qtyOut; +")) over (order by "; +StockLedgerEntriesTable.entryDate; +"))"
    })

    /** Σ(in − out), zero when no rows. */
    fun net(): Expression<BigDecimal?> = RawExpression({ +"coalesce(sum("; +StockLedgerEntriesTable.qtyIn; +" - "; +StockLedgerEntriesTable.qtyOut; +"), 0)" })

    fun received(): Expression<BigDecimal?> = RawExpression({ +"coalesce(sum("; +StockLedgerEntriesTable.qtyIn; +"), 0)" })

    fun issued(): Expression<BigDecimal?> = RawExpression({ +"coalesce(sum("; +StockLedgerEntriesTable.qtyOut; +"), 0)" })
}
