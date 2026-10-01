package com.zifiq.process.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.process.dto.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Process Guide & Admin", description = "Exchange Rates, Commissions, Error Logs, Recycle Bin & Sales Reps")
@SecurityRequirement(name = "bearerAuth")
interface ProcessGuideApi {
    @Operation(summary = "Get current exchange rates")
    fun getExchangeRates(): ApiEnvelope<List<ExchangeRateSummary>>

    @Operation(summary = "Update currency exchange rate")
    fun updateExchangeRate(request: ExchangeRateUpdate): ApiEnvelope<ExchangeRateSummary>

    @Operation(summary = "List commission rules and rates")
    fun getCommissionRules(): ApiEnvelope<List<CommissionRuleSummary>>

    @Operation(summary = "Create or update commission rule")
    fun createCommissionRule(request: CommissionRuleRequest): ApiEnvelope<CommissionRuleSummary>

    @Operation(summary = "Review system-flagged error log")
    fun getSystemErrors(): ApiEnvelope<List<SystemErrorLogItem>>

    @Operation(summary = "List soft-deleted records in recycle bin")
    fun getRecycleBinItems(): ApiEnvelope<List<RecycleBinItem>>

    @Operation(summary = "Register sales representative (correspondent)")
    fun registerCorrespondent(request: SalesCorrespondentRequest): ApiEnvelope<SalesCorrespondentSummary>
}
