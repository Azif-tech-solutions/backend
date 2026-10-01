package com.zifiq.barcode.api

import com.zifiq.barcode.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Barcode & QR", description = "Barcode Scanning & Printable QR/Product Label Workflows")
@SecurityRequirement(name = "bearerAuth")
interface BarcodeQrApi {
    @Operation(summary = "Scan barcode or QR code and resolve product details")
    fun scanCode(request: BarcodeScanRequest): ApiEnvelope<BarcodeScanSummary>

    @Operation(summary = "Generate printable product barcode/QR label payload")
    fun generateLabels(request: LabelGenerationRequest): ApiEnvelope<LabelPayloadSummary>
}
