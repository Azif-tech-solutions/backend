package com.zifiq.ai.dto

import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AssistantQueryRequest(
    @field:NotBlank val query: String,
    val contextModule: String?,
)

data class AssistantQueryResponse(
    val answer: String,
    val confidenceScore: Double,
    val suggestedActions: List<String>,
    val timestamp: Instant,
)

data class ReorderForecastItem(
    val productCode: String,
    val productName: String,
    val currentStock: Double,
    val predictedDepletionDate: Instant,
    val recommendedReorderQuantity: Double,
    val urgencyLevel: String, // CRITICAL, WARNING, NORMAL
)

data class RecipeCostOptimizationRequest(
    @field:NotBlank val recipeId: String,
    val targetSavingsPercent: Double = 10.0,
)

data class RecipeCostOptimizationResult(
    val recipeId: String,
    val currentCostPerUnit: BigDecimal,
    val optimizedCostPerUnit: BigDecimal,
    val projectedSavingsAmount: BigDecimal,
    val recommendedSubstitution: String,
)

data class CashFlowForecastResult(
    val projectedInflowNext30Days: BigDecimal,
    val projectedOutflowNext30Days: BigDecimal,
    val netProjectedBalance: BigDecimal,
    val predictedShortfallRisk: Boolean,
    val riskDetails: String?,
)

data class AnomalyDetectionItem(
    val id: UUID,
    val anomalyType: String,
    val affectedModule: String,
    val description: String,
    val anomalyScore: Double, // 0.0 to 1.0
    val detectedAt: Instant,
)

data class OcrCaptureRequest(
    @field:NotBlank val base64DocumentImage: String,
    val documentType: String = "INVOICE", // INVOICE, RECEIPT, WAYBILL
)

data class OcrCaptureResult(
    val detectedDocumentType: String,
    val vendorName: String?,
    val invoiceDate: String?,
    val totalAmount: BigDecimal?,
    val confidence: Double,
    val extractedLineItems: List<OcrLineItem>,
)

data class OcrLineItem(
    val description: String,
    val quantity: Double?,
    val unitPrice: BigDecimal?,
    val amount: BigDecimal?,
)

data class ExecutiveBriefing(
    val headlineSummary: String,
    val topSellingProduct: String,
    val highestCostCategory: String,
    val stockHealthScore: Double,
    val keyRecommendations: List<String>,
    val generatedAt: Instant,
)
