package com.zifiq.process.service

import com.zifiq.process.dto.*
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class ProcessGuideService {
    fun getExchangeRates(): List<ExchangeRateSummary> {
        return listOf(
            ExchangeRateSummary("USD", "EUR", BigDecimal("0.92"), Instant.now()),
            ExchangeRateSummary("USD", "GBP", BigDecimal("0.78"), Instant.now()),
            ExchangeRateSummary("USD", "NGN", BigDecimal("1600.00"), Instant.now()),
        )
    }

    fun updateExchangeRate(request: ExchangeRateUpdate): ExchangeRateSummary {
        return ExchangeRateSummary(
            baseCurrency = request.baseCurrency,
            targetCurrency = request.targetCurrency,
            rate = request.rate,
            updatedAt = Instant.now(),
        )
    }

    fun getCommissionRules(): List<CommissionRuleSummary> {
        return listOf(
            CommissionRuleSummary(UUID.randomUUID(), "Dealer", BigDecimal("5.0"), "Standard dealer sales commission"),
            CommissionRuleSummary(UUID.randomUUID(), "Sales Rep", BigDecimal("2.5"), "Field rep performance bonus"),
        )
    }

    fun createCommissionRule(request: CommissionRuleRequest): CommissionRuleSummary {
        return CommissionRuleSummary(
            id = UUID.randomUUID(),
            role = request.role,
            percentageRate = request.percentageRate,
            description = request.description,
        )
    }

    fun getSystemErrors(): List<SystemErrorLogItem> {
        return listOf(
            SystemErrorLogItem(
                id = UUID.randomUUID(),
                errorCode = "PAYMENT_GATEWAY_TIMEOUT",
                message = "POS payment gateway timed out on transaction REC-8941",
                module = "cashier",
                status = "RESOLVED",
                timestamp = Instant.now().minusSeconds(3600),
            ),
        )
    }

    fun getRecycleBinItems(): List<RecycleBinItem> {
        return listOf(
            RecycleBinItem(
                id = UUID.randomUUID(),
                entityType = "CustomerRecord",
                entityId = "CUST-901",
                deletedBy = "Admin",
                deletedAt = Instant.now().minusSeconds(86400),
            ),
        )
    }

    fun registerCorrespondent(request: SalesCorrespondentRequest): SalesCorrespondentSummary {
        return SalesCorrespondentSummary(
            id = UUID.randomUUID(),
            name = request.name,
            email = request.email,
            phone = request.phone,
            assignedRegion = request.assignedRegion,
        )
    }
}
