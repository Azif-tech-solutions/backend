package com.zifiq.customers.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class DistributorSummary(
    val id: UUID,
    val companyName: String,
    val contactPerson: String,
    val email: String,
    val phone: String,
    val creditLimit: BigDecimal,
    val currentBalance: BigDecimal,
    val category: String, // DISTRIBUTOR, NO_CREDIT, SPECIAL
)

data class PriceListItem(
    val productCode: String,
    val productName: String,
    val wholesalePrice: BigDecimal,
    val retailPrice: BigDecimal,
    val currency: String,
)

data class InstantDiscountRequest(
    @field:NotBlank val invoiceNumber: String,
    @field:NotNull @field:Positive val discountAmount: BigDecimal,
    @field:NotBlank val reason: String,
)

data class InstantDiscountSummary(
    val id: UUID,
    val invoiceNumber: String,
    val discountAmount: BigDecimal,
    val approvedBy: String,
    val timestamp: Instant,
)

data class CustomerContactRequest(
    @field:NotBlank val customerName: String,
    @field:NotBlank val email: String,
    @field:NotBlank val phone: String,
    val address: String?,
)

data class CustomerContactSummary(
    val id: UUID,
    val customerName: String,
    val email: String,
    val phone: String,
    val address: String?,
)

data class OtpRequest(
    @field:NotBlank val accountHolderId: String,
    @field:NotNull @field:Positive val amount: BigDecimal,
)

data class OtpSummary(
    val otpId: UUID,
    val status: String,
    val expiresAt: Instant,
)

data class PendingSupplyItem(
    val id: UUID,
    val invoiceNumber: String,
    val customerName: String,
    val productCode: String,
    val productName: String,
    val quantityPending: Double,
    val paidAmount: BigDecimal,
    val orderDate: Instant,
)
