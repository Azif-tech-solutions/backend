package com.zifiq.cashier.service

import com.zifiq.cashier.dto.*
import com.zifiq.platform.identity.UserDirectory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class CashierService(
    private val userDirectory: UserDirectory,
) {
    fun getSalesReport(): List<SalesReportItem> {
        val currentUser = runCatching { userDirectory.getCurrentUser() }.getOrNull()
        val cashierName = currentUser?.displayName ?: "Active Cashier"

        return listOf(
            SalesReportItem(
                id = UUID.randomUUID(),
                invoiceNumber = "INV-2026-001",
                channel = "POS Terminal 1",
                cashierName = cashierName,
                totalAmount = BigDecimal("850.00"),
                vatAmount = BigDecimal("63.75"),
                timestamp = Instant.now(),
            ),
        )
    }

    fun generateProForma(request: ProFormaInvoiceRequest): ProFormaInvoiceSummary {
        val total = request.items.sumOf { it.unitPrice * BigDecimal.valueOf(it.quantity) }
        return ProFormaInvoiceSummary(
            id = UUID.randomUUID(),
            proFormaNumber = "PRO-" + UUID.randomUUID().toString().take(8).uppercase(),
            customerName = request.customerName,
            totalAmount = total,
            validUntil = Instant.now().plusSeconds(86400 * 7), // 7 days
        )
    }

    fun generateWaybill(request: WaybillGenerationRequest): WaybillSummary {
        return WaybillSummary(
            id = UUID.randomUUID(),
            waybillNumber = "WAY-" + UUID.randomUUID().toString().take(8).uppercase(),
            salesInvoiceNumber = request.salesInvoiceNumber,
            status = "DISPATCHED",
            issuedAt = Instant.now(),
        )
    }

    fun processPosCheckout(request: PosCheckoutRequest): PosCheckoutSummary {
        val total = request.items.sumOf { it.unitPrice * BigDecimal.valueOf(it.quantity) }
        return PosCheckoutSummary(
            transactionId = UUID.randomUUID(),
            receiptNumber = "REC-" + UUID.randomUUID().toString().take(8).uppercase(),
            totalPaid = total,
            changeDue = BigDecimal.ZERO,
            timestamp = Instant.now(),
        )
    }
}
