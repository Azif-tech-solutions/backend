package com.zifiq.platform.documents

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class IssuedDocumentRow(
    val id: UUID,
    val ref: String,
    val docType: DocumentType,
    val sourceModule: String,
    val sourceId: UUID,
    val location: Ref?,
    val partyName: String?,
    val partyCustomer: Ref?,
    /** Selling value, not a cost, so never masked; null for documents without one (transfer waybills). */
    val valueAmount: BigDecimal?,
    val issuedBy: UserRef,
    val issuedAt: Instant,
    val status: String,
    val reprintCount: Int,
    val pdfUrl: String?,
)

data class ReprintRequest(@field:Size(max = 200) val reason: String? = null)

data class ReprintResult(val ref: String, val reprintCount: Int, val pdfUrl: String?)

/**
 * Transaction Receipts (docs/03 §8.2). A caller sees only the document types they may read; a
 * single-location user sees only documents issued at their location (or its storage areas).
 */
@Service
class DocumentService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
    private val registry: DocumentRegistry,
    private val linkProviders: List<DocumentLinkProvider>,
) {
    private val joined = IssuedDocumentsTable
        .join(LocationsTable, JoinType.LEFT, additionalConstraint = { RawOp { +IssuedDocumentsTable.locationId; +" = "; +LocationsTable.id } })
        .join(AppUsersTable, JoinType.LEFT, additionalConstraint = { RawOp { +IssuedDocumentsTable.issuedByUserId; +" = "; +AppUsersTable.id } })

    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("type", "Document type", FilterType.ENUM, IssuedDocumentsTable.docType,
                DocumentType.entries.map { FilterOption(it.name, it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)) },
                aliases = setOf("doctype"), facetable = true),
            FilterField("status", "Status", FilterType.ENUM, IssuedDocumentsTable.status, listOf(FilterOption("ISSUED", "Issued"), FilterOption("VOIDED", "Voided")), facetable = true),
            FilterField("locationId", "Issued at", FilterType.LOCATION, IssuedDocumentsTable.locationId, aliases = setOf("location")),
            FilterField("sourceModule", "Module", FilterType.ENUM, IssuedDocumentsTable.sourceModule),
            FilterField("partyCustomerId", "Customer", FilterType.UUID, IssuedDocumentsTable.partyCustomerId, aliases = setOf("customer"), lookup = "/api/v1/customers/lookup"),
            FilterField("valueAmount", "Value", FilterType.MONEY, IssuedDocumentsTable.valueAmount, sortable = true),
            FilterField("reprinted", "Reprinted", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +IssuedDocumentsTable.reprintCount; +" > 0)" })),
            FilterField("issuedBy", "Issued by", FilterType.UUID, IssuedDocumentsTable.issuedByUserId),
            FilterField("issuedAt", "Issued", FilterType.DATETIME, IssuedDocumentsTable.issuedAt, sortable = true),
            FilterField("ref", "Reference", FilterType.REF, IssuedDocumentsTable.ref, sortable = true),
        ),
        primaryDateKey = "issuedAt",
        defaultSort = listOf("issuedAt" to SortOrder.DESC),
        tiebreaker = IssuedDocumentsTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(IssuedDocumentsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(IssuedDocumentsTable.partyName, SearchKind.TRIGRAM, "Party"),
            SearchField(IssuedDocumentsTable.valueAmount, SearchKind.AMOUNT, "Value"),
        ),
        qualifiers = filterSpec,
    )

    /** docs/03 §8.2: the permission that lets a caller see each document type. */
    private val readPermissions = mapOf(
        DocumentType.INVOICE to listOf("invoicing.invoice.read"),
        DocumentType.CREDIT_NOTE to listOf("invoicing.invoice.read"),
        DocumentType.WAYBILL to listOf("inventory.stock.read", "cashier.waybill.read"),
        DocumentType.RECEIPT to listOf("accounting.receipts.read"),
        DocumentType.SALE_RECEIPT to listOf("cashier.sales.read"),
        DocumentType.PRO_FORMA to listOf("cashier.proforma.read"),
    )

    private fun readableTypes(): Set<DocumentType> {
        val userId = currentActorProvider.current().userId
        return readPermissions.filterValues { codes -> codes.any { permissionAuthorizer.hasPermission(userId, it) } }.keys
    }

    private fun context() = FilterContext(costVisible = true, zone = moduleSettings.zone())

    fun filters(): FilterMetadata = tx.execute { filterSpec.metadata(context()) }

    fun list(request: ListSearchRequest): PageResult<IssuedDocumentRow> = tx.execute {
        val types = readableTypes()
        if (types.isEmpty()) throw ApiErrors.accessDenied()
        val context = context()
        val parsed = searchSpec.parse(request.q)
        val scope = mutableListOf<Op<Boolean>>(
            IssuedDocumentsTable.tenantId eq tenantContext.requireTenantId(),
            IssuedDocumentsTable.docType inList types.map { it.name },
        )
        locationAccess.allowedLocationIds()?.let { scope += IssuedDocumentsTable.locationId inList it }
        val condition = (scope + listOfNotNull(
            searchSpec.condition(parsed),
            filterSpec.fromParams(request.params, context),
            filterSpec.fromJson(request.filter, context),
        )).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) searchSpec.rank(parsed) + filterSpec.sort(emptyList(), context)
        else filterSpec.sort(request.sortParams, context)
        val query = joined.selectAll().where(condition).orderBy(*sort.toTypedArray())
        Paging.fetch(query, PageRequest.of(request.page, request.size), ::toRow)
            .copy(meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun get(ref: String): IssuedDocumentRow = tx.execute { toRow(readable(ref)) }

    fun reprint(ref: String, reason: String?): ReprintResult = tx.execute {
        val row = readable(ref)
        val count = registry.recordReprint(ref, reason)
        ReprintResult(ref, count, pdfUrl(row))
    }

    /** Not found (rather than forbidden) when the caller can't see it, so refs can't be probed. */
    private fun readable(ref: String): ResultRow {
        val row = joined.selectAll()
            .where { (IssuedDocumentsTable.tenantId eq tenantContext.requireTenantId()) and (IssuedDocumentsTable.ref eq ref) }
            .firstOrNull() ?: throw ApiErrors.documentNotFound()
        if (DocumentType.valueOf(row[IssuedDocumentsTable.docType]) !in readableTypes()) throw ApiErrors.documentNotFound()
        val allowed = locationAccess.allowedLocationIds()
        if (allowed != null && row[IssuedDocumentsTable.locationId] !in allowed) throw ApiErrors.documentNotFound()
        return row
    }

    private fun pdfUrl(row: ResultRow): String? {
        val module = row[IssuedDocumentsTable.sourceModule]
        return linkProviders.firstOrNull { it.sourceModule == module }
            ?.pdfUrl(DocumentType.valueOf(row[IssuedDocumentsTable.docType]), row[IssuedDocumentsTable.sourceId])
    }

    private fun toRow(row: ResultRow) = IssuedDocumentRow(
        id = row[IssuedDocumentsTable.id].value,
        ref = row[IssuedDocumentsTable.ref],
        docType = DocumentType.valueOf(row[IssuedDocumentsTable.docType]),
        sourceModule = row[IssuedDocumentsTable.sourceModule],
        sourceId = row[IssuedDocumentsTable.sourceId],
        location = ref(row[IssuedDocumentsTable.locationId], Ref::location),
        partyName = row[IssuedDocumentsTable.partyName],
        partyCustomer = ref(row[IssuedDocumentsTable.partyCustomerId], Ref::customer),
        valueAmount = row[IssuedDocumentsTable.valueAmount],
        issuedBy = UserRef(row[IssuedDocumentsTable.issuedByUserId].value),
        issuedAt = row[IssuedDocumentsTable.issuedAt],
        status = row[IssuedDocumentsTable.status],
        reprintCount = row[IssuedDocumentsTable.reprintCount],
        pdfUrl = pdfUrl(row),
    )
}

