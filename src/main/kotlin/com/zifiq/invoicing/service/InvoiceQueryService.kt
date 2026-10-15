package com.zifiq.invoicing.service

import com.zifiq.platform.directory.Names
import com.zifiq.account.spi.Channel
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PagingPolicy
import com.zifiq.account.spi.ConfirmationStatus
import com.zifiq.account.spi.Receipts
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.filter.SortField
import com.zifiq.common.json.Json
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageResult
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.allOf
import com.zifiq.common.search.equalsText
import com.zifiq.common.sql.Aggregates
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.invoicing.dto.BuyerBlock
import com.zifiq.invoicing.dto.CreditNoteRef
import com.zifiq.invoicing.dto.DeliveryRow
import com.zifiq.invoicing.dto.InvoiceDelivery
import com.zifiq.invoicing.dto.InvoiceDetail
import com.zifiq.invoicing.dto.InvoiceLineDetail
import com.zifiq.invoicing.dto.InvoiceLinkRef
import com.zifiq.invoicing.dto.InvoiceLocation
import com.zifiq.invoicing.dto.InvoiceLookupRow
import com.zifiq.invoicing.dto.InvoicePaymentView
import com.zifiq.invoicing.dto.InvoiceRow
import com.zifiq.invoicing.dto.InvoiceTotals
import com.zifiq.invoicing.dto.InvoiceVat
import com.zifiq.invoicing.dto.IssuerBlock
import com.zifiq.invoicing.dto.LinkedDocuments
import com.zifiq.invoicing.dto.RegulatoryBlock
import com.zifiq.invoicing.dto.VoidedInvoiceRow
import com.zifiq.invoicing.dto.WaybillLink
import com.zifiq.invoicing.model.CreditNotesTable
import com.zifiq.invoicing.model.InvoiceDeliveriesTable
import com.zifiq.invoicing.model.InvoiceLinesTable
import com.zifiq.invoicing.model.InvoicePaymentsTable
import com.zifiq.invoicing.model.InvoiceWaybillLinksTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.documents.DocumentLinkProvider
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.settings.spi.CompanyProfileProvider
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Invoice history, detail, voided invoices, deliveries, pickers, PDFs, bulk export and global
 * search (docs/08 §4.2–4.4, §4.8–4.9, §9). The lists share [InvoiceListing], so derived statuses
 * (PARTIALLY_PAID, PAID, OVERDUE) and delivery states filter, facet, sort and label alike.
 */
