package com.zifiq.invoicing.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.invoicing.dto.DraftHeaderRequest
import com.zifiq.invoicing.dto.InvoiceDetail
import com.zifiq.invoicing.dto.InvoiceLookupRow
import com.zifiq.invoicing.dto.InvoiceRow
import com.zifiq.invoicing.dto.SandboxInvoiceRequest
import com.zifiq.invoicing.dto.VoidInvoiceRequest
import com.zifiq.invoicing.dto.VoidedInvoiceRow
import com.zifiq.invoicing.service.InvoiceQueryService
import com.zifiq.invoicing.service.InvoiceService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

private const val READ = "@permissionAuthorizer.hasPermission(authentication, 'invoicing.invoice.read')"
private const val ISSUE = "@permissionAuthorizer.hasPermission(authentication, 'invoicing.invoice.issue')"
private const val VOID = "@permissionAuthorizer.hasPermission(authentication, 'invoicing.invoice.void')"

@Tag(name = "Invoicing — invoices", description = "Invoice history, detail, PDF, drafts, issue, void and void-and-reissue (docs/08 §4.1–4.4, §4.7, §4.9)")
@SecurityRequirement(name = "bearerAuth")
interface InvoicesApi {
    @Operation(summary = "Invoice history: status filters include the derived PARTIALLY_PAID, PAID and OVERDUE; header totals in summary")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<InvoiceRow>>

    @Operation(summary = "Invoice history with an advanced filter body")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<InvoiceRow>>

    @Operation(summary = "Filters the invoice list accepts")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Voided invoices with reason, who voided them and the replacement")
    fun voided(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<VoidedInvoiceRow>>

    @Operation(summary = "Invoice picker (number · customer · balance); openOnly keeps invoices with a balance")
    fun lookup(q: String?, openOnly: Boolean, customerId: UUID?): ApiEnvelope<List<InvoiceLookupRow>>

    @Operation(summary = "The invoice for a sale (TXN-…) or pro-forma (PF-…) reference")
    fun lookupSource(ref: String): ApiEnvelope<InvoiceRow>

    @Operation(summary = "One invoice with lines, totals, VAT, issuer and buyer snapshots and linked documents")
    fun get(id: UUID): ApiEnvelope<InvoiceDetail>

    @Operation(summary = "Tax invoice PDF (draft and void are stamped; DUPLICATE COPY once reprinted)")
    fun pdf(id: UUID): ResponseEntity<ByteArray>

    @Operation(summary = "Edit a draft's header: planned issue date, terms, due date, note, buyer e-mail (lines come from the source)")
    fun updateDraft(id: UUID, request: DraftHeaderRequest): ApiEnvelope<InvoiceDetail>

    @Operation(summary = "Discard a draft (the only invoice delete: a draft has no number)")
    fun discardDraft(id: UUID): ApiEnvelope<Map<String, Any>>

    @Operation(summary = "Issue a draft: re-validates, recomputes VAT at the issue date's rate and assigns the next INV number")
    fun issue(id: UUID, request: DraftHeaderRequest?): ApiEnvelope<InvoiceDetail>

    @Operation(summary = "Void with a reason (only when nothing is paid or credited), or void and reissue to a corrected buyer")
    fun void(id: UUID, request: VoidInvoiceRequest): ApiEnvelope<InvoiceDetail>
}

@RestController
@RequestMapping("/api/v1/invoicing/invoices")
class InvoicesController(private val invoices: InvoiceService, private val queries: InvoiceQueryService) : InvoicesApi {
    @GetMapping
    @PreAuthorize(READ)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(queries.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ)
    override fun search(@RequestBody request: ListSearchRequest) = ApiEnvelope(queries.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ)
    override fun filters() = ApiEnvelope(queries.filterMetadata())

    @GetMapping("/voided")
    @PreAuthorize(READ)
    override fun voided(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(queries.voided(ListSearchRequest.fromQuery(query)))

    @GetMapping("/lookup")
    @PreAuthorize(READ)
    override fun lookup(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "true") openOnly: Boolean,
        @RequestParam(required = false) customerId: UUID?,
    ) = ApiEnvelope(queries.lookup(q, openOnly, customerId))

    @GetMapping("/lookup-source")
    @PreAuthorize(READ)
    override fun lookupSource(@RequestParam ref: String) = ApiEnvelope(queries.lookupSource(ref))

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    override fun get(@PathVariable id: UUID) = ApiEnvelope(queries.detail(id))

    @GetMapping("/{id}/invoice.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize(READ)
    override fun pdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val printed = queries.pdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(printed.fileName).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(printed.pdf)
    }

    @PutMapping("/{id}")
    @PreAuthorize(ISSUE)
    override fun updateDraft(@PathVariable id: UUID, @Valid @RequestBody request: DraftHeaderRequest) =
        ApiEnvelope(queries.detail(invoices.updateDraft(id, request)))

    @DeleteMapping("/{id}")
    @PreAuthorize(ISSUE)
    override fun discardDraft(@PathVariable id: UUID): ApiEnvelope<Map<String, Any>> {
        invoices.discardDraft(id)
        return ApiEnvelope(mapOf("id" to id, "deleted" to true))
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(ISSUE)
    override fun issue(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: DraftHeaderRequest?) =
        ApiEnvelope(queries.detail(invoices.issueDraft(id, request ?: DraftHeaderRequest())))

    @PostMapping("/{id}/void")
    @PreAuthorize(VOID)
    override fun void(@PathVariable id: UUID, @Valid @RequestBody request: VoidInvoiceRequest) =
        ApiEnvelope(queries.detail(invoices.void(id, request.reason, request.reissue)))
}

@Tag(name = "Invoicing — sandbox", description = "Test support until Sales & POS (doc 09) exists: raise an invoice from a made-up sale or pro-forma")
@SecurityRequirement(name = "bearerAuth")
interface InvoiceSandboxApi {
    @Operation(summary = "Issue an invoice (or save a draft with asDraft=true) for a made-up source; sourceRef defaults to SBX-n")
    fun create(request: SandboxInvoiceRequest): ApiEnvelope<InvoiceDetail>
}

/** Off with `zifiq.invoicing.sandbox.enabled=false` (ZIFIQ_INVOICING_SANDBOX); removed when doc 09 ships. */
@RestController
@RequestMapping("/api/v1/invoicing/sandbox/invoices")
@ConditionalOnProperty(name = ["zifiq.invoicing.sandbox.enabled"], havingValue = "true")
class InvoiceSandboxController(private val invoices: InvoiceService, private val queries: InvoiceQueryService) : InvoiceSandboxApi {
    @PostMapping
    @PreAuthorize(ISSUE)
    override fun create(@Valid @RequestBody request: SandboxInvoiceRequest) = ApiEnvelope(queries.detail(invoices.sandbox(request)))
}
