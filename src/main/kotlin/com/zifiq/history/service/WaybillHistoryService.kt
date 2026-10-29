package com.zifiq.history.service

import com.zifiq.cashier.spi.SupplyHistoryFilter
import com.zifiq.cashier.spi.SupplyHistoryRow
import com.zifiq.cashier.spi.WaybillHistoryQueries
import com.zifiq.common.errors.ApiErrors
import com.zifiq.history.dto.WaybillHistoryInvoice
import com.zifiq.history.dto.WaybillHistoryLineRow
import com.zifiq.history.dto.WaybillHistoryResult
import com.zifiq.history.dto.WaybillInvoiceHeader
import com.zifiq.history.dto.WaybillStats
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.ref
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Waybill History (docs/10 §4.1): the supply history of a sales invoice, read through the cashier
 * SPI and grouped into invoice blocks here. Each row is one line of one waybill, with the running
 * outstanding straight off the waybill line; the stats aggregate per sale line.
 */
@Service
class WaybillHistoryService(private val supply: WaybillHistoryQueries) {
    fun history(
        invoice: String?,
        customerId: UUID?,
        driverId: UUID?,
        locationId: UUID?,
        status: String?,
        from: LocalDate?,
        to: LocalDate?,
        page: Int?,
        size: Int?,
    ): WaybillHistoryResult {
        val single = invoice?.trim()?.ifEmpty { null }
        if (single == null && (from == null || to == null)) {
            throw ApiErrors.validationFailed(mapOf("from" to "from and to are required unless an invoice is given"))
        }
        val rows = supply.supplyHistory(SupplyHistoryFilter(single, customerId, driverId, locationId, status, from, to))
        val blocks = rows.groupBy { it.saleId }
            .map { (saleId, saleRows) -> block(saleId, saleRows) }
            .sortedWith(compareByDescending { it.invoice.date ?: LocalDate.MIN })
        if (single != null) return WaybillHistoryResult(invoice = blocks.firstOrNull() ?: throw ApiErrors.validationFailed(mapOf("invoice" to "no supply history for this invoice")))
        val p = (page ?: 0).coerceAtLeast(0)
        val s = (size ?: 20).coerceIn(1, 200)
        val total = blocks.size.toLong()
        return WaybillHistoryResult(
            invoices = blocks.drop(p * s).take(s),
            page = p, size = s, totalItems = total,
            totalPages = if (total == 0L) 0 else ((total + s - 1) / s).toInt(),
        )
    }

    private fun block(saleId: UUID, rows: List<SupplyHistoryRow>): WaybillHistoryInvoice {
        val first = rows.first()
        val byLine = rows.groupBy { it.saleLineId }
        val quantityInvoiced = byLine.values.fold(BigDecimal.ZERO) { acc, lineRows -> acc + lineRows.first().ordered }
        val quantitySupplied = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.qtySupplied }
        val stillOutstanding = byLine.values.fold(BigDecimal.ZERO) { acc, lineRows -> acc + lineRows.minOf { it.outstanding } }
        val waybillsRaised = rows.map { it.waybillId }.distinct().size
        val fulfilledPct = if (quantityInvoiced.signum() == 0) 0
        else (quantitySupplied * BigDecimal(100)).divide(quantityInvoiced, 0, RoundingMode.HALF_UP).toInt()
        return WaybillHistoryInvoice(
            invoice = WaybillInvoiceHeader(
                number = first.invoiceNumber, saleRef = first.saleRef, date = first.saleDate,
                sale = Ref.sale(saleId), customer = ref(first.customerId, Ref::customer),
            ),
            stats = WaybillStats(
                quantityInvoiced = quantityInvoiced, quantitySupplied = quantitySupplied, fulfilledPct = fulfilledPct,
                stillOutstanding = stillOutstanding, waybillsRaised = waybillsRaised,
                deliveryPattern = if (waybillsRaised > 1) "Multi-delivery" else "Single-delivery",
            ),
            rows = rows.map {
                WaybillHistoryLineRow(
                    waybill = it.waybillRef, supplyDate = it.supplyDate, product = Ref.stockItem(it.stockItemId),
                    ordered = it.ordered, qtySupplied = it.qtySupplied, outstanding = it.outstanding,
                    driver = ref(it.driverId, Ref::driver), receivedBy = it.receivedBy, status = statusLabel(it.status),
                )
            },
        )
    }

    private fun statusLabel(status: String) = when (status.uppercase()) {
        "DELIVERED", "RECEIVED" -> "Delivered"
        "ISSUED" -> "Issued"
        "CANCELLED" -> "Cancelled"
        else -> status.lowercase().replaceFirstChar { it.uppercase() }
    }
}
