package com.zifiq.barcode.api

import com.zifiq.barcode.dto.BatchScanRequest
import com.zifiq.barcode.dto.ScanRequest
import com.zifiq.barcode.dto.ScanResolution
import com.zifiq.barcode.service.ScanResolutionService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Barcode & QR — Scan", description = "Resolve a scanned code to a stock item in the scanning screen's context")
@SecurityRequirement(name = "bearerAuth")
interface ScanApi {
    @Operation(summary = "Resolve one scanned code (the permission depends on the scan context)")
    fun scan(request: ScanRequest): ApiEnvelope<ScanResolution>

    @Operation(summary = "Resolve many codes at once (stocktake / offline queue)")
    fun scanBatch(request: BatchScanRequest): ApiEnvelope<List<ScanResolution>>
}

@RestController
@RequestMapping("/api/v1/barcode")
class ScanController(
    private val scans: ScanResolutionService,
) : ScanApi {
    // No static @PreAuthorize: the required permission is the scanning context's (docs/11 §3.2),
    // enforced in the service, which returns 403 when the caller may not open that manual screen.
    @PostMapping("/scan")
    override fun scan(@Valid @RequestBody request: ScanRequest): ApiEnvelope<ScanResolution> =
        ApiEnvelope(scans.resolve(request))

    @PostMapping("/scan/batch")
    override fun scanBatch(@Valid @RequestBody request: BatchScanRequest): ApiEnvelope<List<ScanResolution>> =
        ApiEnvelope(scans.batch(request))
}
