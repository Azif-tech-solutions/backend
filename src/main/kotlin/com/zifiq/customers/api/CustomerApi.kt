package com.zifiq.customers.api

import com.zifiq.customers.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Customers & Pricing", description = "Distributors, Price Lists, Instant Discounts, Contacts & Pending Supply")
@SecurityRequirement(name = "bearerAuth")
interface CustomerApi {
    @Operation(summary = "Get distributor and customer listing by category")
    fun getDistributors(kind: String?): ApiEnvelope<List<DistributorSummary>>

    @Operation(summary = "Get product price list (wholesale & retail)")
    fun getPriceList(): ApiEnvelope<List<PriceListItem>>

    @Operation(summary = "Apply instant point-of-sale discount")
    fun applyInstantDiscount(request: InstantDiscountRequest): ApiEnvelope<InstantDiscountSummary>

    @Operation(summary = "Add new customer contact record")
    fun addCustomerContact(request: CustomerContactRequest): ApiEnvelope<CustomerContactSummary>

    @Operation(summary = "Issue wallet withdrawal one-time code (OTP)")
    fun requestWithdrawalOtp(request: OtpRequest): ApiEnvelope<OtpSummary>

    @Operation(summary = "Get orders paid for but pending delivery/supply")
    fun getPendingSupplies(): ApiEnvelope<List<PendingSupplyItem>>
}
