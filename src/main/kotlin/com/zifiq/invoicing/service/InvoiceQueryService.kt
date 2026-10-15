package com.zifiq.invoicing.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.Facets
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.json.Json
import com.zifiq.common.paging.FacetCount
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
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerType
import com.zifiq.invoicing.dto.BuyerBlock
import com.zifiq.invoicing.dto.InvoiceDelivery
import com.zifiq.invoicing.dto.InvoiceDetail
import com.zifiq.invoicing.dto.InvoiceLineDetail
import com.zifiq.invoicing.dto.InvoiceLinkRef
import com.zifiq.invoicing.dto.InvoiceLocation
import com.zifiq.invoicing.dto.InvoiceLookupRow
import com.zifiq.invoicing.dto.InvoiceRow
import com.zifiq.invoicing.dto.InvoiceTotals
import com.zifiq.invoicing.dto.InvoiceVat
import com.zifiq.invoicing.dto.IssuerBlock
import com.zifiq.invoicing.dto.LinkedDocuments
import com.zifiq.invoicing.dto.RegulatoryBlock
import com.zifiq.invoicing.dto.VoidedInvoiceRow
import com.zifiq.invoicing.dto.WaybillLink
import com.zifiq.invoicing.model.InvoiceLinesTable
import com.zifiq.invoicing.model.InvoiceWaybillLinksTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.documents.DocumentLinkProvider
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.settings.spi.CompanyProfileProvider
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Invoice history, detail, voided invoices, pickers, the PDF and global search (docs/08 §4.2–4.4,
 * §4.9, §9). Derived statuses (PARTIALLY_PAID, PAID, OVERDUE) are one SQL expression built per
 * request from the paid tolerance and today's date, so filters, facets, sorting and each row's
 * `displayStatus` can never disagree — nothing is computed in memory.
 */
