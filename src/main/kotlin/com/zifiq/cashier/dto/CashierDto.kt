package com.zifiq.cashier.dto

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class SalesReportItem(
    val id: UUID,
    val invoiceNumber: String,
    val channel: String,
    val cashierName: String,
    val totalAmount: BigDecimal,
    val vatAmount: BigDecimal,
    val timestamp: Instant,
)

data class ProFormaInvoiceRequest(
    val customerName: String,
    val customerEmail: String?,
    @field:NotEmpty val items: List<SaleLineItem>,
)

data class SaleLineItem(
    val productCode: String,
    val productName: String,
    @field:Positive val quantity: Double,
    @field:NotNull @field:Positive val unitPrice: BigDecimal,
)

data class ProFormaInvoiceSummary(
    val id: UUID,
    val proFormaNumber: String,
    val customerName: String,
    val totalAmount: BigDecimal,
    val validUntil: Instant,
)

data class WaybillGenerationRequest(
    val salesInvoiceNumber: String,
    val destinationAddress: String,
    val driverName: String,
    val vehicleRegNumber: String,
)

data class WaybillSummary(
    val id: UUID,
    val waybillNumber: String,
    val salesInvoiceNumber: String,
    val status: String,
    val issuedAt: Instant,
)

data class PosCheckoutRequest(
    val customerId: UUID?,
    @field:NotEmpty val items: List<SaleLineItem>,
    val paymentMode: String, // CASH, CARD, POS, TRANSFER
    val discountPercent: BigDecimal = BigDecimal.ZERO,
)

data class PosCheckoutSummary(
    val transactionId: UUID,
    val receiptNumber: String,
    val totalPaid: BigDecimal,
    val changeDue: BigDecimal,
    val timestamp: Instant,
)
