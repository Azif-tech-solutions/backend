package com.zifiq.invoicing.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.invoicing.dto.CreditNoteDetail
import com.zifiq.invoicing.dto.CreditNoteRow
import com.zifiq.invoicing.dto.DebtorDetail
import com.zifiq.invoicing.dto.DebtorRow
import com.zifiq.invoicing.dto.DeliveryRow
import com.zifiq.invoicing.dto.ReceivableRow
import com.zifiq.invoicing.service.CreditNoteService
import com.zifiq.invoicing.service.InvoiceQueryService
import com.zifiq.invoicing.service.ReceivablesService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

private const val READ = "@permissionAuthorizer.hasPermission(authentication, 'invoicing.invoice.read')"
private const val RECEIVABLES = "@permissionAuthorizer.hasPermission(authentication, 'invoicing.receivables.read')"

@Tag(name = "Invoicing — credit notes", description = "Credit notes against issued invoices (docs/08 §4.5)")
@SecurityRequirement(name = "bearerAuth")
interface CreditNotesApi {
    @Operation(summary = "Credit notes; filters invoiceId, customerId, reason, locationId, issuedBy, total, from/to, hasExcessRefunded")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<CreditNoteRow>>

    @Operation(summary = "Credit notes with an advanced filter body")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<CreditNoteRow>>

    @Operation(summary = "Filters the credit-note list accepts")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "One credit note with its lines")
    fun get(id: UUID): ApiEnvelope<CreditNoteDetail>

    @Operation(summary = "Credit note PDF")
    fun pdf(id: UUID): ResponseEntity<ByteArray>
}

@RestController
@RequestMapping("/api/v1/invoicing/credit-notes")
class CreditNotesController(private val creditNotes: CreditNoteService) : CreditNotesApi {
    @GetMapping
    @PreAuthorize(READ)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(creditNotes.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ)
    override fun search(@RequestBody request: ListSearchRequest) = ApiEnvelope(creditNotes.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ)
    override fun filters() = ApiEnvelope(creditNotes.filterMetadata())

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    override fun get(@PathVariable id: UUID) = ApiEnvelope(creditNotes.get(id))

    @GetMapping("/{id}/credit-note.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize(READ)
    override fun pdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val printed = creditNotes.pdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(printed.fileName).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(printed.pdf)
    }
}

@Tag(name = "Invoicing — deliveries", description = "Invoice e-mail delivery status (docs/08 §4.8)")
@SecurityRequirement(name = "bearerAuth")
interface DeliveriesApi {
    @Operation(summary = "Issued invoices with their e-mail state; delivery = SENT, FAILED, NO_EMAIL or NOT_SENT; header with the delivered share")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<DeliveryRow>>

    @Operation(summary = "Deliveries with an advanced filter body")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<DeliveryRow>>
}

@RestController
@RequestMapping("/api/v1/invoicing/deliveries")
class DeliveriesController(private val queries: InvoiceQueryService) : DeliveriesApi {
    @GetMapping
    @PreAuthorize(READ)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(queries.deliveries(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ)
    override fun search(@RequestBody request: ListSearchRequest) = ApiEnvelope(queries.deliveries(request))
}

@Tag(name = "Invoicing — receivables and debtors", description = "Outstanding receivables and the Debtors List (docs/08 §4.9–4.10)")
@SecurityRequirement(name = "bearerAuth")
interface ReceivablesApi {
    @Operation(summary = "Open invoices (issued, balance above the paid tolerance), most overdue first; ageingBucket, overdueOnly and the invoice filters; header reconciles with the rows")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ReceivableRow>>

    @Operation(summary = "Receivables with an advanced filter body")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<ReceivableRow>>

    @Operation(summary = "Filters the receivables list accepts")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Debtors List: one row per customer with an open balance; locationId, customerType, ageing, correspondentId, overLimitOnly, outstanding.gte/lte, q")
    fun debtors(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<DebtorRow>>

    @Operation(summary = "One debtor's account and invoice history; carries a warning when over the credit limit")
    fun debtor(customerId: UUID, from: LocalDate?, to: LocalDate?, openOnly: Boolean): ApiEnvelope<DebtorDetail>
}

@RestController
@RequestMapping("/api/v1/invoicing")
class ReceivablesController(private val receivables: ReceivablesService) : ReceivablesApi {
    @GetMapping("/receivables")
    @PreAuthorize(RECEIVABLES)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(receivables.receivables(ListSearchRequest.fromQuery(query)))

    @PostMapping("/receivables/search")
    @PreAuthorize(RECEIVABLES)
    override fun search(@RequestBody request: ListSearchRequest) = ApiEnvelope(receivables.receivables(request))

    @GetMapping("/receivables/filters")
    @PreAuthorize(RECEIVABLES)
    override fun filters() = ApiEnvelope(receivables.filterMetadata())

    @GetMapping("/debtors")
    @PreAuthorize(RECEIVABLES)
    override fun debtors(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(receivables.debtors(ListSearchRequest.fromQuery(query)))

    @GetMapping("/debtors/{customerId}")
    @PreAuthorize(RECEIVABLES)
    override fun debtor(
        @PathVariable customerId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "false") openOnly: Boolean,
    ) = ApiEnvelope(receivables.debtor(customerId, from, to, openOnly))
}
