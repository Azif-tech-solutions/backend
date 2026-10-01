package com.zifiq.invoicing.api

import com.zifiq.invoicing.dto.*
import com.zifiq.invoicing.service.ElectronicInvoicingService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/invoicing")
class ElectronicInvoicingController(
    private val invoicingService: ElectronicInvoicingService,
) : ElectronicInvoicingApi {
    @PostMapping("/generate")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun generateInvoice(
        @Valid @RequestBody request: InvoiceGenerationRequest,
    ): ApiEnvelope<ElectronicInvoiceSummary> =
        ApiEnvelope(invoicingService.generateInvoice(request))

    @PostMapping("/deliver")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun deliverInvoice(
        @Valid @RequestBody request: InvoiceDeliveryRequest,
    ): ApiEnvelope<InvoiceDeliverySummary> =
        ApiEnvelope(invoicingService.deliverInvoice(request))

    @GetMapping("/history")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun getInvoiceHistory(): ApiEnvelope<List<ElectronicInvoiceSummary>> =
        ApiEnvelope(invoicingService.getInvoiceHistory())
}
