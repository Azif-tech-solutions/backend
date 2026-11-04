package com.zifiq.barcode.dto

import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.time.Instant

data class LabelGenerationRequest(
    @field:NotBlank val productCode: String,
    val count: Int = 1,
    val labelFormat: String = "STANDARD_QR", // EAN13, QR, CODE128
)

data class LabelPayloadSummary(
    val productCode: String,
    val productName: String,
    val formattedBarcodeData: String,
    val qrCodeSvg: String,
    val printableCount: Int,
)