@Tag(name = "Documents", description = "Transaction Receipts: every issued invoice, waybill, receipt and pro-forma (docs/03 §8)")
@SecurityRequirement(name = "bearerAuth")
interface DocumentsApi {
    @Operation(summary = "List issued documents the caller may read (q, filters, paging)")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<IssuedDocumentRow>>

    @Operation(summary = "Search issued documents with an advanced filter tree")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<IssuedDocumentRow>>

    @Operation(summary = "Filterable fields for the document list")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "One document by reference")
    fun get(ref: String): ApiEnvelope<IssuedDocumentRow>

    @Operation(summary = "Record a reprint; the next PDF of it is marked DUPLICATE COPY")
    fun reprint(ref: String, request: ReprintRequest): ApiEnvelope<ReprintResult>
}

private const val READ_DOCUMENTS = "@permissionAuthorizer.hasPermission(authentication, 'platform.documents.read')"

@RestController
@RequestMapping("/api/v1/documents")
class DocumentsController(private val documentService: DocumentService) : DocumentsApi {
    @GetMapping
    @PreAuthorize(READ_DOCUMENTS)
    override fun list(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<IssuedDocumentRow>> =
        ApiEnvelope(documentService.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ_DOCUMENTS)
    override fun search(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<IssuedDocumentRow>> =
        ApiEnvelope(documentService.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ_DOCUMENTS)
    override fun filters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(documentService.filters())

    @GetMapping("/{ref}")
    @PreAuthorize(READ_DOCUMENTS)
    override fun get(@PathVariable ref: String): ApiEnvelope<IssuedDocumentRow> = ApiEnvelope(documentService.get(ref))

    @PostMapping("/{ref}/reprints")
    @PreAuthorize(READ_DOCUMENTS)
    override fun reprint(@PathVariable ref: String, @jakarta.validation.Valid @RequestBody request: ReprintRequest): ApiEnvelope<ReprintResult> =
        ApiEnvelope(documentService.reprint(ref, request.reason))
}
