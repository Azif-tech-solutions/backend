package com.zifiq.invoicing.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.invoicing.dto.DraftHeaderRequest
import com.zifiq.invoicing.dto.ReissueRequest
import com.zifiq.invoicing.dto.SandboxInvoiceRequest
import com.zifiq.invoicing.dto.WalkInBuyerRequest
import com.zifiq.invoicing.model.InvoiceLinesTable
import com.zifiq.invoicing.model.InvoiceWaybillLinksTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.invoicing.spi.InvoiceIssuer
import com.zifiq.invoicing.spi.InvoiceLineRequest
import com.zifiq.invoicing.spi.InvoiceLineView
import com.zifiq.invoicing.spi.InvoiceQueries
import com.zifiq.invoicing.spi.InvoiceRef
import com.zifiq.invoicing.spi.InvoiceRequest
import com.zifiq.invoicing.spi.InvoiceWaybillLinks
import com.zifiq.invoicing.spi.OpenInvoiceRef
import com.zifiq.invoicing.spi.Receivables
import com.zifiq.invoicing.spi.SourceType
import com.zifiq.invoicing.spi.WalkInBuyer
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * The invoice lifecycle (docs/08 §4.1, §4.7, §5): drafts, issue (the gap-free INV-n number is
 * assigned inside the issuing transaction), void and void-and-reissue. Also the spi other modules
 * call — Sales & POS issues invoices through [InvoiceIssuer] in its own transaction.
 *
 * Issued invoices are immutable (rule 14): V12's triggers reject any change to an issued invoice's
 * content or lines, so corrections only happen by void-and-reissue (or, with slice 2, credit notes).
 */
