package com.zifiq.cashier.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One supplied line of one waybill, for Audit's Waybill History (docs/10 §4.1). The running
 * `outstanding` (the line balance after this waybill) is stored on the waybill line, so no
 * recomputation is needed. Ids are plain; the audit module wraps them in references.
 */
data class SupplyHistoryRow(
    val saleId: UUID,
    val saleRef: String?,
    val invoiceNumber: String?,
    val saleDate: LocalDate?,
    val customerId: UUID?,
    val customerName: String,
    val waybillId: UUID,
    val waybillRef: String,
    val supplyDate: LocalDate,
    val saleLineId: UUID,
    val stockItemId: UUID,
    val productName: String,
    val ordered: BigDecimal,
    val qtySupplied: BigDecimal,
    val outstanding: BigDecimal,
    val driverId: UUID?,
    val driverName: String?,
    val receivedBy: String?,
    val status: String,
    val locationId: UUID,
)

/** Supply-date range is required for the all-invoices view; single `invoice` lookups ignore it. */
data class SupplyHistoryFilter(
    val invoice: String? = null,
    val customerId: UUID? = null,
    val driverId: UUID? = null,
    val locationId: UUID? = null,
    val status: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

interface WaybillHistoryQueries {
    /** Supplied waybill lines matching the filter, newest supply first; the audit module groups them by invoice. */
    fun supplyHistory(filter: SupplyHistoryFilter): List<SupplyHistoryRow>
}
