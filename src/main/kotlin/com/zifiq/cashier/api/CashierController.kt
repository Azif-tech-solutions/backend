package com.zifiq.cashier.api

import com.zifiq.cashier.dto.*
import com.zifiq.cashier.service.CashierService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/cashier")
class CashierController(
    private val cashierService: CashierService,
) : CashierApi {
    @GetMapping("/sales")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun getSalesReport(): ApiEnvelope<List<SalesReportItem>> =
        ApiEnvelope(cashierService.getSalesReport())

    @PostMapping("/pro-forma")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun generateProForma(
        @Valid @RequestBody request: ProFormaInvoiceRequest,
    ): ApiEnvelope<ProFormaInvoiceSummary> =
        ApiEnvelope(cashierService.generateProForma(request))

    @PostMapping("/waybills")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun generateWaybill(
        @Valid @RequestBody request: WaybillGenerationRequest,
    ): ApiEnvelope<WaybillSummary> =
        ApiEnvelope(cashierService.generateWaybill(request))

    @PostMapping("/pos/checkout")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun processPosCheckout(
        @Valid @RequestBody request: PosCheckoutRequest,
    ): ApiEnvelope<PosCheckoutSummary> =
        ApiEnvelope(cashierService.processPosCheckout(request))
}