@Service
class InvoiceService(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
    private val builder: InvoiceBuilder,
    private val sequences: DocumentSequenceService,
    private val documents: DocumentRegistry,
    private val notify: Notify,
) : InvoiceIssuer, InvoiceQueries, InvoiceWaybillLinks, Receivables {

    // ---- InvoiceIssuer (spi, caller's transaction) ----

    override fun issue(req: InvoiceRequest): InvoiceRef {
        val issueDate = req.issueDate ?: support.today()
        checkIssueDate(issueDate, req.sourceDate)
        val id = insertDraft(req, issueDate)
        return issueStored(id, issueDate, req.paymentTermsDays, dueDate = null)
    }

    override fun createDraft(req: InvoiceRequest): InvoiceRef {
        val planned = req.issueDate
        planned?.let { checkIssueDate(it, req.sourceDate) }
        val id = insertDraft(req, planned)
        val row = row(id)
        support.record("invoicing.invoice.drafted", id, null,
            "Draft invoice for ${req.sourceRef} — ${row[InvoicesTable.buyerName]}, ${InvoiceSupport.naira(row[InvoicesTable.taxInclusiveAmount])}",
            mapOf("sourceType" to req.sourceType.name, "sourceId" to req.sourceId, "sourceRef" to req.sourceRef), req.locationId)
        return ref(row)
    }

    override fun voidForSource(sourceType: String, sourceId: UUID, reason: String) {
        val live = liveForSource(sourceType, sourceId, forUpdate = true) ?: return
        when (live[InvoicesTable.status]) {
            "DRAFT" -> discard(live)
            // The sale was voided in doc 09, which already voided its receipts: no INVOICE_HAS_PAYMENTS guard.
            else -> markVoid(live, reason.trim(), zeroPaid = true)
        }
    }

    override fun discardDraftForSource(sourceType: String, sourceId: UUID) {
        val live = liveForSource(sourceType, sourceId, forUpdate = true) ?: return
        if (live[InvoicesTable.status] != "DRAFT") throw ApiErrors.invoiceNotDraft()
        discard(live)
    }

    override fun reissueForSource(sourceType: String, sourceId: UUID, corrected: InvoiceRequest, reason: String): InvoiceRef {
        val live = liveForSource(sourceType, sourceId, forUpdate = true) ?: throw ApiErrors.invoiceNotFound()
        if (live[InvoicesTable.status] != "ISSUED") throw ApiErrors.invoiceNotIssued()
        return reissue(live, corrected.copy(sourceType = live.sourceType(), sourceId = sourceId), reason.trim())
    }

    // ---- InvoiceQueries / InvoiceWaybillLinks / Receivables (spi, caller's transaction) ----

    override fun byNumber(number: String): InvoiceRef? =
        InvoicesTable.selectAll().where { (InvoicesTable.tenantId eq support.tenantId()) and (InvoicesTable.number eq number.trim().uppercase()) }
            .firstOrNull()?.let(::ref)

    override fun bySource(sourceType: String, sourceId: UUID): InvoiceRef? = liveForSource(sourceType, sourceId)?.let(::ref)

    override fun linesOf(invoiceId: UUID): List<InvoiceLineView> =
        InvoiceLinesTable.selectAll().where { (InvoiceLinesTable.tenantId eq support.tenantId()) and (InvoiceLinesTable.invoiceId eq invoiceId) }
            .orderBy(InvoiceLinesTable.lineNo).map {
                InvoiceLineView(
                    lineNo = it[InvoiceLinesTable.lineNo], stockItemId = it[InvoiceLinesTable.stockItemId], itemCode = it[InvoiceLinesTable.itemCode],
                    itemName = it[InvoiceLinesTable.itemName], quantity = it[InvoiceLinesTable.quantity], unitPrice = it[InvoiceLinesTable.unitPrice],
                    lineNet = it[InvoiceLinesTable.lineNet], vatAmount = it[InvoiceLinesTable.vatAmount], lineTotal = it[InvoiceLinesTable.lineTotal],
                )
            }

    override fun link(invoiceId: UUID, waybillId: UUID, waybillRef: String, waybillDate: LocalDate) {
        val invoice = row(invoiceId)
        if (invoice[InvoicesTable.status] != "ISSUED") throw ApiErrors.invoiceNotIssued()
        InvoiceWaybillLinksTable.insertIgnore {
            it[tenantId] = support.tenantId()
            it[InvoiceWaybillLinksTable.invoiceId] = invoiceId
            it[InvoiceWaybillLinksTable.waybillId] = waybillId
            it[InvoiceWaybillLinksTable.waybillRef] = waybillRef
            it[InvoiceWaybillLinksTable.waybillDate] = waybillDate
            it[createdAt] = Instant.now()
        }
    }

    override fun outstandingFor(customerId: UUID): BigDecimal =
        openInvoicesFor(customerId).fold(BigDecimal.ZERO) { sum, it -> sum + it.balance }.setScale(2)

    override fun openInvoicesFor(customerId: UUID): List<OpenInvoiceRef> {
        val tolerance = support.tolerance()
        return InvoicesTable.selectAll()
            .where { (InvoicesTable.tenantId eq support.tenantId()) and (InvoicesTable.customerId eq customerId) and (InvoicesTable.status eq "ISSUED") }
            .orderBy(InvoicesTable.issueDate)
            .map { it to balanceOf(it) }
            .filter { (_, balance) -> balance > tolerance }
            .map { (row, balance) ->
                OpenInvoiceRef(row[InvoicesTable.id].value, row[InvoicesTable.number]!!, row[InvoicesTable.issueDate]!!, row[InvoicesTable.dueDate],
                    row[InvoicesTable.taxInclusiveAmount], balance)
            }
    }

    // ---- HTTP: drafts (§4.1) ----

    fun issueDraft(id: UUID, header: DraftHeaderRequest): UUID = tx.execute {
        val draft = draftForUpdate(id)
        val issueDate = header.issueDate ?: draft[InvoicesTable.issueDate] ?: support.today()
        checkIssueDate(issueDate, draft[InvoicesTable.sourceDate])
        val terms = header.paymentTermsDays ?: draft[InvoicesTable.paymentTermsDays]
        val due = header.dueDate ?: draft[InvoicesTable.dueDate]?.takeIf { header.issueDate == null && header.paymentTermsDays == null }
        // Re-validate as at issue: customer still active, location trading, items active, VAT at the issue date's rate (prices kept).
        builder.requireIssuingShowroom(draft[InvoicesTable.locationId].value)
        val buyer = builder.refreshedBuyer(draft, header.buyerEmail)
        val vat = builder.vatAt(draft[InvoicesTable.locationId].value, issueDate)
        val lines = builder.price(builder.storedLineInputs(id), vat)
        InvoiceLinesTable.deleteWhere { invoiceId eq id }
        builder.insertLines(id, lines)
        writeContent(id, buyer, lines, vat.enabled, vat.rate, builder.issuerSnapshot(draft[InvoicesTable.locationId].value), terms,
            header.paymentTermsNote ?: draft[InvoicesTable.paymentTermsNote])
        issueStored(id, issueDate, terms, due).id
    }

    fun updateDraft(id: UUID, header: DraftHeaderRequest): UUID = tx.execute {
        val draft = draftForUpdate(id)
        header.issueDate?.let { checkIssueDate(it, draft[InvoicesTable.sourceDate]) }
        val issueDate = header.issueDate ?: draft[InvoicesTable.issueDate]
        val terms = header.paymentTermsDays ?: draft[InvoicesTable.paymentTermsDays]
        val due = header.dueDate ?: issueDate?.plusDays(terms.toLong())
        if (due != null && issueDate != null && due.isBefore(issueDate)) throw ApiErrors.validationFailed(mapOf("dueDate" to "can't be before the issue date"))
        InvoicesTable.update({ InvoicesTable.id eq id }) {
            it[InvoicesTable.issueDate] = issueDate
            it[paymentTermsDays] = terms
            it[dueDate] = due
            if (header.paymentTermsNote != null) it[paymentTermsNote] = header.paymentTermsNote.trim().ifEmpty { null }
            if (header.buyerEmail != null) it[buyerEmail] = header.buyerEmail.trim().ifEmpty { null }
        }
        id
    }

    fun discardDraft(id: UUID) = tx.execute { discard(draftForUpdate(id)) }

    // ---- HTTP: void and void-and-reissue (§4.7) ----

    /** Returns the replacement's id when reissued, else the voided invoice's. */
    fun void(id: UUID, reason: String, reissue: ReissueRequest?): UUID = tx.execute {
        val invoice = rowForUpdate(id)
        support.assertVisible(invoice[InvoicesTable.locationId].value)
        support.assertAccess(invoice[InvoicesTable.locationId].value)
        if (invoice[InvoicesTable.status] != "ISSUED") throw ApiErrors.invoiceNotIssued()
        if (reissue == null) {
            if (invoice[InvoicesTable.amountPaid].signum() > 0 || invoice[InvoicesTable.amountCredited].signum() > 0) throw ApiErrors.invoiceHasPayments()
            markVoid(invoice, reason.trim(), zeroPaid = false)
            return@execute id
        }
        val corrected = sameSource(invoice).let { base ->
            when {
                reissue.customerId != null && reissue.walkInBuyer != null ->
                    throw ApiErrors.validationFailed(mapOf("reissue" to "give either a customer or walk-in buyer details, not both"))
                reissue.customerId != null -> base.copy(customerId = reissue.customerId, walkInBuyer = null, correspondentId = null)
                reissue.walkInBuyer != null -> base.copy(customerId = null, walkInBuyer = reissue.walkInBuyer.toSpi(), correspondentId = null)
                else -> base
            }
        }
        reissue(invoice, corrected, reason.trim(), emailOverride = reissue.buyerEmail).id
    }

    // ---- HTTP: sandbox (until Sales & POS exists) ----

    fun sandbox(request: SandboxInvoiceRequest): UUID = tx.execute {
        support.assertAccess(request.locationId!!)
        val sourceType = SourceType.valueOf(request.sourceType ?: "POS_SALE")
        val sourceRef = request.sourceRef?.trim()?.ifEmpty { null } ?: sequences.next("SBX")
        val req = InvoiceRequest(
            sourceType = sourceType, sourceId = UUID.randomUUID(), sourceRef = sourceRef, sourceDate = request.sourceDate ?: support.today(),
            proFormaRef = request.proFormaRef?.trim()?.ifEmpty { null } ?: if (sourceType == SourceType.PRO_FORMA) sourceRef else null,
            locationId = request.locationId, customerId = request.customerId, walkInBuyer = request.walkInBuyer?.toSpi(),
            issueDate = request.issueDate, paymentTermsDays = request.paymentTermsDays, paymentTermsNote = request.paymentTermsNote,
            lines = request.lines.map {
                InvoiceLineRequest(it.stockItemId!!, it.quantity!!, it.unitPrice!!, it.discountAmount ?: BigDecimal.ZERO, it.discountRate)
            },
        )
        if (request.asDraft) createDraft(req).id else issue(req).id
    }

    // ---- Internals (caller's transaction) ----

    private fun insertDraft(req: InvoiceRequest, plannedIssueDate: LocalDate?): UUID {
        if (req.sourceRef.isBlank()) throw ApiErrors.validationFailed(mapOf("sourceRef" to "must not be blank"))
        if (liveForSource(req.sourceType.name, req.sourceId) != null) throw ApiErrors.invoiceExistsForSource(req.sourceRef)
        builder.requireIssuingShowroom(req.locationId)
        val buyer = builder.buyer(req.customerId, req.walkInBuyer, correspondentId = req.correspondentId)
        val vat = builder.vatAt(req.locationId, plannedIssueDate ?: support.today())
        val lines = builder.price(req.lines, vat)
        val totals = InvoiceTotalsCalc.of(lines)
        val terms = req.paymentTermsDays ?: support.defaultTermsDays()
        if (terms < 0) throw ApiErrors.validationFailed(mapOf("paymentTermsDays" to "can't be negative"))
        val id = InvoicesTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[number] = null
            it[status] = "DRAFT"
            it[invoiceTypeCode] = "381"
            it[invoiceKind] = buyer.invoiceKind
            it[sourceType] = req.sourceType.name
            it[sourceId] = req.sourceId
            it[sourceRef] = req.sourceRef.trim().take(30)
            it[sourceDate] = req.sourceDate
            it[proFormaRef] = req.proFormaRef?.trim()?.take(30)
            it[locationId] = req.locationId
            it[issueDate] = plannedIssueDate
            it[dueDate] = plannedIssueDate?.plusDays(terms.toLong())
            it[paymentTermsDays] = terms
            it[paymentTermsNote] = req.paymentTermsNote?.trim()?.ifEmpty { null }?.take(300)
            it[documentCurrencyCode] = "NGN"
            it[taxCurrencyCode] = "NGN"
            buyerColumns(it, buyer)
            it[issuer] = builder.issuerSnapshot(req.locationId)
            it[lineExtensionAmount] = totals.lineExtension
            it[discountTotal] = totals.discount
            it[taxExclusiveAmount] = totals.lineExtension
            it[vatTotal] = totals.vat
            it[taxInclusiveAmount] = totals.total
            it[vatEnabled] = vat.enabled
            it[locationVatRate] = vat.rate.setScale(2)
            it[amountPaid] = BigDecimal.ZERO
            it[amountCredited] = BigDecimal.ZERO
            it[reissueOfInvoiceId] = null
            it[nrsSubmissionStatus] = "NOT_SUBMITTED"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        builder.insertLines(id, lines)
        return id
    }

    /** Refreshes a draft's buyer, issuer, VAT and money columns (only while DRAFT; issued rows are immutable). */
    private fun writeContent(id: UUID, buyer: BuyerSnapshot, lines: List<PricedLine>, vatEnabled: Boolean, vatRate: BigDecimal, issuerJson: String, terms: Int, note: String?) {
        val totals = InvoiceTotalsCalc.of(lines)
        InvoicesTable.update({ InvoicesTable.id eq id }) {
            it[invoiceKind] = buyer.invoiceKind
            buyerColumns(it, buyer)
            it[issuer] = issuerJson
            it[lineExtensionAmount] = totals.lineExtension
            it[discountTotal] = totals.discount
            it[taxExclusiveAmount] = totals.lineExtension
            it[vatTotal] = totals.vat
            it[taxInclusiveAmount] = totals.total
            it[InvoicesTable.vatEnabled] = vatEnabled
            it[locationVatRate] = vatRate.setScale(2)
            it[paymentTermsDays] = terms
            it[paymentTermsNote] = note?.trim()?.ifEmpty { null }?.take(300)
        }
    }

    private fun buyerColumns(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, buyer: BuyerSnapshot) {
        it[InvoicesTable.customerId] = buyer.customerId
        it[InvoicesTable.buyerName] = buyer.name.take(200)
        it[InvoicesTable.buyerType] = buyer.type
        it[InvoicesTable.buyerEmail] = buyer.email?.take(320)
        it[InvoicesTable.buyerPhone] = buyer.phone?.take(40)
        it[InvoicesTable.buyerTin] = buyer.tin?.take(40)
        it[InvoicesTable.buyerBusinessDescription] = buyer.businessDescription?.take(200)
        it[InvoicesTable.buyerStreetAddress] = buyer.streetAddress?.take(300)
        it[InvoicesTable.buyerCity] = buyer.city?.take(120)
        it[InvoicesTable.buyerPostalCode] = buyer.postalCode?.take(20)
        it[InvoicesTable.buyerStateCode] = buyer.stateCode?.take(5)
        it[InvoicesTable.buyerLgaCode] = buyer.lgaCode?.take(10)
        it[InvoicesTable.buyerCountryCode] = buyer.countryCode.take(2)
        it[InvoicesTable.correspondentId] = buyer.correspondentId
    }

    /** DRAFT → ISSUED: assigns the next INV number in this transaction (a rollback never burns one), registers the document. */
    private fun issueStored(id: UUID, issueDate: LocalDate, termsOverride: Int?, dueDate: LocalDate?): InvoiceRef {
        val draft = row(id)
        val terms = termsOverride ?: draft[InvoicesTable.paymentTermsDays]
        val due = dueDate ?: issueDate.plusDays(terms.toLong())
        if (due.isBefore(issueDate)) throw ApiErrors.validationFailed(mapOf("dueDate" to "can't be before the issue date"))
        val number = sequences.next("INV")
        val now = Instant.now()
        InvoicesTable.update({ InvoicesTable.id eq id }) {
            it[InvoicesTable.number] = number
            it[status] = "ISSUED"
            it[InvoicesTable.issueDate] = issueDate
            it[issueTime] = LocalTime.now(support.zone()).withNano(0)
            it[InvoicesTable.dueDate] = due
            it[paymentTermsDays] = terms
            it[issuedByUserId] = support.userId()
            it[issuedAt] = now
        }
        val issued = row(id)
        val total = issued[InvoicesTable.taxInclusiveAmount]
        documents.register(number, DocumentType.INVOICE, InvoiceSupport.SOURCE_MODULE, id, issued[InvoicesTable.locationId].value,
            issued[InvoicesTable.buyerName], issued[InvoicesTable.customerId], total)
        support.record("invoicing.invoice.issued", id, number,
            "$number issued to ${issued[InvoicesTable.buyerName]}: ${InvoiceSupport.naira(total)} (${issued[InvoicesTable.sourceRef]})",
            mapOf("total" to total, "sourceType" to issued[InvoicesTable.sourceType], "sourceId" to issued[InvoicesTable.sourceId],
                "sourceRef" to issued[InvoicesTable.sourceRef], "customerId" to issued[InvoicesTable.customerId]),
            issued[InvoicesTable.locationId].value)
        return ref(issued)
    }

    private fun discard(draft: ResultRow) {
        val id = draft[InvoicesTable.id].value
        InvoiceWaybillLinksTable.deleteWhere { invoiceId eq id }
        InvoiceLinesTable.deleteWhere { invoiceId eq id }
        InvoicesTable.deleteWhere { InvoicesTable.id eq id }
        support.record("invoicing.invoice.discarded", id, null, "Draft invoice for ${draft[InvoicesTable.sourceRef]} discarded",
            mapOf("sourceType" to draft[InvoicesTable.sourceType], "sourceId" to draft[InvoicesTable.sourceId], "sourceRef" to draft[InvoicesTable.sourceRef]),
            draft[InvoicesTable.locationId].value)
    }

    private fun markVoid(invoice: ResultRow, reason: String, zeroPaid: Boolean) {
        val id = invoice[InvoicesTable.id].value
        val number = invoice[InvoicesTable.number]!!
        InvoicesTable.update({ InvoicesTable.id eq id }) {
            it[status] = "VOID"
            it[voidReason] = reason.take(300)
            it[voidedByUserId] = support.userId()
            it[voidedAt] = Instant.now()
            if (zeroPaid) it[amountPaid] = BigDecimal.ZERO
        }
        documents.markVoided(number)
        val locationId = invoice[InvoicesTable.locationId].value
        val eventId = support.record("invoicing.invoice.voided", id, number, "$number voided: $reason",
            mapOf("total" to invoice[InvoicesTable.taxInclusiveAmount], "sourceType" to invoice[InvoicesTable.sourceType],
                "sourceId" to invoice[InvoicesTable.sourceId], "customerId" to invoice[InvoicesTable.customerId]), locationId)
        notify.holdersOf(
            permission = "invoicing.receivables.read", locationId = locationId, category = NotificationCategory.ALERT,
            title = "Invoice voided — $number",
            body = "${InvoiceSupport.naira(invoice[InvoicesTable.taxInclusiveAmount])} to ${invoice[InvoicesTable.buyerName]}: $reason",
            linkType = "invoice", linkRef = id.toString(), sourceEventId = eventId, exclude = support.userId(),
        )
    }

    /**
     * Voids [old] and issues [corrected] for the same source, linking the two. Payments move to the
     * replacement with slice 2; until then an issued invoice has none, so nothing is below "paid".
     */
    private fun reissue(old: ResultRow, corrected: InvoiceRequest, reason: String, emailOverride: String? = null): InvoiceRef {
        val oldId = old[InvoicesTable.id].value
        // Validate and price the replacement before voiding, so a bad correction leaves the invoice as it was.
        builder.requireIssuingShowroom(corrected.locationId)
        val issueDate = corrected.issueDate ?: support.today()
        val previewBuyer = builder.buyer(corrected.customerId, corrected.walkInBuyer, emailOverride, corrected.correspondentId)
        val previewTotal = InvoiceTotalsCalc.of(builder.price(corrected.lines, builder.vatAt(corrected.locationId, issueDate))).total
        if (previewTotal < old[InvoicesTable.amountPaid]) throw ApiErrors.reissueBelowPaid()

        markVoid(old, reason, zeroPaid = false)
        val newId = insertDraft(corrected.copy(issueDate = issueDate), issueDate)
        if (emailOverride != null || previewBuyer.email != null) {
            InvoicesTable.update({ InvoicesTable.id eq newId }) { it[buyerEmail] = previewBuyer.email }
        }
        InvoicesTable.update({ InvoicesTable.id eq newId }) { it[reissueOfInvoiceId] = oldId }
        val replacement = issueStored(newId, issueDate, corrected.paymentTermsDays, null)
        InvoicesTable.update({ InvoicesTable.id eq oldId }) { it[replacedByInvoiceId] = newId }
        return replacement
    }

    /** The voided invoice's source, buyer, lines and terms: the starting point for a reissue. */
    private fun sameSource(invoice: ResultRow): InvoiceRequest {
        val customerId = invoice[InvoicesTable.customerId]
        return InvoiceRequest(
            sourceType = invoice.sourceType(), sourceId = invoice[InvoicesTable.sourceId], sourceRef = invoice[InvoicesTable.sourceRef],
            sourceDate = invoice[InvoicesTable.sourceDate], proFormaRef = invoice[InvoicesTable.proFormaRef], locationId = invoice[InvoicesTable.locationId].value,
            customerId = customerId,
            walkInBuyer = if (customerId == null) WalkInBuyer(invoice[InvoicesTable.buyerName], invoice[InvoicesTable.buyerEmail], invoice[InvoicesTable.buyerPhone],
                invoice[InvoicesTable.buyerTin], invoice[InvoicesTable.buyerStreetAddress], invoice[InvoicesTable.buyerStateCode]) else null,
            issueDate = null, paymentTermsDays = invoice[InvoicesTable.paymentTermsDays], paymentTermsNote = invoice[InvoicesTable.paymentTermsNote],
            lines = builder.storedLineInputs(invoice[InvoicesTable.id].value), correspondentId = invoice[InvoicesTable.correspondentId],
        )
    }

    private fun checkIssueDate(issueDate: LocalDate, sourceDate: LocalDate) {
        if (issueDate.isAfter(support.today())) throw ApiErrors.validationFailed(mapOf("issueDate" to "can't be in the future"))
        if (issueDate.isBefore(sourceDate)) throw ApiErrors.validationFailed(mapOf("issueDate" to "can't be before the source document's date ($sourceDate)"))
    }

    private fun liveForSource(sourceType: String, sourceId: UUID, forUpdate: Boolean = false): ResultRow? =
        InvoicesTable.selectAll()
            .where { (InvoicesTable.tenantId eq support.tenantId()) and (InvoicesTable.sourceType eq sourceType) and (InvoicesTable.sourceId eq sourceId) and (InvoicesTable.status neq "VOID") }
            .let { if (forUpdate) it.forUpdate() else it }
            .firstOrNull()

    private fun row(id: UUID): ResultRow =
        InvoicesTable.selectAll().where { (InvoicesTable.id eq id) and (InvoicesTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.invoiceNotFound()

    private fun rowForUpdate(id: UUID): ResultRow =
        InvoicesTable.selectAll().where { (InvoicesTable.id eq id) and (InvoicesTable.tenantId eq support.tenantId()) }.forUpdate().firstOrNull()
            ?: throw ApiErrors.invoiceNotFound()

    private fun draftForUpdate(id: UUID): ResultRow {
        val draft = rowForUpdate(id)
        support.assertVisible(draft[InvoicesTable.locationId].value)
        support.assertAccess(draft[InvoicesTable.locationId].value)
        if (draft[InvoicesTable.status] != "DRAFT") throw ApiErrors.invoiceNotDraft()
        return draft
    }

    private fun balanceOf(row: ResultRow): BigDecimal =
        (row[InvoicesTable.taxInclusiveAmount] - row[InvoicesTable.amountPaid] - row[InvoicesTable.amountCredited]).setScale(2)

    private fun ref(row: ResultRow) = InvoiceRef(
        id = row[InvoicesTable.id].value, number = row[InvoicesTable.number], status = row[InvoicesTable.status],
        total = row[InvoicesTable.taxInclusiveAmount], balance = if (row[InvoicesTable.status] == "ISSUED") balanceOf(row) else BigDecimal.ZERO.setScale(2),
    )

    private fun ResultRow.sourceType(): SourceType = SourceType.valueOf(this[InvoicesTable.sourceType])

    private fun WalkInBuyerRequest.toSpi() = WalkInBuyer(name, email, phone, tin, address, stateCode)
}
