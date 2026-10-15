package com.zifiq.invoicing.service

import com.zifiq.platform.directory.UserRef
import com.zifiq.common.sql.Aggregates
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.Facets
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
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.invoicing.dto.CreditNoteDetail
import com.zifiq.invoicing.dto.CreditNoteLineDetail
import com.zifiq.invoicing.dto.CreditNoteRequest
import com.zifiq.invoicing.dto.CreditNoteRow
import com.zifiq.invoicing.dto.InvoiceLinkRef
import com.zifiq.invoicing.dto.InvoiceLocation
import com.zifiq.invoicing.model.CreditNoteLinesTable
import com.zifiq.invoicing.model.CreditNotesTable
import com.zifiq.invoicing.model.InvoiceLinesTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.invoicing.spi.CreditNoteLineView
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.settings.spi.CompanyProfileProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Credit notes (docs/08 §4.5): the only way an issued invoice's value goes down (rule 14). Financial
 * only — they never move stock (§1.3-4). VAT on each line reverses the original line's rate. The part
 * that reduces the invoice balance is `applied_to_invoice`; anything beyond a balance already paid is
 * credited to the customer's wallet (or refused, per `invoicing.creditNoteExcessTo`).
 */
@Service
class CreditNoteService(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
    private val sequences: DocumentSequenceService,
    private val documents: DocumentRegistry,
    private val wallets: WalletLedger,
    private val pdfRenderer: PdfRenderer,
    private val companyProfile: CompanyProfileProvider,
) {
    fun issue(invoiceId: UUID, request: CreditNoteRequest): UUID = tx.execute {
        val invoice = InvoicesTable.selectAll().where { (InvoicesTable.id eq invoiceId) and (InvoicesTable.tenantId eq support.tenantId()) }.forUpdate().firstOrNull()
            ?: throw ApiErrors.invoiceNotFound()
        val locationId = invoice[InvoicesTable.locationId].value
        support.assertVisible(locationId)
        support.assertAccess(locationId)
        if (invoice[InvoicesTable.status] != "ISSUED") throw ApiErrors.invoiceNotIssued()

        val invoiceLines = InvoiceLinesTable.selectAll().where { InvoiceLinesTable.invoiceId eq invoiceId }.associateBy { it[InvoiceLinesTable.id].value }
        val ids = request.lines.map { it.invoiceLineId!! }
        if (ids.toSet().size != ids.size) throw ApiErrors.validationFailed(mapOf("lines" to "each invoice line at most once per credit note"))
        val previous = previousCredits(invoiceLines.keys)

        val priced = request.lines.mapIndexed { index, input ->
            val line = invoiceLines[input.invoiceLineId!!] ?: throw ApiErrors.validationFailed(mapOf("lines[$index].invoiceLineId" to "is not a line of this invoice"))
            if ((input.quantity == null) == (input.amount == null)) throw ApiErrors.validationFailed(mapOf("lines[$index]" to "give either quantity or amount"))
            val lineNo = line[InvoiceLinesTable.lineNo]
            val (prevQuantity, prevNet) = previous[input.invoiceLineId] ?: (BigDecimal.ZERO to BigDecimal.ZERO)
            val net = if (input.quantity != null) {
                val quantity = input.quantity.setScale(3, RoundingMode.HALF_UP)
                if (prevQuantity + quantity > line[InvoiceLinesTable.quantity]) throw ApiErrors.creditExceedsLine(lineNo)
                // Quantity credits keep the line's own price (after its discount): a share of its net.
                Money.round(line[InvoiceLinesTable.lineNet] * quantity / line[InvoiceLinesTable.quantity])
            } else Money.round(input.amount!!)
            if (prevNet + net > line[InvoiceLinesTable.lineNet]) throw ApiErrors.creditExceedsLine(lineNo)
            val vat = Money.round(net * line[InvoiceLinesTable.vatRate] / BigDecimal(100))
            PricedCredit(line[InvoiceLinesTable.id].value, input.quantity?.setScale(3, RoundingMode.HALF_UP), input.amount?.let(Money::round), net, vat, net + vat)
        }
        val lineExtension = priced.fold(BigDecimal.ZERO) { s, it -> s + it.net }.setScale(2)
        val vatTotal = priced.fold(BigDecimal.ZERO) { s, it -> s + it.vat }.setScale(2)
        val total = lineExtension + vatTotal
        if (total.signum() <= 0) throw ApiErrors.validationFailed(mapOf("lines" to "the credit note must be worth something"))

        val balance = (invoice[InvoicesTable.taxInclusiveAmount] - invoice[InvoicesTable.amountPaid] - invoice[InvoicesTable.amountCredited]).max(BigDecimal.ZERO).setScale(2)
        val applied = total.min(balance)
        val excess = total - applied
        val customerId = invoice[InvoicesTable.customerId]
        if (excess.signum() > 0) {
            if (support.settings().string(InvoicingSettings.CREDIT_NOTE_EXCESS_TO) == "BLOCK") {
                throw ApiErrors.creditExceedsBalance("This credit note is ${Money.naira(excess)} more than the invoice's balance; refund the customer through Accounts instead")
            }
            if (customerId == null) {
                throw ApiErrors.creditExceedsBalance("This credit note is ${Money.naira(excess)} more than the invoice's balance and a walk-in buyer has no wallet; refund through Accounts instead")
            }
        }

        val id = UUID.randomUUID()
        val number = sequences.next("CN")
        val invoiceNumber = invoice[InvoicesTable.number]!!
        val ledgerEntryId = if (excess.signum() > 0) {
            wallets.credit(
                LedgerPosting(
                    customerId = customerId!!, balanceType = BalanceType.WALLET, entryType = LedgerEntryType.CREDIT_NOTE, amount = excess, reference = number,
                    description = "Credit note $number on $invoiceNumber (beyond the balance)", paymentMethod = null,
                    sourceModule = InvoiceSupport.SOURCE_MODULE, sourceType = "CREDIT_NOTE", sourceId = id, locationId = locationId,
                ),
            ).id
        } else null
        CreditNotesTable.insert {
            it[CreditNotesTable.id] = id
            it[tenantId] = support.tenantId()
            it[CreditNotesTable.number] = number
            it[invoiceTypeCode] = "380"
            it[CreditNotesTable.invoiceId] = invoiceId
            it[reason] = request.reason!!
            it[reasonNote] = request.reasonNote.trim()
            it[lineExtensionAmount] = lineExtension
            it[CreditNotesTable.vatTotal] = vatTotal
            it[CreditNotesTable.total] = total
            it[appliedToInvoice] = applied
            it[excessRefunded] = excess
            it[excessCustomerLedgerEntryId] = ledgerEntryId
            it[issuedByUserId] = support.userId()
            it[issuedAt] = Instant.now()
        }
        val tenantId = support.tenantId()
        CreditNoteLinesTable.batchInsert(priced, shouldReturnGeneratedValues = false) { line ->
            this[CreditNoteLinesTable.tenantId] = tenantId
            this[CreditNoteLinesTable.creditNoteId] = id
            this[CreditNoteLinesTable.invoiceLineId] = line.invoiceLineId
            this[CreditNoteLinesTable.quantity] = line.quantity
            this[CreditNoteLinesTable.amount] = line.amount
            this[CreditNoteLinesTable.lineNet] = line.net
            this[CreditNoteLinesTable.vatAmount] = line.vat
            this[CreditNoteLinesTable.lineTotal] = line.total
        }
        if (applied.signum() > 0) {
            InvoicesTable.update({ InvoicesTable.id eq invoiceId }) { it[amountCredited] = invoice[InvoicesTable.amountCredited] + applied }
        }
        documents.register(number, DocumentType.CREDIT_NOTE, InvoiceSupport.SOURCE_MODULE, id, locationId, invoice[InvoicesTable.buyerName], customerId, total)
        support.record("invoicing.credit-note.issued", id, number,
            "$number on $invoiceNumber: ${Money.naira(total)} (${request.reason!!.lowercase().replace('_', ' ')})" +
                if (excess.signum() > 0) ", ${Money.naira(excess)} to the wallet" else "",
            mapOf("invoiceId" to invoiceId, "invoiceNumber" to invoiceNumber, "total" to total, "applied" to applied, "excess" to excess, "reason" to request.reason), locationId)
        val newBalance = balance - applied
        if (balance > support.tolerance() && newBalance <= support.tolerance()) {
            support.record("invoicing.invoice.paid", invoiceId, invoiceNumber, "$invoiceNumber settled by credit note $number", mapOf("creditNote" to number), locationId)
        }
        id
    }

    fun list(request: ListSearchRequest): PageResult<CreditNoteRow> = tx.execute {
        val context = support.filterContext()
        val parsed = searchSpec.parse(request.q)
        val scope = visibility()
        val search = searchSpec.condition(parsed)
        val condition = listOfNotNull(scope, search, filterSpec.fromParams(request.params, context), filterSpec.fromJson(request.filter, context)).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) searchSpec.rank(parsed) + filterSpec.sort(emptyList(), context) else filterSpec.sort(request.sortParams, context)
        val page = Paging.fetch(joined().selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val items = page.items.map { row ->
            CreditNoteRow(
                id = row[CreditNotesTable.id].value, number = row[CreditNotesTable.number], invoiceId = row[CreditNotesTable.invoiceId].value,
                invoiceNumber = row[InvoicesTable.number], customerId = row[InvoicesTable.customerId], customer = row[InvoicesTable.buyerName],
                showroom = row.getOrNull(LocationsTable.name), reason = row[CreditNotesTable.reason], reasonNote = row[CreditNotesTable.reasonNote],
                total = row[CreditNotesTable.total], appliedToInvoice = row[CreditNotesTable.appliedToInvoice], excessRefunded = row[CreditNotesTable.excessRefunded],
                issuedBy = UserRef(row[CreditNotesTable.issuedByUserId].value), issuedAt = row[CreditNotesTable.issuedAt],
            )
        }
        val summary = if (request.summary) {
            val count = Aggregates.count()
            val total = Aggregates.sum(CreditNotesTable.total)
            val excess = Aggregates.sum(CreditNotesTable.excessRefunded)
            val row = joined().select(count, total, excess).where(condition).single()
            mapOf("count" to Aggregates.long(row, count), "total" to Aggregates.money(row, total), "excessToWallets" to Aggregates.money(row, excess))
        } else null
        val facets = request.facets.take(Facets.MAX_FACETS).associateWith { key ->
            if (key != "reason") throw ApiErrors.invalidFilter(key, "Not a facet", listOf("reason"))
            val others = Facets.paramsWithout(request.params, key)
            Facets.count(joined(), listOfNotNull(scope, search, filterSpec.fromParams(others, context)).allOf(), CreditNotesTable.reason) { v -> v?.let(::reasonLabel) }
        }
        page.withItems(items).copy(summary = summary, facets = facets.ifEmpty { null }, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filterSpec.metadata(support.filterContext()) }

    fun get(id: UUID): CreditNoteDetail = tx.execute { detailIn(id) }

    data class PrintedCreditNote(val fileName: String, val pdf: ByteArray)

    fun pdf(id: UUID): PrintedCreditNote = tx.execute {
        val note = detailIn(id)
        val company = companyProfile.get()
        val model = mapOf(
            "note" to note,
            "company" to company,
            "logo" to company.logoDataUri(),
            "reason" to reasonLabel(note.reason),
            "issuedAt" to LocalDateTime.ofInstant(note.issuedAt, support.zone()).format(DATE_TIME),
            "invoiceIssueDate" to (note.invoiceIssueDate?.format(DATE) ?: "—"),
            "lines" to note.lines.map { line ->
                mapOf("no" to line.lineNo, "name" to line.name, "code" to line.code,
                    "basis" to (line.quantity?.let { "${it.stripTrailingZeros().toPlainString()} × ${Money.naira(line.unitPrice)}" } ?: "Value adjustment"),
                    "net" to Money.naira(line.lineNet), "vat" to "${line.vatRate.stripTrailingZeros().toPlainString()}% ${Money.naira(line.vatAmount)}",
                    "total" to Money.naira(line.lineTotal))
            },
            "lineExtension" to Money.naira(note.lineExtension), "vat" to Money.naira(note.vat), "total" to Money.naira(note.total),
            "applied" to Money.naira(note.appliedToInvoice), "excess" to Money.naira(note.excessRefunded), "hasExcess" to (note.excessRefunded.signum() > 0),
            "duplicate" to documents.isReprint(note.number),
            "nrsFooter" to InvoiceQueryService.NRS_FOOTER,
            "printedAt" to LocalDateTime.now(support.zone()).format(DATE_TIME),
        )
        PrintedCreditNote("${note.number}.pdf", pdfRenderer.render("credit-note", model))
    }

    /** spi (doc 10): credit-note lines in a date range, for deducting from output VAT. Caller's transaction. */
    fun creditNoteLines(locationId: UUID?, from: LocalDate, to: LocalDate): List<CreditNoteLineView> {
        val zone = support.zone()
        val start = from.atStartOfDay(zone).toInstant()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant()
        return CreditNoteLinesTable
            .join(CreditNotesTable, JoinType.INNER, CreditNoteLinesTable.creditNoteId, CreditNotesTable.id)
            .join(InvoicesTable, JoinType.INNER, CreditNotesTable.invoiceId, InvoicesTable.id)
            .join(InvoiceLinesTable, JoinType.INNER, CreditNoteLinesTable.invoiceLineId, InvoiceLinesTable.id)
            .selectAll()
            .where {
                listOfNotNull(
                    CreditNotesTable.tenantId eq support.tenantId(),
                    locationId?.let { InvoicesTable.locationId eq it },
                ).allOf()!! and (CreditNotesTable.issuedAt greaterEq start) and (CreditNotesTable.issuedAt less end)
            }
            .orderBy(CreditNotesTable.issuedAt).map {
                CreditNoteLineView(
                    date = LocalDate.ofInstant(it[CreditNotesTable.issuedAt], zone), creditNoteNumber = it[CreditNotesTable.number],
                    invoiceNumber = it[InvoicesTable.number]!!, locationId = it[InvoicesTable.locationId].value, buyerName = it[InvoicesTable.buyerName],
                    buyerTin = it[InvoicesTable.buyerTin], lineNet = it[CreditNoteLinesTable.lineNet], vatRate = it[InvoiceLinesTable.vatRate],
                    vatAmount = it[CreditNoteLinesTable.vatAmount],
                )
            }
    }

    fun search(q: String, limit: Int): List<SearchHit> =
        list(ListSearchRequest(q = q, size = limit, summary = false)).items.map {
            SearchHit("credit-note", it.id, it.number, "${it.invoiceNumber} · ${it.customer} · ${Money.naira(it.total)}", it.number, "/invoicing/credit-notes/${it.id}")
        }

    // ---- Internals (caller's transaction) ----

    private class PricedCredit(val invoiceLineId: UUID, val quantity: BigDecimal?, val amount: BigDecimal?, val net: BigDecimal, val vat: BigDecimal, val total: BigDecimal)

    /** Per invoice line: (credited quantity, credited net) so far. */
    private fun previousCredits(lineIds: Collection<UUID>): Map<UUID, Pair<BigDecimal, BigDecimal>> =
        if (lineIds.isEmpty()) emptyMap()
        else CreditNoteLinesTable.select(CreditNoteLinesTable.invoiceLineId, CreditNoteLinesTable.quantity, CreditNoteLinesTable.lineNet)
            .where { CreditNoteLinesTable.invoiceLineId inList lineIds }.toList()
            .groupBy { it[CreditNoteLinesTable.invoiceLineId].value }
            .mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { s, it -> s + (it[CreditNoteLinesTable.quantity] ?: BigDecimal.ZERO) } to
                    rows.fold(BigDecimal.ZERO) { s, it -> s + it[CreditNoteLinesTable.lineNet] }
            }

    private fun detailIn(id: UUID): CreditNoteDetail {
        val row = joined().selectAll().where { (CreditNotesTable.id eq id) and (CreditNotesTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.creditNoteNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[InvoicesTable.locationId].value !in visible) throw ApiErrors.creditNoteNotFound()
        val lines = CreditNoteLinesTable.join(InvoiceLinesTable, JoinType.INNER, CreditNoteLinesTable.invoiceLineId, InvoiceLinesTable.id)
            .selectAll().where { CreditNoteLinesTable.creditNoteId eq id }.orderBy(InvoiceLinesTable.lineNo).map {
                CreditNoteLineDetail(
                    id = it[CreditNoteLinesTable.id].value, invoiceLineId = it[CreditNoteLinesTable.invoiceLineId].value, lineNo = it[InvoiceLinesTable.lineNo],
                    code = it[InvoiceLinesTable.itemCode], name = it[InvoiceLinesTable.itemName], quantity = it[CreditNoteLinesTable.quantity],
                    amount = it[CreditNoteLinesTable.amount], unitPrice = it[InvoiceLinesTable.unitPrice], vatRate = it[InvoiceLinesTable.vatRate],
                    lineNet = it[CreditNoteLinesTable.lineNet], vatAmount = it[CreditNoteLinesTable.vatAmount], lineTotal = it[CreditNoteLinesTable.lineTotal],
                )
            }
        return CreditNoteDetail(
            id = id, number = row[CreditNotesTable.number], invoiceTypeCode = row[CreditNotesTable.invoiceTypeCode],
            invoice = InvoiceLinkRef(row[CreditNotesTable.invoiceId].value, row[InvoicesTable.number]), invoiceIssueDate = row[InvoicesTable.issueDate],
            customerId = row[InvoicesTable.customerId], customer = row[InvoicesTable.buyerName],
            showroom = InvoiceLocation(row[InvoicesTable.locationId].value, row.getOrNull(LocationsTable.name)),
            reason = row[CreditNotesTable.reason], reasonNote = row[CreditNotesTable.reasonNote], lines = lines,
            lineExtension = row[CreditNotesTable.lineExtensionAmount], vat = row[CreditNotesTable.vatTotal], total = row[CreditNotesTable.total],
            appliedToInvoice = row[CreditNotesTable.appliedToInvoice], excessRefunded = row[CreditNotesTable.excessRefunded],
            issuedBy = UserRef(row[CreditNotesTable.issuedByUserId].value), issuedAt = row[CreditNotesTable.issuedAt],
        )
    }

    private fun visibility(): Op<Boolean> = listOfNotNull(
        CreditNotesTable.tenantId eq support.tenantId(),
        support.visibleLocations()?.let { InvoicesTable.locationId inList it },
    ).allOf()!!

    private fun joined() = CreditNotesTable
        .join(InvoicesTable, JoinType.INNER, CreditNotesTable.invoiceId, InvoicesTable.id)
        .join(LocationsTable, JoinType.LEFT, InvoicesTable.locationId, LocationsTable.id)

    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("invoiceId", "Invoice", FilterType.UUID, CreditNotesTable.invoiceId, lookup = "/api/v1/invoicing/invoices/lookup"),
            FilterField("customerId", "Customer", FilterType.UUID, InvoicesTable.customerId, aliases = setOf("customer"), lookup = "/api/v1/customers/lookup"),
            FilterField("reason", "Reason", FilterType.ENUM, CreditNotesTable.reason, REASONS.map { FilterOption(it, reasonLabel(it)) }, facetable = true),
            FilterField("locationId", "Showroom", FilterType.LOCATION, InvoicesTable.locationId, aliases = setOf("showroom")),
            FilterField("issuedBy", "Issued by", FilterType.UUID, CreditNotesTable.issuedByUserId),
            FilterField("total", "Total", FilterType.MONEY, CreditNotesTable.total, sortable = true),
            FilterField("issuedAt", "Issued", FilterType.DATETIME, CreditNotesTable.issuedAt, sortable = true),
            FilterField("number", "Number", FilterType.TEXT, CreditNotesTable.number, sortable = true),
            FilterField("hasExcessRefunded", "Excess to wallet", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +CreditNotesTable.excessRefunded; +" > 0)" })),
        ),
        primaryDateKey = "issuedAt",
        defaultSort = listOf("issuedAt" to SortOrder.DESC),
        tiebreaker = CreditNotesTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(CreditNotesTable.number, SearchKind.REFERENCE, "Number"),
            SearchField(InvoicesTable.number, SearchKind.REFERENCE, "Invoice"),
            SearchField(InvoicesTable.buyerName, SearchKind.TRIGRAM, "Customer"),
            SearchField(RawExpression<String>({ +"credit_notes.search_vector" }), SearchKind.FULLTEXT, "Reason"),
            SearchField(CreditNotesTable.total, SearchKind.AMOUNT, "Total"),
        ),
        qualifiers = filterSpec,
    )

    companion object {
        val REASONS = listOf("PRICING_ERROR", "OVERCHARGE", "DISCOUNT_GRANTED_AFTER_ISSUE", "DUPLICATE_BILLING", "GOODWILL", "OTHER")
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
        private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

        fun reasonLabel(reason: String): String = when (reason) {
            "PRICING_ERROR" -> "Pricing error"
            "OVERCHARGE" -> "Overcharge"
            "DISCOUNT_GRANTED_AFTER_ISSUE" -> "Discount granted after issue"
            "DUPLICATE_BILLING" -> "Duplicate billing"
            "GOODWILL" -> "Goodwill"
            else -> "Other"
        }
    }
}

/** Global search provider `credit-note` (docs/08 §9). */
@Component
class CreditNoteSearchProvider(private val support: InvoiceSupport, private val creditNotes: CreditNoteService) : SearchProvider {
    override val type = "credit-note"
    override val label = "Credit notes"
    override val listRoute = "/invoicing/credit-notes"

    override fun canSearch() = support.can("invoicing.invoice.read")

    override fun search(q: String, limit: Int): List<SearchHit> = creditNotes.search(q, limit)
}