@Service
class InvoiceQueryService(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
    private val listing: InvoiceListing,
    private val documents: DocumentRegistry,
    private val pdfRenderer: PdfRenderer,
    private val companyProfile: CompanyProfileProvider,
    private val customers: CustomerDirectory,
    private val receipts: Receipts,
) {
    // ---- History & search (§4.2) ----

    fun list(request: ListSearchRequest): PageResult<InvoiceRow> = tx.execute {
        val d = listing.expressions()
        val page = listing.page(request, d)
        page.result.withItems(toRows(page.result.items, d)).copy(
            summary = if (request.summary) summary(page.condition, d) else null,
            facets = listing.facets(request, page, d).ifEmpty { null },
            meta = PageMeta(ignoredQualifiers = page.ignoredQualifiers),
        )
    }

    fun filterMetadata(): FilterMetadata = tx.execute { listing.expressions().filterSpec.metadata(support.filterContext()) }

    /** Voided invoices with their reason and replacement (§4.9). */
    fun voided(request: ListSearchRequest): PageResult<VoidedInvoiceRow> = tx.execute {
        val d = listing.expressions()
        val sorted = if (request.sort.isEmpty()) request.copy(sort = listOf(SortField("voidedAt", "desc"))) else request
        val page = listing.page(sorted, d, InvoicesTable.status eq "VOID")
        val rows = page.result.items
        val users = Names.users(rows.mapNotNull { it[InvoicesTable.voidedByUserId]?.value })
        val replacements = numbers(rows.mapNotNull { it[InvoicesTable.replacedByInvoiceId] })
        val items = rows.map { row ->
            VoidedInvoiceRow(
                id = row[InvoicesTable.id].value, number = row[InvoicesTable.number]!!, voidReason = row[InvoicesTable.voidReason],
                customer = row[InvoicesTable.buyerName], issued = row[InvoicesTable.issueDate], total = row[InvoicesTable.taxInclusiveAmount],
                voidedBy = row[InvoicesTable.voidedByUserId]?.value?.let(users::get), voidedAt = row[InvoicesTable.voidedAt],
                replacedById = row[InvoicesTable.replacedByInvoiceId], replacedBy = row[InvoicesTable.replacedByInvoiceId]?.let(replacements::get),
            )
        }
        val summary = if (request.summary) {
            val count = Aggregates.count()
            val value = Aggregates.sum(InvoicesTable.taxInclusiveAmount)
            val row = listing.joined().select(count, value).where(page.condition).single()
            mapOf("count" to Aggregates.long(row, count), "value" to Aggregates.money(row, value))
        } else null
        page.result.withItems(items).copy(summary = summary, meta = PageMeta(ignoredQualifiers = page.ignoredQualifiers))
    }

    // ---- Deliveries (§4.8) ----

    /** Issued invoices with their e-mail state; header `{ issued, delivered, deliveredPct, failed, noEmailCount, noEmailValue }`. */
    fun deliveries(request: ListSearchRequest): PageResult<DeliveryRow> = tx.execute {
        val d = listing.expressions()
        val page = listing.page(request, d, InvoicesTable.status eq "ISSUED")
        val rows = page.result.items
        val attempts = deliveryStats(rows.map { it[InvoicesTable.id].value })
        val items = rows.map { row ->
            val id = row[InvoicesTable.id].value
            val code = row[d.delivery]
            DeliveryRow(
                invoiceId = id, number = row[InvoicesTable.number], issued = row[InvoicesTable.issueDate], customer = row[InvoicesTable.buyerName],
                email = row[InvoicesTable.buyerEmail], showroom = row.getOrNull(LocationsTable.name), total = row[InvoicesTable.taxInclusiveAmount],
                delivery = InvoiceExpressions.deliveryLabel(code), deliveryCode = code,
                lastAttemptAt = attempts[id]?.second, attempts = attempts[id]?.first ?: 0,
            )
        }
        val summary = if (request.summary) {
            val count = Aggregates.count()
            val sent = Aggregates.count(d.delivery.equalsText("SENT"))
            val failed = Aggregates.count(d.delivery.equalsText("FAILED"))
            val noEmail = Aggregates.count(d.delivery.equalsText("NO_EMAIL"))
            val noEmailValue = Aggregates.sum(InvoicesTable.taxInclusiveAmount, d.delivery.equalsText("NO_EMAIL"))
            val row = listing.joined().select(count, sent, failed, noEmail, noEmailValue).where(page.condition).single()
            val issued = Aggregates.long(row, count)
            val delivered = Aggregates.long(row, sent)
            mapOf(
                "issued" to issued, "delivered" to delivered,
                "deliveredPct" to if (issued == 0L) BigDecimal.ZERO.setScale(1) else BigDecimal(delivered * 100).divide(BigDecimal(issued), 1, java.math.RoundingMode.HALF_UP),
                "failed" to Aggregates.long(row, failed), "noEmailCount" to Aggregates.long(row, noEmail),
                "noEmailValue" to Aggregates.money(row, noEmailValue),
            )
        } else null
        page.result.withItems(items).copy(summary = summary, facets = listing.facets(request, page, d).ifEmpty { null }, meta = PageMeta(ignoredQualifiers = page.ignoredQualifiers))
    }

    // ---- Detail (§4.3) ----

    fun detail(id: UUID): InvoiceDetail = tx.execute { detailIn(id, scoped = true) }

    // ---- Lookups (§9) ----

    /** Picker: `number · customer · balance`. Issued invoices only; [openOnly] keeps those with a balance. */
    fun lookup(q: String?, openOnly: Boolean, customerId: UUID?, limit: Int = PagingPolicy.LOOKUP_DEFAULT): List<InvoiceLookupRow> = tx.execute {
        val d = listing.expressions()
        val parsed = d.searchSpec.parse(q)
        val condition = listOfNotNull(
            listing.visibility(),
            InvoicesTable.status eq "ISSUED",
            d.searchSpec.condition(parsed),
            customerId?.let { InvoicesTable.customerId eq it },
            if (openOnly) RawOp { +d.open } else null,
        ).allOf()!!
        val order = if (!parsed.isEmpty) d.searchSpec.rank(parsed) else listOf(InvoicesTable.issuedAt to SortOrder.DESC)
        listing.joined().select(InvoicesTable.columns + listOf(d.balance, d.displayStatus)).where(condition)
            .orderBy(*order.toTypedArray()).limit(PagingPolicy.lookupLimit(limit)).map { row ->
                val balance = Money.round(row[d.balance])
                val number = row[InvoicesTable.number]!!
                InvoiceLookupRow(
                    id = row[InvoicesTable.id].value, number = number, customerId = row[InvoicesTable.customerId], customer = row[InvoicesTable.buyerName],
                    issued = row[InvoicesTable.issueDate], total = row[InvoicesTable.taxInclusiveAmount], balance = balance,
                    displayStatus = InvoiceExpressions.label(row[d.displayStatus]),
                    label = "$number · ${row[InvoicesTable.buyerName]} · ${Money.naira(balance)}",
                )
            }
    }

    /** Resolves a sale (TXN-…) or pro-forma (PF-…) reference to its invoice: the live one, else the latest. */
    fun lookupSource(ref: String): InvoiceRow = tx.execute {
        val d = listing.expressions()
        val key = ref.trim().lowercase()
        if (key.isEmpty()) throw ApiErrors.validationFailed(mapOf("ref" to "must not be blank"))
        val condition = listing.visibility() and ((InvoicesTable.sourceRef.lowerCase() eq key) or (InvoicesTable.proFormaRef.lowerCase() eq key))
        val row = listing.joined().select(listing.selection(d)).where(condition)
            .orderBy(RawExpression<Int>({ +"(case when "; +InvoicesTable.status; +" = 'VOID' then 1 else 0 end)" }) to SortOrder.ASC, InvoicesTable.createdAt to SortOrder.DESC)
            .firstOrNull() ?: throw ApiErrors.invoiceNotFound()
        toRows(listOf(row), d).single()
    }

    // ---- PDF (§4.4) and bulk export (§4.8) ----

    data class PrintedInvoice(val fileName: String, val pdf: ByteArray)

    fun pdf(id: UUID): PrintedInvoice = tx.execute { render(detailIn(id, scoped = true)) }

    /** For the e-mail attachment: rendered without a signed-in user, in [tenantId] (the outbox has already chosen the invoice). */
    fun pdfForAttachment(tenantId: UUID, id: UUID): PrintedInvoice = tx.executeForTenant(tenantId) { render(detailIn(id, scoped = false)) }

    /** A ZIP of the PDFs the list filters select (drafts excluded), within the caller's scope. */
    fun exportZip(request: ListSearchRequest): Pair<String, ByteArray> = tx.execute {
        val d = listing.expressions()
        val max = support.settings().int(InvoicingSettings.BULK_EXPORT_MAX_INVOICES)
        val page = listing.page(request.copy(page = 0, size = 1, summary = false), d, InvoicesTable.status neq "DRAFT")
        val count = listing.joined().select(InvoicesTable.id).where(page.condition).count()
        if (count > max) throw ApiErrors.exportTooLarge(max, count)
        val ids = listing.joined().select(InvoicesTable.id).where(page.condition).orderBy(InvoicesTable.issuedAt to SortOrder.ASC).map { it[InvoicesTable.id].value }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ids.forEach { id ->
                val printed = render(detailIn(id, scoped = true))
                zip.putNextEntry(ZipEntry(printed.fileName))
                zip.write(printed.pdf)
                zip.closeEntry()
            }
        }
        "invoices-${support.today()}.zip" to out.toByteArray()
    }

    // ---- Global search (§9) ----

    fun search(q: String, limit: Int): List<SearchHit> =
        list(ListSearchRequest(q = q, size = limit, summary = false)).items.map {
            SearchHit("invoice", it.id, it.number ?: "Draft (${it.sourceRef})",
                "${it.customer} · ${Money.naira(it.total)} · ${it.displayStatus}" + if (it.daysOverdue > 0) " ${it.daysOverdue}d" else "",
                it.number, "/invoicing/invoices/${it.id}")
        }

    // ---- Internals (caller's transaction) ----

    private fun summary(condition: Op<Boolean>, d: InvoiceExpressions): Map<String, Any?> {
        val count = Aggregates.count()
        val combined = Aggregates.sum(InvoicesTable.taxInclusiveAmount, InvoicesTable.status eq "ISSUED")
        val outstanding = Aggregates.sum(d.balance, d.open)
        val overdue = Aggregates.sum(d.balance, d.displayStatus.equalsText("OVERDUE"))
        val fromProForma = Aggregates.count(InvoicesTable.sourceType eq "PRO_FORMA")
        val fromPos = Aggregates.count(InvoicesTable.sourceType eq "POS_SALE")
        val withWaybills = Aggregates.count(d.hasWaybills)
        val row = listing.joined().select(count, combined, outstanding, overdue, fromProForma, fromPos, withWaybills).where(condition).single()
        return mapOf(
            "invoicesOnFile" to Aggregates.long(row, count), "combinedValue" to Aggregates.money(row, combined),
            "outstanding" to Aggregates.money(row, outstanding), "overdue" to Aggregates.money(row, overdue),
            "fromProForma" to Aggregates.long(row, fromProForma), "fromPos" to Aggregates.long(row, fromPos), "withWaybills" to Aggregates.long(row, withWaybills),
        )
    }

    /** [scoped] = false only for system work that has already chosen the invoice (the e-mail attachment). */
    internal fun detailIn(id: UUID, scoped: Boolean): InvoiceDetail {
        val d = listing.expressions()
        val row = listing.joined().select(listing.selection(d)).where { (InvoicesTable.id eq id) and (InvoicesTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.invoiceNotFound()
        if (scoped) support.assertVisible(row[InvoicesTable.locationId].value)
        val lines = InvoiceLinesTable.selectAll().where { InvoiceLinesTable.invoiceId eq id }.orderBy(InvoiceLinesTable.lineNo).map { line ->
            InvoiceLineDetail(
                id = line[InvoiceLinesTable.id].value, lineNo = line[InvoiceLinesTable.lineNo], stockItemId = line[InvoiceLinesTable.stockItemId],
                code = line[InvoiceLinesTable.itemCode], name = line[InvoiceLinesTable.itemName], quantity = line[InvoiceLinesTable.quantity],
                unit = line[InvoiceLinesTable.unitName], nrsUnitCode = line[InvoiceLinesTable.nrsUnitCode], unitPrice = line[InvoiceLinesTable.unitPrice],
                discountAmount = line[InvoiceLinesTable.discountAmount], discountRate = line[InvoiceLinesTable.discountRate], lineNet = line[InvoiceLinesTable.lineNet],
                vatCategory = line[InvoiceLinesTable.vatCategoryCode], nrsTaxCategory = line[InvoiceLinesTable.nrsTaxCategoryCode], vatRate = line[InvoiceLinesTable.vatRate],
                vatAmount = line[InvoiceLinesTable.vatAmount], lineTotal = line[InvoiceLinesTable.lineTotal], isService = line[InvoiceLinesTable.isService],
                hsnCode = line[InvoiceLinesTable.hsnCode], hsnSectionCode = line[InvoiceLinesTable.hsnSectionCode], productCategory = line[InvoiceLinesTable.productCategory],
                isicCode = line[InvoiceLinesTable.isicCode], serviceCategory = line[InvoiceLinesTable.serviceCategory],
            )
        }
        val waybills = InvoiceWaybillLinksTable.selectAll().where { InvoiceWaybillLinksTable.invoiceId eq id }.orderBy(InvoiceWaybillLinksTable.waybillDate)
            .map { WaybillLink(it[InvoiceWaybillLinksTable.waybillId], it[InvoiceWaybillLinksTable.waybillRef], it[InvoiceWaybillLinksTable.waybillDate]) }
        val payments = paymentsOf(id)
        val creditNotes = CreditNotesTable.selectAll().where { CreditNotesTable.invoiceId eq id }.orderBy(CreditNotesTable.issuedAt).map {
            CreditNoteRef(it[CreditNotesTable.id].value, it[CreditNotesTable.number], it[CreditNotesTable.issuedAt], it[CreditNotesTable.reason],
                it[CreditNotesTable.total], it[CreditNotesTable.appliedToInvoice], it[CreditNotesTable.excessRefunded])
        }
        val users = Names.users(listOfNotNull(row[InvoicesTable.issuedByUserId]?.value, row[InvoicesTable.voidedByUserId]?.value))
        val linkedNumbers = numbers(listOfNotNull(row[InvoicesTable.reissueOfInvoiceId], row[InvoicesTable.replacedByInvoiceId]))
        val customerCode = row[InvoicesTable.customerId]?.let { runCatching { customers.get(it).code }.getOrNull() }
        val status = row[InvoicesTable.status]
        val pending = payments.filter { it.status == PENDING_LABEL }.fold(BigDecimal.ZERO) { s, it -> s + it.amount }.setScale(2)
        val deliveryCode = row[d.delivery]
        val latest = latestDelivery(id)
        return InvoiceDetail(
            id = id, number = row[InvoicesTable.number], status = status, displayStatus = InvoiceExpressions.label(row[d.displayStatus]),
            invoiceTypeCode = row[InvoicesTable.invoiceTypeCode], invoiceKind = row[InvoicesTable.invoiceKind],
            issueDate = row[InvoicesTable.issueDate], issueTime = row[InvoicesTable.issueTime], dueDate = row[InvoicesTable.dueDate],
            paymentTermsDays = row[InvoicesTable.paymentTermsDays], paymentTermsNote = row[InvoicesTable.paymentTermsNote],
            daysOverdue = row[d.daysOverdue],
            issuedBy = row[InvoicesTable.issuedByUserId]?.value?.let(users::get), issuedAt = row[InvoicesTable.issuedAt], createdAt = row[InvoicesTable.createdAt],
            showroom = InvoiceLocation(row[InvoicesTable.locationId].value, row.getOrNull(LocationsTable.name)),
            issuer = issuerBlock(row),
            buyer = BuyerBlock(
                customerId = row[InvoicesTable.customerId], code = customerCode, name = row[InvoicesTable.buyerName], type = row[InvoicesTable.buyerType],
                email = row[InvoicesTable.buyerEmail], phone = row[InvoicesTable.buyerPhone], tin = row[InvoicesTable.buyerTin],
                businessDescription = row[InvoicesTable.buyerBusinessDescription],
                address = listOfNotNull(row[InvoicesTable.buyerStreetAddress], row[InvoicesTable.buyerCity]).joinToString(", ").ifEmpty { null },
                stateCode = row[InvoicesTable.buyerStateCode], lgaCode = row[InvoicesTable.buyerLgaCode], countryCode = row[InvoicesTable.buyerCountryCode],
            ),
            lines = lines,
            totals = InvoiceTotals(
                lineExtension = row[InvoicesTable.lineExtensionAmount], discount = row[InvoicesTable.discountTotal], vat = row[InvoicesTable.vatTotal],
                total = row[InvoicesTable.taxInclusiveAmount], paid = row[InvoicesTable.amountPaid], credited = row[InvoicesTable.amountCredited],
                balance = if (status == "ISSUED") Money.round(row[d.balance]) else BigDecimal.ZERO.setScale(2), pendingConfirmation = pending,
            ),
            vat = InvoiceVat(row[InvoicesTable.vatEnabled], row[InvoicesTable.locationVatRate]),
            linked = LinkedDocuments(
                sourceType = row[InvoicesTable.sourceType], sourceRef = row[InvoicesTable.sourceRef], proForma = row[InvoicesTable.proFormaRef],
                waybills = waybills, payments = payments, creditNotes = creditNotes,
                reissueOf = row[InvoicesTable.reissueOfInvoiceId]?.let { InvoiceLinkRef(it, linkedNumbers[it]) },
                replacedBy = row[InvoicesTable.replacedByInvoiceId]?.let { InvoiceLinkRef(it, linkedNumbers[it]) },
            ),
            delivery = InvoiceDelivery(
                code = deliveryCode,
                status = if (latest?.status == "PENDING") "Queued" else InvoiceExpressions.deliveryLabel(deliveryCode),
                lastAttemptAt = latest?.at, recipient = latest?.recipient ?: row[InvoicesTable.buyerEmail], attempts = latest?.attempts ?: 0,
            ),
            regulatory = RegulatoryBlock(row[InvoicesTable.irn], null, null, "Not in this phase", row[InvoicesTable.nrsSubmissionStatus]),
            voidReason = row[InvoicesTable.voidReason], voidedBy = row[InvoicesTable.voidedByUserId]?.value?.let(users::get), voidedAt = row[InvoicesTable.voidedAt],
        )
    }

    /** Payments with their receipt's confirmation state, oldest first. */
    internal fun paymentsOf(invoiceId: UUID): List<InvoicePaymentView> =
        InvoicePaymentsTable.selectAll().where { InvoicePaymentsTable.invoiceId eq invoiceId }.orderBy(InvoicePaymentsTable.paidAt).map { p ->
            val receiptStatus = p[InvoicePaymentsTable.receiptStatus]
            val status = when (receiptStatus) {
                "DECLINED" -> "Declined"
                "VOIDED" -> "Voided"
                else -> if (receipts.get(p[InvoicePaymentsTable.paymentReceiptId])?.confirmationStatus == ConfirmationStatus.PENDING) PENDING_LABEL else "Confirmed"
            }
            InvoicePaymentView(
                id = p[InvoicePaymentsTable.id].value, date = p[InvoicePaymentsTable.paidAt], method = p[InvoicePaymentsTable.method],
                methodLabel = methodLabel(p[InvoicePaymentsTable.method]), amount = p[InvoicePaymentsTable.amount],
                receiptId = p[InvoicePaymentsTable.paymentReceiptId], receipt = p[InvoicePaymentsTable.receiptRef], status = status,
                source = p[InvoicePaymentsTable.paymentSource], reversalReason = p[InvoicePaymentsTable.reversalReason],
            )
        }

    private class LatestDelivery(val status: String, val at: Instant, val recipient: String, val attempts: Int)

    private fun latestDelivery(invoiceId: UUID): LatestDelivery? {
        val rows = InvoiceDeliveriesTable.join(NotificationOutboxTable, JoinType.INNER, additionalConstraint = {
            RawOp { +InvoiceDeliveriesTable.notificationOutboxId; +" = "; +NotificationOutboxTable.id }
        }).select(InvoiceDeliveriesTable.recipientEmail, InvoiceDeliveriesTable.requestedAt, NotificationOutboxTable.status, NotificationOutboxTable.sentAt)
            .where { InvoiceDeliveriesTable.invoiceId eq invoiceId }.orderBy(InvoiceDeliveriesTable.requestedAt to SortOrder.DESC).toList()
        val latest = rows.firstOrNull() ?: return null
        return LatestDelivery(latest[NotificationOutboxTable.status], latest[NotificationOutboxTable.sentAt] ?: latest[InvoiceDeliveriesTable.requestedAt],
            latest[InvoiceDeliveriesTable.recipientEmail], rows.size)
    }

    /** Per invoice: (deliveries requested, latest request time). */
    private fun deliveryStats(ids: Collection<UUID>): Map<UUID, Pair<Int, Instant>> =
        if (ids.isEmpty()) emptyMap()
        else InvoiceDeliveriesTable.select(InvoiceDeliveriesTable.invoiceId, InvoiceDeliveriesTable.requestedAt)
            .where { InvoiceDeliveriesTable.invoiceId inList ids }.toList()
            .groupBy { it[InvoiceDeliveriesTable.invoiceId].value }
            .mapValues { (_, rows) -> rows.size to rows.maxOf { it[InvoiceDeliveriesTable.requestedAt] } }

    private fun toRows(rows: List<ResultRow>, d: InvoiceExpressions): List<InvoiceRow> {
        val ids = rows.map { it[InvoicesTable.id].value }
        val waybills = if (ids.isEmpty()) emptyMap()
        else InvoiceWaybillLinksTable.select(InvoiceWaybillLinksTable.invoiceId, InvoiceWaybillLinksTable.waybillRef)
            .where { InvoiceWaybillLinksTable.invoiceId inList ids }.orderBy(InvoiceWaybillLinksTable.waybillDate).toList()
            .groupBy({ it[InvoiceWaybillLinksTable.invoiceId].value }, { it[InvoiceWaybillLinksTable.waybillRef] })
        return rows.map { row ->
            val status = row[InvoicesTable.status]
            InvoiceRow(
                id = row[InvoicesTable.id].value, number = row[InvoicesTable.number], status = status, displayStatus = InvoiceExpressions.label(row[d.displayStatus]),
                issued = if (status == "DRAFT") null else row[InvoicesTable.issueDate], due = row[InvoicesTable.dueDate],
                customerId = row[InvoicesTable.customerId], customer = row[InvoicesTable.buyerName], customerType = row[InvoicesTable.buyerType],
                locationId = row[InvoicesTable.locationId].value, showroom = row.getOrNull(LocationsTable.name),
                sourceType = row[InvoicesTable.sourceType], source = if (row[InvoicesTable.sourceType] == "PRO_FORMA") "Pro-forma" else "POS sale",
                sourceRef = row[InvoicesTable.sourceRef], proForma = row[InvoicesTable.proFormaRef],
                waybills = waybills[row[InvoicesTable.id].value].orEmpty(),
                total = row[InvoicesTable.taxInclusiveAmount], paid = row[InvoicesTable.amountPaid], credited = row[InvoicesTable.amountCredited],
                balance = if (status == "ISSUED") Money.round(row[d.balance]) else BigDecimal.ZERO.setScale(2),
                daysOverdue = row[d.daysOverdue], delivery = InvoiceExpressions.deliveryLabel(row[d.delivery]),
            )
        }
    }

    private fun render(detail: InvoiceDetail): PrintedInvoice {
        val company = companyProfile.get()
        val issuer = detail.issuer
        val stamp = when (detail.displayStatus) {
            "Void" -> "Void"
            "Draft" -> "Draft — not a tax invoice"
            "Paid" -> "Paid"
            else -> null
        }
        val model = mapOf(
            "number" to (detail.number ?: "DRAFT"),
            "title" to if (detail.status == "DRAFT") "Draft Invoice" else "Tax Invoice",
            "status" to detail.status,
            "logo" to company.logoDataUri(),
            "issuer" to mapOf(
                "tradingName" to (issuer?.tradingName ?: company.tradingName), "legalName" to issuer?.legalName, "address" to (issuer?.address ?: company.streetAddress),
                "phone" to (issuer?.phone ?: company.phone), "email" to issuer?.email, "tin" to issuer?.tin, "vatRegistrationNumber" to issuer?.vatRegistrationNumber,
                "locationName" to (issuer?.locationName ?: detail.showroom.name), "locationAddress" to issuer?.locationAddress,
                "documentFooter" to (issuerFooter(detail.id) ?: company.documentFooter),
            ),
            "buyer" to mapOf(
                "name" to detail.buyer.name, "code" to detail.buyer.code, "address" to detail.buyer.address, "phone" to detail.buyer.phone,
                "email" to detail.buyer.email, "tin" to detail.buyer.tin,
            ),
            "stamp" to stamp,
            "stampClass" to when (stamp) { "Paid" -> "ok"; "Void" -> "void"; null -> null; else -> "draft" },
            "voidReason" to detail.voidReason,
            "issueDate" to (detail.issueDate?.format(DATE) ?: "Not issued"),
            "dueDate" to (detail.dueDate?.format(DATE) ?: "—") + if (detail.paymentTermsDays > 0) " (${detail.paymentTermsDays} days)" else "",
            "sourceLabel" to (if (detail.linked.sourceType == "PRO_FORMA") "Pro-forma " else "POS sale ") + detail.linked.sourceRef,
            "proForma" to (detail.linked.proForma ?: "—"),
            "lines" to detail.lines.map { line ->
                mapOf(
                    "no" to line.lineNo, "name" to line.name, "code" to line.code, "quantity" to line.quantity.stripTrailingZeros().toPlainString(),
                    "unit" to line.unit, "price" to Money.naira(line.unitPrice),
                    "discount" to if (line.discountAmount.signum() > 0) Money.naira(line.discountAmount) else "—",
                    "vat" to if (line.vatAmount.signum() > 0) "${line.vatRate.stripTrailingZeros().toPlainString()}% ${Money.naira(line.vatAmount)}" else vatWord(line.vatCategory),
                    "amount" to Money.naira(line.lineTotal),
                )
            },
            "payments" to detail.linked.payments.filter { it.status != "Declined" && it.status != "Voided" }.map {
                mapOf("date" to LocalDateTime.ofInstant(it.date, support.zone()).format(DATE), "method" to it.methodLabel, "receipt" to it.receipt,
                    "amount" to Money.naira(it.amount), "pending" to (it.status == PENDING_LABEL))
            },
            "totals" to mapOf(
                "lineExtension" to Money.naira(detail.totals.lineExtension), "discount" to Money.naira(detail.totals.discount),
                "hasDiscount" to (detail.totals.discount.signum() > 0), "vat" to Money.naira(detail.totals.vat),
                "total" to Money.naira(detail.totals.total), "paid" to Money.naira(detail.totals.paid),
                "hasPaid" to (detail.totals.paid.signum() > 0), "credited" to Money.naira(detail.totals.credited),
                "hasCredited" to (detail.totals.credited.signum() > 0), "balance" to Money.naira(detail.totals.balance),
                "showBalance" to (detail.status == "ISSUED"),
            ),
            "vatLabel" to if (detail.vat.enabled) "VAT (${detail.vat.rate.stripTrailingZeros().toPlainString()}%)" else "VAT (not collected at this showroom)",
            "paymentTermsNote" to detail.paymentTermsNote,
            "duplicate" to (detail.number?.let(documents::isReprint) ?: false),
            "nrsFooter" to NRS_FOOTER,
            "printedAt" to LocalDateTime.now(support.zone()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
        )
        val fileName = detail.number ?: "draft-invoice-${detail.id.toString().take(8)}"
        return PrintedInvoice("$fileName.pdf", pdfRenderer.render("invoice", model))
    }

    private fun issuerBlock(row: ResultRow): IssuerBlock? {
        val map = runCatching { Json.readMap(row[InvoicesTable.issuer]) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        fun s(key: String) = map[key]?.toString()?.takeIf { it.isNotBlank() }
        return IssuerBlock(
            tradingName = s("tradingName") ?: "", legalName = s("legalName"), tin = s("tin"), vatRegistrationNumber = s("vatRegistrationNumber"),
            nrsBusinessId = s("nrsBusinessId"), email = s("email"), phone = s("phone"), address = s("address") ?: "",
            locationName = s("locationName") ?: "", locationAddress = s("locationAddress"),
        )
    }

    private fun issuerFooter(id: UUID): String? =
        InvoicesTable.select(InvoicesTable.issuer).where { InvoicesTable.id eq id }.firstOrNull()
            ?.let { runCatching { Json.readMap(it[InvoicesTable.issuer])["documentFooter"]?.toString() }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun numbers(ids: Collection<UUID>): Map<UUID, String?> =
        if (ids.isEmpty()) emptyMap()
        else InvoicesTable.select(InvoicesTable.id, InvoicesTable.number).where { InvoicesTable.id inList ids.toSet() }
            .associate { it[InvoicesTable.id].value to it[InvoicesTable.number] }

    companion object {
        /** Kept for callers that predate [InvoiceExpressions]. */
        val STATUSES = InvoiceExpressions.STATUSES
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
        const val PENDING_LABEL = "Pending confirmation"

        const val NRS_FOOTER = "Issued under ZifIQ internal digital invoicing. This document is not transmitted to the NRS/FIRS Merchant-Buyer " +
            "Solution and carries no Invoice Reference Number or cryptographic stamp in this phase."

        fun label(status: String): String = InvoiceExpressions.label(status)

        fun methodLabel(method: String): String = Channel.labelOf(method)

        private fun vatWord(category: String) = when (category) {
            "ZERO_RATED" -> "Zero-rated"
            "EXEMPT" -> "Exempt"
            else -> "—"
        }
    }
}

/** Lets the document registry link invoices and credit notes to their PDF. */
@Component
class InvoicingDocumentLinks : DocumentLinkProvider {
    override val sourceModule = InvoiceSupport.SOURCE_MODULE
    override fun pdfUrl(docType: DocumentType, sourceId: UUID): String? = when (docType) {
        DocumentType.INVOICE -> "/api/v1/invoicing/invoices/$sourceId/invoice.pdf"
        DocumentType.CREDIT_NOTE -> "/api/v1/invoicing/credit-notes/$sourceId/credit-note.pdf"
        else -> null
    }
}

/** Global search provider `invoice` (docs/08 §9); an exact invoice number ranks first. */
@Component
class InvoiceSearchProvider(private val support: InvoiceSupport, private val invoices: InvoiceQueryService) : SearchProvider {
    override val type = "invoice"
    override val label = "Invoices"
    override val listRoute = "/invoicing/invoices"

    override fun canSearch() = support.can("invoicing.invoice.read")

    override fun search(q: String, limit: Int): List<SearchHit> = invoices.search(q, limit)
}
