package com.zifiq.invoicing.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.invoicing.dto.CreditNoteDetail
import com.zifiq.invoicing.dto.CreditNoteRequest
import com.zifiq.invoicing.dto.DeliveryRequest
import com.zifiq.invoicing.dto.DeliveryView
import com.zifiq.invoicing.dto.DraftHeaderRequest
import com.zifiq.invoicing.dto.InvoicePaymentView
import com.zifiq.invoicing.dto.NrsPreview
import com.zifiq.invoicing.dto.ReasonBody
import com.zifiq.invoicing.dto.RecordPaymentRequest
import com.zifiq.invoicing.dto.RecordedPayment
import com.zifiq.invoicing.dto.InvoiceDetail
import com.zifiq.invoicing.dto.InvoiceLookupRow
import com.zifiq.invoicing.dto.InvoiceRow
import com.zifiq.invoicing.dto.SandboxInvoiceRequest
import com.zifiq.invoicing.dto.VoidInvoiceRequest
import com.zifiq.invoicing.dto.VoidedInvoiceRow
import com.zifiq.invoicing.service.CreditNoteService
import com.zifiq.invoicing.service.InvoiceDeliveryService
import com.zifiq.invoicing.service.InvoicePaymentService
import com.zifiq.invoicing.service.InvoiceQueryService
import com.zifiq.invoicing.service.NrsPreviewService
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
private const val PAYMENT = "@permissionAuthorizer.hasPermission(authentication, 'invoicing.payment.record')"
private const val CREDIT = "@permissionAuthorizer.hasPermission(authentication, 'invoicing.credit-note.issue')"
private const val DELIVER = "@permissionAuthorizer.hasPermission(authentication, 'invoicing.delivery.manage')"

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

    @Operation(summary = "Record a payment: an Account receipt (cash posts now; POS, transfer and cheque wait for confirmation); never more than the balance")
    fun recordPayment(id: UUID, request: RecordPaymentRequest): ApiEnvelope<RecordedPayment>

    @Operation(summary = "The invoice's payments with their receipt status")
    fun payments(id: UUID): ApiEnvelope<List<InvoicePaymentView>>

    @Operation(summary = "Reverse a payment recorded here by mistake: voids its receipt and credits back a wallet/deposit debit")
    fun reversePayment(id: UUID, paymentId: UUID, request: ReasonBody): ApiEnvelope<InvoiceDetail>

    @Operation(summary = "Issue a credit note against lines (quantity or value); VAT follows the original line; excess over the balance goes to the wallet")
    fun issueCreditNote(id: UUID, request: CreditNoteRequest): ApiEnvelope<CreditNoteDetail>

    @Operation(summary = "E-mail the invoice (STANDARD, REMINDER) or the customer's statement (STATEMENT); a different recipient is a redirect")
    fun deliver(id: UUID, request: DeliveryRequest?): ApiEnvelope<DeliveryView>

    @Operation(summary = "The invoice's e-mail deliveries, newest first")
    fun deliveries(id: UUID): ApiEnvelope<List<DeliveryView>>

    @Operation(summary = "The NRS Invoice Schema JSON this invoice would be submitted as, and what is missing (nothing is transmitted)")
    fun nrsPreview(id: UUID): ApiEnvelope<NrsPreview>

    @Operation(summary = "ZIP of the invoice PDFs the list filters select (same scope as opening them one by one; at most invoicing.bulkExportMaxInvoices)")
    fun export(query: MultiValueMap<String, String>): ResponseEntity<ByteArray>
}

@RestController
@RequestMapping("/api/v1/invoicing/invoices")
class InvoicesController(
    private val invoices: InvoiceService,
    private val queries: InvoiceQueryService,
    private val payments: InvoicePaymentService,
    private val creditNotes: CreditNoteService,
    private val deliveryService: InvoiceDeliveryService,
    private val nrs: NrsPreviewService,
) : InvoicesApi {
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

    @PostMapping("/{id}/payments")
    @PreAuthorize(PAYMENT)
    override fun recordPayment(@PathVariable id: UUID, @Valid @RequestBody request: RecordPaymentRequest): ApiEnvelope<RecordedPayment> {
        val recorded = payments.record(id, request)
        return ApiEnvelope(RecordedPayment(recorded.paymentId, recorded.receiptId, recorded.receiptRef, recorded.confirmationStatus, queries.detail(id)))
    }

    @GetMapping("/{id}/payments")
    @PreAuthorize(READ)
    override fun payments(@PathVariable id: UUID) = ApiEnvelope(queries.detail(id).linked.payments)

    @PostMapping("/{id}/payments/{paymentId}/reverse")
    @PreAuthorize(VOID)
    override fun reversePayment(@PathVariable id: UUID, @PathVariable paymentId: UUID, @Valid @RequestBody request: ReasonBody) =
        ApiEnvelope(queries.detail(payments.reverse(id, paymentId, request.reason)))

    @PostMapping("/{id}/credit-notes")
    @PreAuthorize(CREDIT)
    override fun issueCreditNote(@PathVariable id: UUID, @Valid @RequestBody request: CreditNoteRequest) =
        ApiEnvelope(creditNotes.get(creditNotes.issue(id, request)))

    @PostMapping("/{id}/deliveries")
    @PreAuthorize(DELIVER)
    override fun deliver(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: DeliveryRequest?) =
        ApiEnvelope(deliveryService.send(id, request ?: DeliveryRequest()))

    @GetMapping("/{id}/deliveries")
    @PreAuthorize(READ)
    override fun deliveries(@PathVariable id: UUID) = ApiEnvelope(deliveryService.history(id))

    @GetMapping("/{id}/nrs-preview")
    @PreAuthorize(READ)
    override fun nrsPreview(@PathVariable id: UUID) = ApiEnvelope(nrs.preview(id))

    @GetMapping("/export.zip", produces = ["application/zip"])
    @PreAuthorize(DELIVER)
    override fun export(@RequestParam query: MultiValueMap<String, String>): ResponseEntity<ByteArray> {
        val (fileName, zip) = queries.exportZip(ListSearchRequest.fromQuery(query))
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(zip)
    }
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
