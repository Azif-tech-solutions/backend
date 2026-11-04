package com.zifiq.barcode.api

import com.zifiq.barcode.dto.ScanLogRow
import com.zifiq.barcode.dto.UnrecognisedCodeRow
import com.zifiq.barcode.service.ScanLogService
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Barcode & QR — Scan log", description = "Recent scans across every scan-enabled screen, with stats")
@SecurityRequirement(name = "bearerAuth")
interface ScanLogApi {
    @Operation(summary = "List scans (a user without barcode.scan-log.read sees only their own)")
    fun scanEvents(
        outcome: String?, context: String?, userId: UUID?, locationId: UUID?, stockItemId: UUID?, code: String?,
        from: LocalDate?, to: LocalDate?, mine: Boolean, page: Int?, size: Int?,
    ): ApiEnvelope<PageResult<ScanLogRow>>

    @Operation(summary = "Repeatedly-unrecognised codes, grouped — candidates to register")
    fun unrecognised(from: LocalDate?, to: LocalDate?): ApiEnvelope<List<UnrecognisedCodeRow>>
}

@RestController
@RequestMapping("/api/v1/barcode/scan-events")
class ScanLogController(
    private val log: ScanLogService,
) : ScanLogApi {
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.lookup')")
    override fun scanEvents(
        @RequestParam(required = false) outcome: String?,
        @RequestParam(required = false) context: String?,
        @RequestParam(required = false) userId: UUID?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) stockItemId: UUID?,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false, defaultValue = "false") mine: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<ScanLogRow>> = ApiEnvelope(
        log.list(
            ScanLogService.Filter(outcome, context, userId, locationId, stockItemId, code, from, to, mine),
            PageRequest.of(page, size),
        ),
    )

    @GetMapping("/unrecognised")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.scan-log.read')")
    override fun unrecognised(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ApiEnvelope<List<UnrecognisedCodeRow>> = ApiEnvelope(log.unrecognised(from, to))
}
