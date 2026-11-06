package com.zifiq.reports.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.reports.dto.CatalogReportEntry
import com.zifiq.reports.dto.ReportCatalog
import com.zifiq.reports.service.ExportService
import com.zifiq.reports.service.ReportCatalogueService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Reports — Catalogue", description = "Every report the caller may open, grouped into the 14 streams (docs/13 §4.1)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/reports/catalog")
class ReportCatalogueController(
    private val catalogue: ReportCatalogueService,
) {
    @Operation(summary = "The report catalogue")
    @GetMapping
    fun catalog(@RequestParam(required = false) q: String?): ApiEnvelope<ReportCatalog> = ApiEnvelope(catalogue.catalog(q))

    @Operation(summary = "One catalogued report")
    @GetMapping("/{reportKey}")
    fun entry(@PathVariable reportKey: String): ApiEnvelope<CatalogReportEntry> = ApiEnvelope(catalogue.entry(reportKey))
}

data class ExportRequest(
    @field:NotBlank val reportKey: String,
    val params: Map<String, Any?> = emptyMap(),
    val format: String = "XLSX",
)

@Tag(name = "Reports — Exports", description = "Export a catalogued report to CSV / XLSX (docs/13 §4.6)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/reports/exports")
class ReportExportController(
    private val exports: ExportService,
) {
    @Operation(summary = "Export a report (synchronous CSV/XLSX)")
    @PostMapping
    fun export(@RequestBody request: ExportRequest): ResponseEntity<ByteArray> {
        val file = exports.exportSync(request.reportKey, request.params, request.format)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(file.bytes)
    }

    @Operation(summary = "The caller's exports")
    @GetMapping
    fun list(
        @RequestParam(required = false, defaultValue = "false") all: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<Map<String, Any?>>> = ApiEnvelope(exports.list(all, PageRequest.of(page, size)))
}
