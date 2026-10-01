package com.zifiq.customers.api

import com.zifiq.customers.dto.*
import com.zifiq.customers.service.CustomerService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController(
    private val customerService: CustomerService,
) : CustomerApi {
    @GetMapping("/distributors")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun getDistributors(@RequestParam(required = false) kind: String?): ApiEnvelope<List<DistributorSummary>> =
        ApiEnvelope(customerService.getDistributors(kind))

    @GetMapping("/price-list")
    override fun getPriceList(): ApiEnvelope<List<PriceListItem>> =
        ApiEnvelope(customerService.getPriceList())

    @PostMapping("/discounts/instant")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun applyInstantDiscount(
        @Valid @RequestBody request: InstantDiscountRequest,
    ): ApiEnvelope<InstantDiscountSummary> =
        ApiEnvelope(customerService.applyInstantDiscount(request))

    @PostMapping("/contacts")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun addCustomerContact(
        @Valid @RequestBody request: CustomerContactRequest,
    ): ApiEnvelope<CustomerContactSummary> =
        ApiEnvelope(customerService.addCustomerContact(request))

    @PostMapping("/otp/request")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun requestWithdrawalOtp(
        @Valid @RequestBody request: OtpRequest,
    ): ApiEnvelope<OtpSummary> =
        ApiEnvelope(customerService.requestWithdrawalOtp(request))

    @GetMapping("/pending-supplies")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun getPendingSupplies(): ApiEnvelope<List<PendingSupplyItem>> =
        ApiEnvelope(customerService.getPendingSupplies())
}
