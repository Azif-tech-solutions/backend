package com.zifiq.showroom.service

import com.zifiq.showroom.dto.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class ShowroomInventoryService {
    fun listStock(): List<ShowroomStockItem> = listOf(
        ShowroomStockItem(
            productCode = "P-1001",
            productName = "Premium Blend",
            quantity = 150.0,
            unit = "pcs",
            warehouseCode = "SHOW-001/DISPLAY",
            status = "AVAILABLE",
        ),
        ShowroomStockItem(
            productCode = "P-1002",
            productName = "Classic Roast",
            quantity = 85.0,
            unit = "pcs",
            warehouseCode = "SHOW-001/STORAGE",
            status = "AVAILABLE",
        ),
    )

    fun recordDirectEntry(request: DirectStockEntryRequest): InventoryEntrySummary =
        InventoryEntrySummary(
            id = UUID.randomUUID(),
            warehouseCode = request.warehouseCode,
            productCode = request.productCode,
            quantity = request.quantity,
            unit = request.unit,
            source = request.source,
            status = "ADDED",
        )

    fun receiveTransfer(request: ReceiveTransferRequest): ReceiveTransferSummary =
        ReceiveTransferSummary(
            receiptId = UUID.randomUUID(),
            transferWaybillNumber = request.transferWaybillNumber,
            showroomCode = request.showroomCode,
            totalItemsReceived = 50,
            status = "CONFIRMED",
            receivedAt = Instant.now(),
        )

    fun transferBetweenShowrooms(request: ShowroomTransferRequest): ShowroomTransferSummary =
        ShowroomTransferSummary(
            transferId = UUID.randomUUID(),
            waybillNumber = "STR-" + UUID.randomUUID().toString().take(8).uppercase(),
            status = "IN_TRANSIT",
            transferredAt = Instant.now(),
        )

    fun processReturn(request: ReturnProcessingRequest): ReturnProcessingSummary =
        ReturnProcessingSummary(
            returnId = UUID.randomUUID(),
            refundAmount = BigDecimal("120.00"),
            status = "APPROVED_RESTOCKED",
            processedAt = Instant.now(),
        )

    fun getVatReport(showroomCode: String): ShowroomVatReport =
        ShowroomVatReport(
            showroomCode = showroomCode,
            grossSalesAmount = BigDecimal("45000.00"),
            vatRate = BigDecimal("7.5"),
            totalVatCollected = BigDecimal("3375.00"),
            reportingPeriod = "CURRENT_MONTH",
        )
}
