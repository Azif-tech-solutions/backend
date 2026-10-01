package com.zifiq.account.service

import com.zifiq.account.dto.*
import com.zifiq.platform.security.CurrentActorProvider
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class AccountService(
    private val currentActorProvider: CurrentActorProvider,
) {
    fun getCashFlow(): List<CashFlowEntry> {
        return listOf(
            CashFlowEntry(
                id = UUID.randomUUID(),
                type = "INFLOW",
                category = "Showroom Sales",
                amount = BigDecimal("12500.00"),
                currency = "USD",
                description = "Daily showroom sales deposit",
                timestamp = Instant.now(),
            ),
            CashFlowEntry(
                id = UUID.randomUUID(),
                type = "OUTFLOW",
                category = "Raw Material Purchase",
                amount = BigDecimal("4500.00"),
                currency = "USD",
                description = "Supplier payment for cocoa beans",
                timestamp = Instant.now(),
            ),
        )
    }

    fun getBankStatement(accountNumber: String): BankAccountStatement {
        return BankAccountStatement(
            accountId = UUID.randomUUID(),
            bankName = "First National Bank",
            accountNumber = accountNumber,
            currency = "USD",
            currentBalance = BigDecimal("154200.00"),
            transactions = listOf(
                BankTransactionSummary(
                    id = UUID.randomUUID(),
                    reference = "TXN-98421",
                    amount = BigDecimal("12500.00"),
                    transactionType = "CREDIT",
                    timestamp = Instant.now(),
                ),
            ),
        )
    }

    fun getPettyCashStatement(): PettyCashStatement {
        return PettyCashStatement(
            accountId = UUID.randomUUID(),
            availableBalance = BigDecimal("1250.00"),
            floatLimit = BigDecimal("5000.00"),
            currency = "USD",
        )
    }

    fun recordOperatingCost(request: OperatingCostPaymentRequest): PaymentSummary {
        return PaymentSummary(
            id = UUID.randomUUID(),
            category = request.category,
            amount = request.amount,
            status = "CONFIRMED",
            referenceCode = "PAY-" + UUID.randomUUID().toString().take(8).uppercase(),
        )
    }

    fun registerBankAccount(request: BankAccountRegistrationRequest): BankAccountSummary {
        return BankAccountSummary(
            id = UUID.randomUUID(),
            bankName = request.bankName,
            accountNumber = request.accountNumber,
            accountName = request.accountName,
            currency = request.currency,
        )
    }

    fun topUpPettyCash(request: PettyCashTopUpRequest): PaymentSummary {
        return PaymentSummary(
            id = UUID.randomUUID(),
            category = "Petty Cash Top-up",
            amount = request.amount,
            status = "TOPPED_UP",
            referenceCode = "PC-" + UUID.randomUUID().toString().take(8).uppercase(),
        )
    }
}
