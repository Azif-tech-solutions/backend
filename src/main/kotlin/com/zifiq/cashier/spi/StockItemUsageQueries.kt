package com.zifiq.cashier.spi

import java.util.UUID

/** Whether a stock item is referenced by live Sales & POS documents — for the Recycle Bin's delete/suspend guard (docs/10 §4.9). */
interface StockItemUsageQueries {
    /** True if the item is on a line of an OPEN sale or a non-terminal pro-forma (DRAFT / PENDING_APPROVAL / SENT). */
    fun inOpenDocuments(stockItemId: UUID): Boolean
}
