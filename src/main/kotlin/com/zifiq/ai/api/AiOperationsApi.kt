package com.zifiq.ai.api

import com.zifiq.ai.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "AI Features", description = "ZifIQ Assistant, Smart Reorder Forecasting, Recipe Cost Optimizer, Cashflow & Demand Forecasting, Anomaly Detection & OCR")
@SecurityRequirement(name = "bearerAuth")
interface AiOperationsApi {
    @Operation(summary = "Ask ZifIQ Assistant operational questions")
    fun askAssistant(request: AssistantQueryRequest): ApiEnvelope<AssistantQueryResponse>

    @Operation(summary = "Smart trend-aware reorder date and quantity forecasts")
    fun getReorderForecasts(): ApiEnvelope<List<ReorderForecastItem>>

    @Operation(summary = "Optimize recipe material costs while maintaining specification bounds")
    fun optimizeRecipeCost(request: RecipeCostOptimizationRequest): ApiEnvelope<RecipeCostOptimizationResult>

    @Operation(summary = "Projected inflows, outflows, and shortfall risk detection")
    fun getCashFlowForecast(): ApiEnvelope<CashFlowForecastResult>

    @Operation(summary = "AI flagged operational irregularities and anomalies for review")
    fun detectAnomalies(): ApiEnvelope<List<AnomalyDetectionItem>>

    @Operation(summary = "Extract data from scanned invoices, receipts, and waybills via OCR")
    fun processOcr(request: OcrCaptureRequest): ApiEnvelope<OcrCaptureResult>

    @Operation(summary = "Automated management executive briefing and insights")
    fun getExecutiveBriefing(): ApiEnvelope<ExecutiveBriefing>
}
