package com.zifiq.cashier.spi

import java.util.UUID

/** Whether a stock item is referenced by live Sales & POS documents — for the Recycle Bin's delete/suspend guard (docs/10 §4.9). */
interface StockItemUsageQueries {
    /** True if the item is on a line of an OPEN sale or a non-terminal pro-forma (DRAFT / PENDING_APPROVAL / SENT). */
    fun inOpenDocuments(stockItemId: UUID): Boolean

    /**
     * Live Sales & POS lines that would reflect a data-quality rename: OPEN sale lines plus
     * non-terminal pro-forma lines for [stockItemId] (docs/10 §4.8, "records affected").
     */
    fun liveDocumentLineCount(stockItemId: UUID): Int

    /** Dispatched waybill lines for [stockItemId] — issued documents that keep the original name (docs/10 §4.8). */
    fun waybillLineCount(stockItemId: UUID): Int
}
