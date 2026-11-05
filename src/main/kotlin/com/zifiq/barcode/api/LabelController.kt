package com.zifiq.barcode.api

import com.zifiq.barcode.dto.LabelItemRow
import com.zifiq.barcode.dto.LabelPreviewRequest
import com.zifiq.barcode.dto.LabelRunRequest
import com.zifiq.barcode.dto.LabelRunResult
import com.zifiq.barcode.dto.LabelRunRow
import com.zifiq.barcode.service.LabelService
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Barcode & QR — Labels", description = "Pick items, preview, print runs (PDF / ZPL) and history")
@SecurityRequirement(name = "bearerAuth")
interface LabelApi {
    @Operation(summary = "The label picker: items with (or without) a code")
    fun labelItems(hasCode: String?, kind: String?, status: String?, q: String?, page: Int?, size: Int?): ApiEnvelope<PageResult<LabelItemRow>>

    @Operation(summary = "A PNG preview of one label")
    fun preview(request: LabelPreviewRequest): ResponseEntity<ByteArray>

    @Operation(summary = "Create a label print run (generating any missing codes)")
    fun createRun(request: LabelRunRequest): ApiEnvelope<LabelRunResult>

    @Operation(summary = "Render a run as a label PDF")
    fun labelsPdf(id: UUID): ResponseEntity<ByteArray>

    @Operation(summary = "Render a run as Zebra ZPL")
    fun labelsZpl(id: UUID): ResponseEntity<ByteArray>

    @Operation(summary = "Print-run history")
    fun runs(page: Int?, size: Int?): ApiEnvelope<PageResult<LabelRunRow>>
}

@RestController
@RequestMapping("/api/v1/barcode/labels")
@PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.labels.print')")
class LabelController(
    private val labels: LabelService,
) : LabelApi {
    @GetMapping("/items")
    override fun labelItems(
        @RequestParam(required = false) hasCode: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<LabelItemRow>> = ApiEnvelope(labels.items(hasCode, kind, status, q, PageRequest.of(page, size)))

    @PostMapping("/preview", produces = [MediaType.IMAGE_PNG_VALUE])
    override fun preview(@Valid @RequestBody request: LabelPreviewRequest): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header(HttpHeaders.CACHE_CONTROL, "no-store").body(labels.preview(request))

    @PostMapping("/runs")
    override fun createRun(@Valid @RequestBody request: LabelRunRequest): ApiEnvelope<LabelRunResult> =
        ApiEnvelope(labels.createRun(request))

    @GetMapping("/runs/{id}/labels.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    override fun labelsPdf(@PathVariable id: UUID): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("labels-$id.pdf").build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(labels.renderPdf(id))

    @GetMapping("/runs/{id}/labels.zpl", produces = ["text/plain"])
    override fun labelsZpl(@PathVariable id: UUID): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("labels-$id.zpl").build().toString())
            .body(labels.renderZpl(id).toByteArray(Charsets.UTF_8))

    @GetMapping("/runs")
    override fun runs(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<LabelRunRow>> = ApiEnvelope(labels.history(PageRequest.of(page, size)))
}
