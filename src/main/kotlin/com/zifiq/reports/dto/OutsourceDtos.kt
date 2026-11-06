package com.zifiq.reports.dto

import com.zifiq.platform.directory.Ref
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One outsource receipt line with its lifetime sell-through (docs/13 §4.3). `unitCost` and
 * `marginRealised` are null for a caller without `factory.costing.read`. `sellingPrice` is the
 * average realised net unit price of the item at that showroom.
 */
data class OutsourceStockRow(
    val date: LocalDate,
    val ref: String,
    val product: Ref?,
    val vendor: Ref?,
    val showroom: Ref?,
    val qtySourced: BigDecimal,
    val qtySold: BigDecimal,
    val remaining: BigDecimal,
    val sellThroughPct: BigDecimal,
    val unitCost: BigDecimal?,
    val sellingPrice: BigDecimal,
    val marginRealised: BigDecimal?,
    val status: String,
)

data class OutsourceStockReport(
    val rows: List<OutsourceStockRow>,
    val stats: Map<String, Any?>,
    val costVisible: Boolean,
)
