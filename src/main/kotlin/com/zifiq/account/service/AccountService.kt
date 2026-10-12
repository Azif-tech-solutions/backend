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
