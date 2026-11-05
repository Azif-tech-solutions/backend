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
import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@Tag(name = "Barcode & QR — Scan", description = "Resolve a scanned code to a stock item in the scanning screen's context")
@SecurityRequirement(name = "bearerAuth")
interface ScanApi {
    @Operation(summary = "Resolve one scanned code (the permission depends on the scan context)")
    fun scan(request: ScanRequest): ApiEnvelope<ScanResolution>

    @Operation(summary = "Resolve many codes at once (stocktake / offline queue)")
    fun scanBatch(request: BatchScanRequest): ApiEnvelope<List<ScanResolution>>

    @Operation(summary = "Decode an uploaded image and resolve the code(s) it contains")
    fun decode(image: MultipartFile, context: String, locationId: UUID?, customerId: UUID?): ApiEnvelope<List<ScanResolution>>
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

    // Decoding is a lookup (gated here); the resolution still enforces the scanning context's permission.
    @PostMapping("/decode", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.lookup')")
    override fun decode(
        @RequestPart("image") image: MultipartFile,
        @RequestParam context: String,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) customerId: UUID?,
    ): ApiEnvelope<List<ScanResolution>> {
        if (image.size > 5L * 1024 * 1024) throw com.zifiq.common.errors.ApiErrors.imageTooLarge()
        image.contentType?.let { if (!it.startsWith("image/")) throw com.zifiq.common.errors.ApiErrors.unsupportedImageType() }
        return ApiEnvelope(scans.resolveImage(context, image.bytes, locationId, customerId))
    }
}
