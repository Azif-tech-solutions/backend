package com.zifiq.history.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One stock-affecting movement (docs/10 §4.3), read from the single stock ledger every module writes
 * to. `product`/`location` are references; `unitCost`/`value` are cost figures, zeroed for callers
 * without `factory.costing.read` (doc 03 §14).
 */
data class StockMovementHistoryRow(
    val date: LocalDate,
    val ref: String,
    val product: Ref,
    val location: Ref,
    /** Human label for [sourceType], e.g. "Stock transfer out". */
    val source: String,
    val sourceType: String,
    /** IN or OUT. */
    val direction: String,
    /** Signed: positive in, negative out. */
    val quantity: BigDecimal,
    val recordedBy: UserRef,
    val unitCost: BigDecimal?,
    val value: BigDecimal?,
    val remark: String?,
)
