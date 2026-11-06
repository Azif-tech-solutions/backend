package com.zifiq.reports.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.reports.dto.CatalogReportEntry
import com.zifiq.reports.dto.OutsourceStockReport
import com.zifiq.reports.dto.ReportCatalog
import com.zifiq.reports.dto.SalesAnalyticsResult
import com.zifiq.reports.dto.ScheduleRequest
import com.zifiq.reports.dto.ScheduleView
import com.zifiq.reports.dto.SciResult
import com.zifiq.reports.dto.StockAnalyticsResult
import com.zifiq.reports.service.ExportService
import com.zifiq.reports.service.OutsourceStockService
import com.zifiq.reports.service.ReportCatalogueService
import com.zifiq.reports.service.SalesAnalyticsService
import com.zifiq.reports.service.ScheduleService
import com.zifiq.reports.service.SciService
import com.zifiq.reports.service.StockAnalyticsService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
    @Operation(summary = "Export a report (CSV/XLSX/PDF); streams the file when produced inline, else 202 with a ref")
    @PostMapping
    fun export(
        @RequestBody request: ExportRequest,
        @RequestParam(required = false, defaultValue = "true") wait: Boolean,
    ): ResponseEntity<*> = when (val outcome = exports.export(request.reportKey, request.params, request.format, wait)) {
        is ExportService.Outcome.Ready -> fileResponse(outcome.file)
        is ExportService.Outcome.Queued -> ResponseEntity.accepted().body(mapOf("ref" to outcome.ref, "status" to "QUEUED"))
    }

    @Operation(summary = "Download a finished export by ref (the requester, or an Administrator)")
    @GetMapping("/{ref}/file")
    fun file(@PathVariable ref: String): ResponseEntity<ByteArray> = fileResponse(exports.download(ref))

    private fun fileResponse(file: ExportService.ExportFile): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName).build().toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(file.bytes)

    @Operation(summary = "The caller's exports")
    @GetMapping
    fun list(
        @RequestParam(required = false, defaultValue = "false") all: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<Map<String, Any?>>> = ApiEnvelope(exports.list(all, PageRequest.of(page, size)))
}

@Tag(name = "Reports — SCI", description = "Statement of Comprehensive Income, company-wide or per location, with a comparison period (docs/13 §4.2)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/reports/sci")
class SciController(
    private val sci: SciService,
) {
    @Operation(summary = "The SCI for a period; defaults to the current month, prior-period comparison and company scope")
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'reports.sci.read')")
    fun sci(
        @RequestParam(required = false, defaultValue = "COMPANY") scope: String,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false, defaultValue = "PRIOR_PERIOD") compare: String,
    ): ApiEnvelope<SciResult> {
        val today = LocalDate.now()
        val start = from ?: today.withDayOfMonth(1)
        val end = to ?: today
        return ApiEnvelope(sci.sci(scope, locationId, start, end, compare))
    }
}

@Tag(name = "Reports — Out-Source Stock", description = "Sell-through of outsourced consignments, FIFO-attributed per showroom (docs/13 §4.3)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/reports/outsource-stock")
class OutsourceStockController(
    private val outsource: OutsourceStockService,
) {
    @Operation(summary = "Outsourced consignments with lifetime sell-through; filters are on the receipt date")
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'reports.outsource.read')")
    fun report(
        @RequestParam(required = false) manufacturerId: UUID?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ApiEnvelope<OutsourceStockReport> = ApiEnvelope(outsource.report(manufacturerId, locationId, status, from, to))
}

@Tag(name = "Reports — Sales analytics", description = "Grouped sales with quantity, revenue, discounts, VAT and margin (docs/13 §4.4)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/reports/sales/analytics")
class SalesAnalyticsController(
    private val analytics: SalesAnalyticsService,
) {
    @Operation(summary = "Sales aggregated by one or two dimensions and an optional interval, defaulting to the current month")
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'reports.sales.read')")
    fun analytics(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam groupBy: List<String>,
        @RequestParam(required = false, defaultValue = "NONE") interval: String,
        @RequestParam(required = false) locationIds: Set<UUID>?,
        @RequestParam(required = false) customerTypes: Set<String>?,
        @RequestParam(required = false, defaultValue = "20") top: Int,
        @RequestParam(required = false, defaultValue = "NONE") compare: String,
    ): ApiEnvelope<SalesAnalyticsResult> {
        val today = LocalDate.now()
        return ApiEnvelope(analytics.analytics(from ?: today.withDayOfMonth(1), to ?: today, groupBy, interval, locationIds, customerTypes, top, compare))
    }
}

@Tag(name = "Reports — Stock analytics", description = "Stock value, slow-moving/dead stock and days of cover (docs/13 §4.5)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/reports/stock/analytics")
class StockAnalyticsController(
    private val stock: StockAnalyticsService,
) {
    @Operation(summary = "A stock-analytics view; VALUE | SLOW_MOVING | DAYS_OF_COVER, as at 'asOf' (default today)")
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'reports.stock.read')")
    fun analytics(
        @RequestParam(required = false, defaultValue = "VALUE") view: String,
        @RequestParam(required = false) groupBy: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?,
        @RequestParam(required = false) locationIds: Set<UUID>?,
    ): ApiEnvelope<StockAnalyticsResult> = ApiEnvelope(stock.analytics(view, asOf ?: LocalDate.now(), groupBy, locationIds))
}

@Tag(name = "Reports — Schedules", description = "Scheduled reports produced on a cadence as the owner and delivered to permitted recipients (docs/13 §4.7)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/reports/schedules")
class ScheduleController(
    private val schedules: ScheduleService,
) {
    private companion object {
        const val MANAGE = "@permissionAuthorizer.hasPermission(authentication, 'reports.schedules.manage')"
    }

    @Operation(summary = "Create a scheduled report")
    @PostMapping
    @PreAuthorize(MANAGE)
    fun create(@Valid @RequestBody request: ScheduleRequest): ApiEnvelope<ScheduleView> = ApiEnvelope(schedules.create(request))

    @Operation(summary = "The tenant's scheduled reports")
    @GetMapping
    @PreAuthorize(MANAGE)
    fun list(): ApiEnvelope<List<ScheduleView>> = ApiEnvelope(schedules.list())

    @Operation(summary = "One scheduled report")
    @GetMapping("/{id}")
    @PreAuthorize(MANAGE)
    fun get(@PathVariable id: UUID): ApiEnvelope<ScheduleView> = ApiEnvelope(schedules.get(id))

    @Operation(summary = "Update a scheduled report")
    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: ScheduleRequest): ApiEnvelope<ScheduleView> = ApiEnvelope(schedules.update(id, request))

    @Operation(summary = "Pause a scheduled report")
    @PostMapping("/{id}/pause")
    @PreAuthorize(MANAGE)
    fun pause(@PathVariable id: UUID): ApiEnvelope<ScheduleView> = ApiEnvelope(schedules.pause(id))

    @Operation(summary = "Resume a scheduled report")
    @PostMapping("/{id}/resume")
    @PreAuthorize(MANAGE)
    fun resume(@PathVariable id: UUID): ApiEnvelope<ScheduleView> = ApiEnvelope(schedules.resume(id))

    @Operation(summary = "Run a scheduled report now (queues an export, produced in the background)")
    @PostMapping("/{id}/run-now")
    @PreAuthorize(MANAGE)
    fun runNow(@PathVariable id: UUID): ApiEnvelope<Map<String, String>> = ApiEnvelope(mapOf("ref" to schedules.runNow(id), "status" to "QUEUED"))
}
