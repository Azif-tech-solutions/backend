package com.zifiq.history.service

import com.zifiq.history.dto.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class HistoryService {
    fun getWaybillHistory(): List<WaybillHistoryItem> {
        return listOf(
            WaybillHistoryItem(
                id = UUID.randomUUID(),
                waybillNumber = "WAY-884102",
                salesInvoiceNumber = "INV-2026-001",
                destination = "Lagos Showroom Central",
                driverName = "John Doe",
                issuedAt = Instant.now().minusSeconds(7200),
            ),
        )
    }

    fun getExpenseHistory(): List<ExpenseHistoryItem> {
        return listOf(
            ExpenseHistoryItem(
                id = UUID.randomUUID(),
                category = "Generator Fuel",
                amount = BigDecimal("350.00"),
                paymentMethod = "PETTY_CASH",
                approvedBy = "Store Manager",
                timestamp = Instant.now().minusSeconds(14400),
            ),
        )
    }

    fun getStockMovementHistory(): List<StockMovementHistoryItem> {
        return listOf(
            StockMovementHistoryItem(
                id = UUID.randomUUID(),
                productCode = "P-1001",
                productName = "Premium Blend",
                movementType = "TRANSFER",
                quantity = 50.0,
                sourceLocation = "FAC-001",
                targetLocation = "SHOW-001",
                timestamp = Instant.now().minusSeconds(10800),
            ),
        )
    }

    fun getFraudViolations(): List<FraudViolationItem> {
        return listOf(
            FraudViolationItem(
                id = UUID.randomUUID(),
                violationType = "AFTER_HOURS_PRICE_OVERRIDE",
                severity = "HIGH",
                description = "Manual price discount of 35% applied after standard operating hours",
                flaggedUser = "Cashier-03",
                timestamp = Instant.now().minusSeconds(43200),
            ),
        )
    }

    fun getWalletWithdrawals(): List<WalletWithdrawalHistoryItem> {
        return listOf(
            WalletWithdrawalHistoryItem(
                id = UUID.randomUUID(),
                accountHolder = "Dealer Alpha Outlets",
                amount = BigDecimal("2500.00"),
                channel = "BANK_TRANSFER",
                status = "COMPLETED",
                timestamp = Instant.now().minusSeconds(86400),
            ),
        )
    }
}
