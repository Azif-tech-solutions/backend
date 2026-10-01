package com.zifiq.customers.service

import com.zifiq.customers.dto.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class CustomerService {
    fun getDistributors(kind: String?): List<DistributorSummary> {
        val cat = kind ?: "DISTRIBUTOR"
        return listOf(
            DistributorSummary(
                id = UUID.randomUUID(),
                companyName = "Apex Mega Wholesale",
                contactPerson = "Michael Chang",
                email = "apex@distributors.com",
                phone = "+1-555-0192",
                creditLimit = BigDecimal("50000.00"),
                currentBalance = BigDecimal("12450.00"),
                category = cat.uppercase(),
            ),
        )
    }

    fun getPriceList(): List<PriceListItem> {
        return listOf(
            PriceListItem(
                productCode = "P-1001",
                productName = "Premium Blend",
                wholesalePrice = BigDecimal("18.50"),
                retailPrice = BigDecimal("24.99"),
                currency = "USD",
            ),
            PriceListItem(
                productCode = "RM-2001",
                productName = "Cocoa Powder",
                wholesalePrice = BigDecimal("8.00"),
                retailPrice = BigDecimal("12.00"),
                currency = "USD",
            ),
        )
    }

    fun applyInstantDiscount(request: InstantDiscountRequest): InstantDiscountSummary {
        return InstantDiscountSummary(
            id = UUID.randomUUID(),
            invoiceNumber = request.invoiceNumber,
            discountAmount = request.discountAmount,
            approvedBy = "Sales Manager",
            timestamp = Instant.now(),
        )
    }

    fun addCustomerContact(request: CustomerContactRequest): CustomerContactSummary {
        return CustomerContactSummary(
            id = UUID.randomUUID(),
            customerName = request.customerName,
            email = request.email,
            phone = request.phone,
            address = request.address,
        )
    }

    fun requestWithdrawalOtp(request: OtpRequest): OtpSummary {
        return OtpSummary(
            otpId = UUID.randomUUID(),
            status = "ISSUED",
            expiresAt = Instant.now().plusSeconds(300), // 5 minutes
        )
    }

    fun getPendingSupplies(): List<PendingSupplyItem> {
        return listOf(
            PendingSupplyItem(
                id = UUID.randomUUID(),
                invoiceNumber = "INV-2026-089",
                customerName = "Metro Stores Ltd",
                productCode = "P-1001",
                productName = "Premium Blend",
                quantityPending = 200.0,
                paidAmount = BigDecimal("3700.00"),
                orderDate = Instant.now().minusSeconds(172800),
            ),
        )
    }
}
