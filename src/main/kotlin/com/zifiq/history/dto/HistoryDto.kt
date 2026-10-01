package com.zifiq.history.dto

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class WaybillHistoryItem(
    val id: UUID,
    val waybillNumber: String,
    val salesInvoiceNumber: String,
    val destination: String,
    val driverName: String,
    val issuedAt: Instant,
)

data class ExpenseHistoryItem(
    val id: UUID,
    val category: String,
    val amount: BigDecimal,
    val paymentMethod: String,
    val approvedBy: String,
    val timestamp: Instant,
)

data class StockMovementHistoryItem(
    val id: UUID,
    val productCode: String,
    val productName: String,
    val movementType: String, // IN, OUT, TRANSFER, ADJUSTMENT
    val quantity: Double,
    val sourceLocation: String,
    val targetLocation: String,
    val timestamp: Instant,
)

data class FraudViolationItem(
    val id: UUID,
    val violationType: String,
    val severity: String, // LOW, MEDIUM, HIGH, CRITICAL
    val description: String,
    val flaggedUser: String?,
    val timestamp: Instant,
)

data class WalletWithdrawalHistoryItem(
    val id: UUID,
    val accountHolder: String,
    val amount: BigDecimal,
    val channel: String,
    val status: String,
    val timestamp: Instant,
)
