package com.zifiq.history.dto

import com.zifiq.platform.directory.Ref
import java.math.BigDecimal
import java.time.LocalDate

/** The supply history of one sales invoice (docs/10 §4.1). */
data class WaybillHistoryInvoice(
    val invoice: WaybillInvoiceHeader,
    val stats: WaybillStats,
    val rows: List<WaybillHistoryLineRow>,
)

data class WaybillInvoiceHeader(
    val number: String?,
    val saleRef: String?,
    val date: LocalDate?,
    val sale: Ref,
    val customer: Ref?,
)

data class WaybillStats(
    val quantityInvoiced: BigDecimal,
    val quantitySupplied: BigDecimal,
    val fulfilledPct: Int,
    val stillOutstanding: BigDecimal,
    val waybillsRaised: Int,
    val deliveryPattern: String,
)

data class WaybillHistoryLineRow(
    val waybill: String,
    val supplyDate: LocalDate,
    val product: Ref,
    val ordered: BigDecimal,
    val qtySupplied: BigDecimal,
    val outstanding: BigDecimal,
    val driver: Ref?,
    val receivedBy: String?,
    val status: String,
)

/**
 * Either one invoice's supply history (when `invoice` is given) or a page of invoices (otherwise),
 * matching docs/10 §4.1's two response shapes.
 */
data class WaybillHistoryResult(
    val invoice: WaybillHistoryInvoice? = null,
    val invoices: List<WaybillHistoryInvoice>? = null,
    val page: Int? = null,
    val size: Int? = null,
    val totalItems: Long? = null,
    val totalPages: Int? = null,
)
