package com.zifiq.ai.api

import com.zifiq.ai.dto.*
import com.zifiq.ai.service.AiOperationsService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/ai")
class AiOperationsController(
    private val aiOperationsService: AiOperationsService,
) : AiOperationsApi {
    @PostMapping("/assistant")
    override fun askAssistant(
        @Valid @RequestBody request: AssistantQueryRequest,
    ): ApiEnvelope<AssistantQueryResponse> =
        ApiEnvelope(aiOperationsService.askAssistant(request))

    @GetMapping("/reorder-forecasting")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun getReorderForecasts(): ApiEnvelope<List<ReorderForecastItem>> =
        ApiEnvelope(aiOperationsService.getReorderForecasts())

    @PostMapping("/recipe-optimizer")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.manage')")
    override fun optimizeRecipeCost(
        @Valid @RequestBody request: RecipeCostOptimizationRequest,
    ): ApiEnvelope<RecipeCostOptimizationResult> =
        ApiEnvelope(aiOperationsService.optimizeRecipeCost(request))

    @GetMapping("/cashflow-forecasting")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun getCashFlowForecast(): ApiEnvelope<CashFlowForecastResult> =
        ApiEnvelope(aiOperationsService.getCashFlowForecast())

    @GetMapping("/fraud-detection")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.roles.manage')")
    override fun detectAnomalies(): ApiEnvelope<List<AnomalyDetectionItem>> =
        ApiEnvelope(aiOperationsService.detectAnomalies())

    @PostMapping("/ocr-capture")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')")
    override fun processOcr(
        @Valid @RequestBody request: OcrCaptureRequest,
    ): ApiEnvelope<OcrCaptureResult> =
        ApiEnvelope(aiOperationsService.processOcr(request))

    @GetMapping("/executive-briefing")
    override fun getExecutiveBriefing(): ApiEnvelope<ExecutiveBriefing> =
        ApiEnvelope(aiOperationsService.getExecutiveBriefing())
}