@Service
class InvoiceQueryService(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
    private val documents: DocumentRegistry,
    private val pdfRenderer: PdfRenderer,
    private val companyProfile: CompanyProfileProvider,
    private val customers: CustomerDirectory,
) {
    // ---- History & search (§4.2) ----

    fun list(request: ListSearchRequest, extra: Op<Boolean>? = null): PageResult<InvoiceRow> = tx.execute {
        val d = Derived(support.tolerance(), support.today())
        val page = query(request, d, extra)
        page.result.withItems(toRows(page.result.items, d)).copy(
            summary = if (request.summary) summary(page.condition, d) else null,
            facets = facets(request, page, d).ifEmpty { null },
            meta = PageMeta(ignoredQualifiers = page.ignoredQualifiers),
        )
    }

    fun filterMetadata(): FilterMetadata = tx.execute { Derived(support.tolerance(), support.today()).filterSpec.metadata(support.filterContext()) }

    /** Voided invoices with their reason and replacement (§4.9). */
    fun voided(request: ListSearchRequest): PageResult<VoidedInvoiceRow> = tx.execute {
        val d = Derived(support.tolerance(), support.today())
        val sorted = if (request.sort.isEmpty()) request.copy(sort = listOf(com.zifiq.common.filter.SortField("voidedAt", "desc"))) else request
        val page = query(sorted, d, InvoicesTable.status eq "VOID")
        val rows = page.result.items
        val users = support.userNames(rows.mapNotNull { it[InvoicesTable.voidedByUserId]?.value })
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
            val all = joined().select(InvoicesTable.taxInclusiveAmount).where(page.condition).toList()
            mapOf("count" to all.size, "value" to InvoiceSupport.money(all.fold(BigDecimal.ZERO) { s, it -> s + it[InvoicesTable.taxInclusiveAmount] }))
        } else null
        page.result.withItems(items).copy(summary = summary, meta = PageMeta(ignoredQualifiers = page.ignoredQualifiers))
    }

    // ---- Detail (§4.3) ----

    fun detail(id: UUID): InvoiceDetail = tx.execute { detailIn(id) }

    // ---- Lookups (§9) ----

    /** Picker: `number · customer · balance`. Issued invoices only; [openOnly] keeps those with a balance. */
    fun lookup(q: String?, openOnly: Boolean, customerId: UUID?, limit: Int = 20): List<InvoiceLookupRow> = tx.execute {
        val d = Derived(support.tolerance(), support.today())
        val parsed = d.searchSpec.parse(q)
        val condition = listOfNotNull(
            visibility(),
            InvoicesTable.status eq "ISSUED",
            d.searchSpec.condition(parsed),
            customerId?.let { InvoicesTable.customerId eq it },
            if (openOnly) RawOp { +"("; +d.balance; +") > "; +d.toleranceSql } else null,
        ).allOf()!!
        val order = if (!parsed.isEmpty) d.searchSpec.rank(parsed) else listOf(InvoicesTable.issuedAt to SortOrder.DESC)
        joined().select(InvoicesTable.columns + listOf(d.balance, d.displayStatus)).where(condition)
            .orderBy(*order.toTypedArray()).limit(limit.coerceIn(1, 50)).map { row ->
                val balance = InvoiceSupport.money(row[d.balance] as BigDecimal)
                val number = row[InvoicesTable.number]!!
                InvoiceLookupRow(
                    id = row[InvoicesTable.id].value, number = number, customerId = row[InvoicesTable.customerId], customer = row[InvoicesTable.buyerName],
                    issued = row[InvoicesTable.issueDate], total = row[InvoicesTable.taxInclusiveAmount], balance = balance,
                    displayStatus = label(row[d.displayStatus] as String),
                    label = "$number · ${row[InvoicesTable.buyerName]} · ${InvoiceSupport.naira(balance)}",
                )
            }
    }

    /** Resolves a sale (TXN-…) or pro-forma (PF-…) reference to its invoice: the live one, else the latest. */
    fun lookupSource(ref: String): InvoiceRow = tx.execute {
        val d = Derived(support.tolerance(), support.today())
        val key = ref.trim().lowercase()
        if (key.isEmpty()) throw ApiErrors.validationFailed(mapOf("ref" to "must not be blank"))
        val condition = visibility() and ((InvoicesTable.sourceRef.lowerCase() eq key) or (InvoicesTable.proFormaRef.lowerCase() eq key))
        val row = joined().select(selection(d)).where(condition)
            .orderBy(RawExpression<Int>({ +"(case when "; +InvoicesTable.status; +" = 'VOID' then 1 else 0 end)" }) to SortOrder.ASC, InvoicesTable.createdAt to SortOrder.DESC)
            .firstOrNull() ?: throw ApiErrors.invoiceNotFound()
        toRows(listOf(row), d).single()
    }

    // ---- PDF (§4.4) ----

    data class PrintedInvoice(val fileName: String, val pdf: ByteArray)

    fun pdf(id: UUID): PrintedInvoice {
        val model = tx.execute {
            val detail = detailIn(id)
            val company = companyProfile.get()
            val issuer = detail.issuer
            val stamp = when (detail.displayStatus) {
                "Void" -> "Void"
                "Draft" -> "Draft — not a tax invoice"
                "Paid" -> "Paid"
                else -> null
            }
            mapOf(
                "number" to (detail.number ?: "DRAFT"),
                "title" to if (detail.status == "DRAFT") "Draft Invoice" else "Tax Invoice",
                "status" to detail.status,
                "logo" to company.logo?.let { "data:${company.logoContentType};base64," + Base64.getEncoder().encodeToString(it) },
                "issuer" to mapOf(
                    "tradingName" to (issuer?.tradingName ?: company.tradingName), "legalName" to issuer?.legalName, "address" to (issuer?.address ?: company.streetAddress),
                    "phone" to (issuer?.phone ?: company.phone), "email" to issuer?.email, "tin" to issuer?.tin, "vatRegistrationNumber" to issuer?.vatRegistrationNumber,
                    "locationName" to (issuer?.locationName ?: detail.showroom.name), "locationAddress" to issuer?.locationAddress,
                    "documentFooter" to (issuerFooter(id) ?: company.documentFooter),
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
                        "unit" to line.unit, "price" to InvoiceSupport.naira(line.unitPrice),
                        "discount" to if (line.discountAmount.signum() > 0) InvoiceSupport.naira(line.discountAmount) else "—",
                        "vat" to if (line.vatAmount.signum() > 0) "${line.vatRate.stripTrailingZeros().toPlainString()}% ${InvoiceSupport.naira(line.vatAmount)}" else vatWord(line.vatCategory),
                        "amount" to InvoiceSupport.naira(line.lineTotal),
                    )
                },
                "totals" to mapOf(
                    "lineExtension" to InvoiceSupport.naira(detail.totals.lineExtension), "discount" to InvoiceSupport.naira(detail.totals.discount),
                    "hasDiscount" to (detail.totals.discount.signum() > 0), "vat" to InvoiceSupport.naira(detail.totals.vat),
                    "total" to InvoiceSupport.naira(detail.totals.total), "paid" to InvoiceSupport.naira(detail.totals.paid),
                    "hasPaid" to (detail.totals.paid.signum() > 0), "credited" to InvoiceSupport.naira(detail.totals.credited),
                    "hasCredited" to (detail.totals.credited.signum() > 0), "balance" to InvoiceSupport.naira(detail.totals.balance),
                    "showBalance" to (detail.status == "ISSUED"),
                ),
                "vatLabel" to if (detail.vat.enabled) "VAT (${detail.vat.rate.stripTrailingZeros().toPlainString()}%)" else "VAT (not collected at this showroom)",
                "paymentTermsNote" to detail.paymentTermsNote,
                "duplicate" to (detail.number?.let(documents::isReprint) ?: false),
                "nrsFooter" to NRS_FOOTER,
                "printedAt" to LocalDateTime.now(support.zone()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
            )
        }
        val fileName = (model["number"] as String).let { if (it == "DRAFT") "draft-invoice-${id.toString().take(8)}" else it }
        return PrintedInvoice("$fileName.pdf", pdfRenderer.render("invoice", model))
    }

    // ---- Global search (§9) ----

    fun search(q: String, limit: Int): List<SearchHit> =
        list(ListSearchRequest(q = q, size = limit, summary = false)).items.map {
            SearchHit("invoice", it.id, it.number ?: "Draft (${it.sourceRef})",
                "${it.customer} · ${InvoiceSupport.naira(it.total)} · ${it.displayStatus}" + if (it.daysOverdue > 0) " ${it.daysOverdue}d" else "",
                it.number, "/invoicing/invoices/${it.id}")
        }

    // ---- Internals (caller's transaction) ----

    private class Page(val result: PageResult<ResultRow>, val condition: Op<Boolean>, val scope: Op<Boolean>, val search: Op<Boolean>?, val ignoredQualifiers: List<String>)

    private fun query(request: ListSearchRequest, d: Derived, extra: Op<Boolean>?): Page {
        val context = support.filterContext()
        val parsed = d.searchSpec.parse(request.q)
        val scope = listOfNotNull(visibility(), extra).allOf()!!
        val search = d.searchSpec.condition(parsed)
        val condition = listOfNotNull(scope, search, d.filterSpec.fromParams(request.params, context), d.filterSpec.fromJson(request.filter, context)).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) d.searchSpec.rank(parsed) + d.filterSpec.sort(emptyList(), context) else d.filterSpec.sort(request.sortParams, context)
        val result = Paging.fetch(joined().select(selection(d)).where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        return Page(result, condition, scope, search, parsed.ignoredQualifiers)
    }

    private fun facets(request: ListSearchRequest, page: Page, d: Derived): Map<String, List<FacetCount>> {
        val context = support.filterContext()
        return request.facets.take(Facets.MAX_FACETS).associateWith { key ->
            val others = request.params.filterKeys { it.substringBefore('.').removeSuffix("!").removeSuffix("Min").removeSuffix("Max") != key }
            val condition = listOfNotNull(page.scope, page.search, d.filterSpec.fromParams(others, context), d.filterSpec.fromJson(request.filter, context)).allOf()
            when (key) {
                "status" -> Facets.count(joined(), condition, d.displayStatus) { v -> v?.let(::label) }
                "locationId" -> Facets.count(joined(), condition, InvoicesTable.locationId) { v -> v?.let { support.locationNames(listOf(UUID.fromString(it)))[UUID.fromString(it)] } }
                "customerType" -> Facets.count(joined(), condition, InvoicesTable.buyerType) { v -> v?.let { runCatching { CustomerType.valueOf(it).label }.getOrDefault(it) } ?: "Walk-in" }
                "sourceType" -> Facets.count(joined(), condition, InvoicesTable.sourceType) { v -> if (v == "PRO_FORMA") "Pro-forma" else "POS sale" }
                "invoiceKind" -> Facets.count(joined(), condition, InvoicesTable.invoiceKind)
                else -> throw ApiErrors.invalidFilter(key, "Not a facet", listOf("status", "locationId", "customerType", "sourceType", "invoiceKind"))
            }
        }
    }

    private fun summary(condition: Op<Boolean>, d: Derived): Map<String, Any?> {
        val count = RawExpression<Long>({ +"count(*)" })
        val combined = RawExpression<BigDecimal>({ +"coalesce(sum(case when "; +InvoicesTable.status; +" = 'ISSUED' then "; +InvoicesTable.taxInclusiveAmount; +" end), 0)" })
        val outstanding = RawExpression<BigDecimal>({ +"coalesce(sum(case when "; +InvoicesTable.status; +" = 'ISSUED' and ("; +d.balance; +") > "; +d.toleranceSql; +" then ("; +d.balance; +") end), 0)" })
        val overdue = RawExpression<BigDecimal>({ +"coalesce(sum(case when ("; +d.displayStatus; +") = 'OVERDUE' then ("; +d.balance; +") end), 0)" })
        val fromProForma = RawExpression<Long>({ +"count(*) filter (where "; +InvoicesTable.sourceType; +" = 'PRO_FORMA')" })
        val fromPos = RawExpression<Long>({ +"count(*) filter (where "; +InvoicesTable.sourceType; +" = 'POS_SALE')" })
        val withWaybills = RawExpression<Long>({ +"count(*) filter (where "; +d.hasWaybills; +")" })
        val row = joined().select(count, combined, outstanding, overdue, fromProForma, fromPos, withWaybills).where(condition).single()
        fun money(e: Expression<*>) = InvoiceSupport.money(row[e] as BigDecimal)
        fun long(e: Expression<*>) = (row[e] as Number).toLong()
        return mapOf(
            "invoicesOnFile" to long(count), "combinedValue" to money(combined), "outstanding" to money(outstanding), "overdue" to money(overdue),
            "fromProForma" to long(fromProForma), "fromPos" to long(fromPos), "withWaybills" to long(withWaybills),
        )
    }

    private fun detailIn(id: UUID): InvoiceDetail {
        val d = Derived(support.tolerance(), support.today())
        val row = joined().select(selection(d)).where { (InvoicesTable.id eq id) and (InvoicesTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.invoiceNotFound()
        support.assertVisible(row[InvoicesTable.locationId].value)
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
        val users = support.userNames(listOfNotNull(row[InvoicesTable.issuedByUserId]?.value, row[InvoicesTable.voidedByUserId]?.value))
        val linkedNumbers = numbers(listOfNotNull(row[InvoicesTable.reissueOfInvoiceId], row[InvoicesTable.replacedByInvoiceId]))
        val issuer = issuerBlock(row)
        val customerCode = row[InvoicesTable.customerId]?.let { runCatching { customers.get(it).code }.getOrNull() }
        val balance = InvoiceSupport.money(row[d.balance] as BigDecimal)
        val status = row[InvoicesTable.status]
        return InvoiceDetail(
            id = id, number = row[InvoicesTable.number], status = status, displayStatus = label(row[d.displayStatus] as String),
            invoiceTypeCode = row[InvoicesTable.invoiceTypeCode], invoiceKind = row[InvoicesTable.invoiceKind],
            issueDate = row[InvoicesTable.issueDate], issueTime = row[InvoicesTable.issueTime], dueDate = row[InvoicesTable.dueDate],
            paymentTermsDays = row[InvoicesTable.paymentTermsDays], paymentTermsNote = row[InvoicesTable.paymentTermsNote],
            daysOverdue = (row[d.daysOverdue] as Number).toInt(),
            issuedBy = row[InvoicesTable.issuedByUserId]?.value?.let(users::get), issuedAt = row[InvoicesTable.issuedAt], createdAt = row[InvoicesTable.createdAt],
            showroom = InvoiceLocation(row[InvoicesTable.locationId].value, row.getOrNull(LocationsTable.name)),
            issuer = issuer,
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
                balance = if (status == "ISSUED") balance else BigDecimal.ZERO.setScale(2), pendingConfirmation = BigDecimal.ZERO.setScale(2),
            ),
            vat = InvoiceVat(row[InvoicesTable.vatEnabled], row[InvoicesTable.locationVatRate]),
            linked = LinkedDocuments(
                sourceType = row[InvoicesTable.sourceType], sourceRef = row[InvoicesTable.sourceRef], proForma = row[InvoicesTable.proFormaRef],
                waybills = waybills, payments = emptyList(), creditNotes = emptyList(),
                reissueOf = row[InvoicesTable.reissueOfInvoiceId]?.let { InvoiceLinkRef(it, linkedNumbers[it]) },
                replacedBy = row[InvoicesTable.replacedByInvoiceId]?.let { InvoiceLinkRef(it, linkedNumbers[it]) },
            ),
            delivery = InvoiceDelivery(delivery(row), null, row[InvoicesTable.buyerEmail]),
            regulatory = RegulatoryBlock(row[InvoicesTable.irn], null, null, "Not in this phase", row[InvoicesTable.nrsSubmissionStatus]),
            voidReason = row[InvoicesTable.voidReason], voidedBy = row[InvoicesTable.voidedByUserId]?.value?.let(users::get), voidedAt = row[InvoicesTable.voidedAt],
        )
    }

    private fun toRows(rows: List<ResultRow>, d: Derived): List<InvoiceRow> {
        val ids = rows.map { it[InvoicesTable.id].value }
        val waybills = if (ids.isEmpty()) emptyMap()
        else InvoiceWaybillLinksTable.select(InvoiceWaybillLinksTable.invoiceId, InvoiceWaybillLinksTable.waybillRef)
            .where { InvoiceWaybillLinksTable.invoiceId inList ids }.orderBy(InvoiceWaybillLinksTable.waybillDate).toList()
            .groupBy({ it[InvoiceWaybillLinksTable.invoiceId].value }, { it[InvoiceWaybillLinksTable.waybillRef] })
        return rows.map { row ->
            val status = row[InvoicesTable.status]
            InvoiceRow(
                id = row[InvoicesTable.id].value, number = row[InvoicesTable.number], status = status, displayStatus = label(row[d.displayStatus] as String),
                issued = if (status == "DRAFT") null else row[InvoicesTable.issueDate], due = row[InvoicesTable.dueDate],
                customerId = row[InvoicesTable.customerId], customer = row[InvoicesTable.buyerName], customerType = row[InvoicesTable.buyerType],
                locationId = row[InvoicesTable.locationId].value, showroom = row.getOrNull(LocationsTable.name),
                sourceType = row[InvoicesTable.sourceType], source = if (row[InvoicesTable.sourceType] == "PRO_FORMA") "Pro-forma" else "POS sale",
                sourceRef = row[InvoicesTable.sourceRef], proForma = row[InvoicesTable.proFormaRef],
                waybills = waybills[row[InvoicesTable.id].value].orEmpty(),
                total = row[InvoicesTable.taxInclusiveAmount], paid = row[InvoicesTable.amountPaid], credited = row[InvoicesTable.amountCredited],
                balance = if (status == "ISSUED") InvoiceSupport.money(row[d.balance] as BigDecimal) else BigDecimal.ZERO.setScale(2),
                daysOverdue = (row[d.daysOverdue] as Number).toInt(), delivery = delivery(row),
            )
        }
    }

    /** Until e-mail delivery arrives (slice 2): no address → "No email on file", else "Not sent". */
    private fun delivery(row: ResultRow): String = if (row[InvoicesTable.buyerEmail].isNullOrBlank()) "No email on file" else "Not sent"

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

    private fun visibility(): Op<Boolean> = listOfNotNull(
        InvoicesTable.tenantId eq support.tenantId(),
        support.visibleLocations()?.let { InvoicesTable.locationId inList it },
    ).allOf()!!

    private fun joined() = InvoicesTable.join(LocationsTable, JoinType.LEFT, InvoicesTable.locationId, LocationsTable.id)

    private fun selection(d: Derived): List<Expression<*>> = InvoicesTable.columns + listOf(LocationsTable.name, d.balance, d.displayStatus, d.daysOverdue)

    /**
     * The derived-status expressions for one request. Tolerance and today are rendered as SQL
     * literals from a BigDecimal and a LocalDate, so nothing user-supplied reaches the string.
     */
    private class Derived(tolerance: BigDecimal, today: LocalDate) {
        val toleranceSql: String = tolerance.toPlainString()
        private val todaySql = "date '$today'"

        val balance = RawExpression<BigDecimal>({ +"("; +InvoicesTable.taxInclusiveAmount; +" - "; +InvoicesTable.amountPaid; +" - "; +InvoicesTable.amountCredited; +")" })

        val displayStatus = RawExpression<String>({
            +"(case when "; +InvoicesTable.status; +" = 'DRAFT' then 'DRAFT' when "; +InvoicesTable.status; +" = 'VOID' then 'VOID' when ("
            +balance; +") <= "; +toleranceSql; +" then 'PAID' when "; +InvoicesTable.dueDate; +" < "; +todaySql; +" then 'OVERDUE' when "
            +InvoicesTable.amountPaid; +" + "; +InvoicesTable.amountCredited; +" > 0 then 'PARTIALLY_PAID' else 'ISSUED' end)"
        })

        val daysOverdue = RawExpression<Int>({
            +"(case when "; +InvoicesTable.status; +" = 'ISSUED' and ("; +balance; +") > "; +toleranceSql; +" and "; +InvoicesTable.dueDate; +" < "; +todaySql
            +" then ("; +todaySql; +" - "; +InvoicesTable.dueDate; +") else 0 end)"
        })

        val hasWaybills = RawExpression<Boolean>({ +"exists (select 1 from invoice_waybill_links w where w.invoice_id = "; +InvoicesTable.id; +")" })

        val filterSpec = FilterSpec(
            fields = listOf(
                FilterField("status", "Status", FilterType.ENUM, displayStatus, STATUSES.map { FilterOption(it, label(it)) }, facetable = true),
                FilterField("customerId", "Customer", FilterType.UUID, InvoicesTable.customerId, aliases = setOf("customer"), lookup = "/api/v1/customers/lookup"),
                FilterField("customerType", "Customer type", FilterType.ENUM, InvoicesTable.buyerType,
                    CustomerType.entries.map { FilterOption(it.name, it.label) }, facetable = true),
                FilterField("locationId", "Showroom", FilterType.LOCATION, InvoicesTable.locationId, aliases = setOf("showroom"), facetable = true),
                FilterField("sourceType", "Source", FilterType.ENUM, InvoicesTable.sourceType,
                    listOf(FilterOption("POS_SALE", "POS sale"), FilterOption("PRO_FORMA", "Pro-forma")), aliases = setOf("source"), facetable = true),
                FilterField("invoiceKind", "Invoice kind", FilterType.ENUM, InvoicesTable.invoiceKind, listOf(FilterOption("B2B", "B2B"), FilterOption("B2C", "B2C")), facetable = true),
                FilterField("issueDate", "Issue date", FilterType.DATE, InvoicesTable.issueDate, sortable = true),
                FilterField("dueDate", "Due date", FilterType.DATE, InvoicesTable.dueDate, sortable = true),
                FilterField("issuedAt", "Issued", FilterType.DATETIME, InvoicesTable.issuedAt, sortable = true),
                FilterField("voidedAt", "Voided", FilterType.DATETIME, InvoicesTable.voidedAt, sortable = true),
                FilterField("number", "Number", FilterType.TEXT, InvoicesTable.number, sortable = true),
                FilterField("buyerName", "Customer name", FilterType.TEXT, InvoicesTable.buyerName, sortable = true),
                FilterField("total", "Total", FilterType.MONEY, InvoicesTable.taxInclusiveAmount, sortable = true),
                FilterField("paid", "Paid", FilterType.MONEY, InvoicesTable.amountPaid),
                FilterField("balance", "Balance", FilterType.MONEY, balance, sortable = true),
                FilterField("daysOverdue", "Days overdue", FilterType.NUMBER, daysOverdue, sortable = true),
                FilterField("issuedBy", "Issued by", FilterType.UUID, InvoicesTable.issuedByUserId),
                FilterField("voidedBy", "Voided by", FilterType.UUID, InvoicesTable.voidedByUserId),
                FilterField("correspondentId", "Correspondent", FilterType.UUID, InvoicesTable.correspondentId, lookup = "/api/v1/customers/correspondents/lookup"),
                FilterField("vatEnabled", "VAT collected", FilterType.BOOLEAN, InvoicesTable.vatEnabled),
                FilterField("hasWaybills", "Has waybills", FilterType.BOOLEAN, hasWaybills),
                FilterField("hasReplacement", "Reissued", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +InvoicesTable.replacedByInvoiceId; +" is not null)" })),
            ),
            primaryDateKey = "issueDate",
            defaultSort = listOf("issuedAt" to SortOrder.DESC),
            tiebreaker = InvoicesTable.id,
        )

        val searchSpec = SearchSpec(
            fields = listOf(
                SearchField(InvoicesTable.number, SearchKind.REFERENCE, "Number"),
                SearchField(InvoicesTable.sourceRef, SearchKind.REFERENCE, "Source reference"),
                SearchField(InvoicesTable.proFormaRef, SearchKind.REFERENCE, "Pro-forma"),
                SearchField(InvoicesTable.buyerName, SearchKind.TRIGRAM, "Customer"),
                SearchField(InvoicesTable.buyerPhone, SearchKind.PHONE, "Phone"),
                SearchField(InvoicesTable.buyerTin, SearchKind.CODE, "TIN"),
                SearchField(InvoicesTable.buyerEmail, SearchKind.CODE, "E-mail"),
                SearchField(RawExpression<String>({ +"invoices.search_vector" }), SearchKind.FULLTEXT, "Text"),
                SearchField(InvoicesTable.taxInclusiveAmount, SearchKind.AMOUNT, "Total"),
            ),
            qualifiers = filterSpec,
        )
    }

    companion object {
        val STATUSES = listOf("DRAFT", "ISSUED", "PARTIALLY_PAID", "PAID", "OVERDUE", "VOID")
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

        const val NRS_FOOTER = "Issued under ZifIQ internal digital invoicing. This document is not transmitted to the NRS/FIRS Merchant-Buyer " +
            "Solution and carries no Invoice Reference Number or cryptographic stamp in this phase."

        fun label(status: String): String = when (status) {
            "DRAFT" -> "Draft"
            "ISSUED" -> "Issued"
            "PARTIALLY_PAID" -> "Partially Paid"
            "PAID" -> "Paid"
            "OVERDUE" -> "Overdue"
            "VOID" -> "Void"
            else -> status
        }

        private fun vatWord(category: String) = when (category) {
            "ZERO_RATED" -> "Zero-rated"
            "EXEMPT" -> "Exempt"
            else -> "—"
        }
    }
}

/** Lets the document registry link invoices to their PDF. */
@Component
class InvoicingDocumentLinks : DocumentLinkProvider {
    override val sourceModule = InvoiceSupport.SOURCE_MODULE
    override fun pdfUrl(docType: DocumentType, sourceId: UUID): String? =
        if (docType == DocumentType.INVOICE) "/api/v1/invoicing/invoices/$sourceId/invoice.pdf" else null
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
