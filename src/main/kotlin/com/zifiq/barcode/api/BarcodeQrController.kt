package com.zifiq.barcode.api

import com.zifiq.barcode.dto.*
import com.zifiq.barcode.service.BarcodeQrService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/barcode")
class BarcodeQrController(
    private val barcodeQrService: BarcodeQrService,
) : BarcodeQrApi {
    @PostMapping("/scan")
    override fun scanCode(
        @Valid @RequestBody request: BarcodeScanRequest,
    ): ApiEnvelope<BarcodeScanSummary> =
        ApiEnvelope(barcodeQrService.scanCode(request))

    @PostMapping("/labels")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun generateLabels(
        @Valid @RequestBody request: LabelGenerationRequest,
    ): ApiEnvelope<LabelPayloadSummary> =
        ApiEnvelope(barcodeQrService.generateLabels(request))
}
