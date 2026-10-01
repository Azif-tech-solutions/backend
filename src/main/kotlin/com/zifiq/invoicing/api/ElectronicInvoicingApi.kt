package com.zifiq.invoicing.api

import com.zifiq.invoicing.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Electronic Invoicing", description = "Invoice Generation, PDF & Email Delivery, Status & Payment Tracking, Archive Search")
@SecurityRequirement(name = "bearerAuth")
interface ElectronicInvoicingApi {
    @Operation(summary = "Create an electronic invoice from a sale or pro-forma")
    fun generateInvoice(request: InvoiceGenerationRequest): ApiEnvelope<ElectronicInvoiceSummary>

    @Operation(summary = "Render and send electronic invoice via PDF/Email")
    fun deliverInvoice(request: InvoiceDeliveryRequest): ApiEnvelope<InvoiceDeliverySummary>

    @Operation(summary = "Search electronic invoice history archive")
    fun getInvoiceHistory(): ApiEnvelope<List<ElectronicInvoiceSummary>>
}
