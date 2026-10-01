package com.zifiq.invoicing.dto

import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class InvoiceGenerationRequest(
    @field:NotBlank val sourceReference: String, // Sale transaction or pro-forma number
    @field:NotBlank val customerName: String,
    val customerEmail: String?,
    val taxId: String?,
)

data class ElectronicInvoiceSummary(
    val invoiceId: UUID,
    val invoiceNumber: String,
    val customerName: String,
    val totalAmount: BigDecimal,
    val taxAmount: BigDecimal,
    val status: String, // UNPAID, PARTIAL, PAID
    val pdfUrl: String,
    val createdAt: Instant,
)

data class InvoiceDeliveryRequest(
    val invoiceId: UUID,
    @field:NotBlank val recipientEmail: String,
)

data class InvoiceDeliverySummary(
    val invoiceId: UUID,
    val deliveryStatus: String,
    val dispatchedAt: Instant,
)
