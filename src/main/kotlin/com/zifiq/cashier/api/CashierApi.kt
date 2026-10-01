package com.zifiq.cashier.api

import com.zifiq.cashier.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Cashier & POS", description = "Sales Reports, Pro-Forma Invoices, Waybills & POS Transactions")
@SecurityRequirement(name = "bearerAuth")
interface CashierApi {
    @Operation(summary = "Get sales report by period, channel, and cashier")
    fun getSalesReport(): ApiEnvelope<List<SalesReportItem>>

    @Operation(summary = "Issue a pro-forma invoice quotation before sale")
    fun generateProForma(request: ProFormaInvoiceRequest): ApiEnvelope<ProFormaInvoiceSummary>

    @Operation(summary = "Generate a waybill for goods leaving factory or showroom")
    fun generateWaybill(request: WaybillGenerationRequest): ApiEnvelope<WaybillSummary>

    @Operation(summary = "Process point-of-sale terminal transaction")
    fun processPosCheckout(request: PosCheckoutRequest): ApiEnvelope<PosCheckoutSummary>
}
