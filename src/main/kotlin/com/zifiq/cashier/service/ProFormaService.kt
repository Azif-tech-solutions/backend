package com.zifiq.cashier.service

import com.zifiq.account.spi.VatRates
import com.zifiq.account.spi.VatSetting
import com.zifiq.cashier.dto.ProFormaConvertRequest
import com.zifiq.cashier.dto.ProFormaLineInput
import com.zifiq.cashier.dto.ProFormaLineView
import com.zifiq.cashier.dto.ProFormaRequest
import com.zifiq.cashier.dto.ProFormaRow
import com.zifiq.cashier.dto.ProFormaTotals
import com.zifiq.cashier.dto.ProFormaView
import com.zifiq.cashier.dto.ProspectInput
import com.zifiq.cashier.dto.RejectRequest
import com.zifiq.cashier.model.ProFormaLinesTable
import com.zifiq.cashier.model.ProFormasTable
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.allOf
import com.zifiq.common.tax.LineAmounts
import com.zifiq.common.tax.VatCategoriesTable
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.PriceListRepository
import com.zifiq.inventory.repository.StockSql
import com.zifiq.inventory.service.StockAvailability
import com.zifiq.inventory.service.StockLocations
import com.zifiq.inventory.service.StockReservations
import com.zifiq.invoicing.spi.InvoiceIssuer
import com.zifiq.invoicing.spi.InvoiceLineRequest
import com.zifiq.invoicing.spi.InvoiceRef
import com.zifiq.invoicing.spi.InvoiceRequest
import com.zifiq.invoicing.spi.Receivables
import com.zifiq.invoicing.spi.SourceType
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Filters for the pro-forma list (docs/09 §4.4). */
data class ProFormaFilter(
    val status: String? = null,
    val locationId: UUID? = null,
    val customerId: UUID? = null,
    val ref: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

private data class PricedPfLine(
    val lineNo: Int, val stockItemId: UUID, val name: String, val unit: String, val vatCategoryCode: String,
    val amounts: LineAmounts, val availabilityWarning: Boolean,
)

/**
 * Pro-forma invoices (docs/09 §4.4): a quotation that commits no stock until the customer confirms,
 * then converts into an order (a `PRO_FORMA_ORDER` sale, fully reserved, invoiced on account) or is
 * loaded into a till sale. Line discounts above the ceiling need Management approval. Caller's
 * transaction, through the tenant-scoped executor.
 */
@Service
class ProFormaService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val prices: PriceListRepository,
    private val vatRates: VatRates,
    private val customers: CustomerDirectory,
    private val availability: StockAvailability,
    private val reservations: StockReservations,
    private val stockLocations: StockLocations,
    private val invoices: InvoiceIssuer,
    private val receivables: Receivables,
    private val sequences: DocumentSequenceService,
    private val documents: DocumentRegistry,
    private val pdfRenderer: com.zifiq.platform.documents.PdfRenderer,
    private val companyProfile: com.zifiq.settings.spi.CompanyProfileProvider,
) {
    fun create(request: ProFormaRequest, send: Boolean): ProFormaView = tx.execute {
        support.assertAccess(request.locationId!!)
        stockLocations.requireTrading(request.locationId)
        val customer = request.customerId?.let(::sellableCustomer)
        if (customer == null && request.prospect?.name.isNullOrBlank()) throw ApiErrors.validationFailed(mapOf("prospect" to "give a registered customer or a prospect name"))
        val issueDate = request.issueDate ?: support.today()
        if (issueDate.isAfter(support.today())) throw ApiErrors.validationFailed(mapOf("issueDate" to "can't be in the future"))
        val validUntil = request.validUntil ?: issueDate.plusDays(support.settings().int(CashierSettings.PRO_FORMA_VALIDITY_DAYS).toLong())
        if (validUntil.isBefore(issueDate)) throw ApiErrors.validationFailed(mapOf("validUntil" to "can't be before the issue date"))
        val dealer = customer?.type == CustomerType.AUTHORISED_DEALER
        val vat = vatRates.forLocation(request.locationId, issueDate)
        val lines = price(request.locationId, dealer, request.lines, issueDate, vat)
        val totals = totals(lines, vat)
        val needsApproval = overCeiling(totals) && !support.can(SaleDiscounts.APPROVE)
        val ref = sequences.next("PF")
        val id = ProFormasTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[ProFormasTable.ref] = ref
            it[status] = if (needsApproval) "PENDING_APPROVAL" else if (send) "SENT" else "DRAFT"
            it[locationId] = request.locationId
            it[customerId] = customer?.id
            it[prospectName] = request.prospect?.name?.trim()
            it[prospectAddress] = request.prospect?.address?.trim()?.ifEmpty { null }
            it[prospectContact] = request.prospect?.contact?.trim()?.ifEmpty { null }
            it[prospectEmail] = request.prospect?.email?.trim()?.ifEmpty { null }
            it[customerName] = (customer?.name ?: request.prospect?.name)!!.take(200)
            it[customerType] = customer?.type?.name
            it[priceBasis] = if (dealer) "DEALER" else "RETAIL"
            it[ProFormasTable.issueDate] = issueDate
            it[ProFormasTable.validUntil] = validUntil
            it[paymentTerms] = request.paymentTerms?.trim()?.ifEmpty { null }
            it[deliveryTerms] = request.deliveryTerms?.trim()?.ifEmpty { null }
            it[note] = request.note?.trim()?.ifEmpty { null }
            writeTotals(it, totals)
            it[preparedByUserId] = support.userId()
            it[sentAt] = if (!needsApproval && send) Instant.now() else null
            it[createdAt] = Instant.now()
            it[updatedAt] = Instant.now()
        }.value
        writeLines(id, lines)
        if (!needsApproval && send) register(id, ref, request.locationId, customer?.id, totals.total)
        support.record("cashier.pro-forma.created", "pro-forma", id, ref, "$ref for ${(customer?.name ?: request.prospect?.name)}", emptyMap(), request.locationId)
        view(row(id))
    }

    fun update(id: UUID, request: ProFormaRequest): ProFormaView = tx.execute {
        val pf = editable(id)
        val customer = request.customerId?.let(::sellableCustomer)
        val issueDate = request.issueDate ?: pf[ProFormasTable.issueDate]
        val validUntil = request.validUntil ?: issueDate.plusDays(support.settings().int(CashierSettings.PRO_FORMA_VALIDITY_DAYS).toLong())
        if (validUntil.isBefore(issueDate)) throw ApiErrors.validationFailed(mapOf("validUntil" to "can't be before the issue date"))
        val dealer = customer?.type == CustomerType.AUTHORISED_DEALER
        val vat = vatRates.forLocation(pf[ProFormasTable.locationId], issueDate)
        val lines = price(pf[ProFormasTable.locationId], dealer, request.lines, issueDate, vat)
        val totals = totals(lines, vat)
        val needsApproval = overCeiling(totals) && !support.can(SaleDiscounts.APPROVE)
        ProFormaLinesTable.deleteWhere { proFormaId eq id }
        writeLines(id, lines)
        ProFormasTable.update({ ProFormasTable.id eq id }) {
            it[status] = if (needsApproval) "PENDING_APPROVAL" else "DRAFT"
            it[customerId] = customer?.id
            it[prospectName] = request.prospect?.name?.trim()
            it[customerName] = (customer?.name ?: request.prospect?.name ?: pf[ProFormasTable.customerName]).take(200)
            it[customerType] = customer?.type?.name
            it[priceBasis] = if (dealer) "DEALER" else "RETAIL"
            it[ProFormasTable.issueDate] = issueDate
            it[ProFormasTable.validUntil] = validUntil
            it[paymentTerms] = request.paymentTerms?.trim()?.ifEmpty { null }
            it[deliveryTerms] = request.deliveryTerms?.trim()?.ifEmpty { null }
            it[note] = request.note?.trim()?.ifEmpty { null }
            writeTotals(it, totals)
            it[updatedAt] = Instant.now()
        }
        view(row(id))
    }

    fun send(id: UUID): ProFormaView = tx.execute {
        val pf = visible(id, forUpdate = true)
        if (pf[ProFormasTable.status] !in setOf("DRAFT", "SENT")) throw ApiErrors.proFormaNotEditable(pf[ProFormasTable.status])
        if (pf[ProFormasTable.status] == "DRAFT") {
            ProFormasTable.update({ ProFormasTable.id eq id }) { it[status] = "SENT"; it[sentAt] = Instant.now(); it[updatedAt] = Instant.now() }
            register(id, pf[ProFormasTable.ref], pf[ProFormasTable.locationId], pf[ProFormasTable.customerId], pf[ProFormasTable.total])
            support.record("cashier.pro-forma.sent", "pro-forma", id, pf[ProFormasTable.ref], "${pf[ProFormasTable.ref]} sent", emptyMap(), pf[ProFormasTable.locationId])
        }
        view(row(id))
    }

    fun decline(id: UUID, request: RejectRequest): ProFormaView = tx.execute {
        val pf = visible(id, forUpdate = true)
        if (pf[ProFormasTable.status] != "SENT") throw ApiErrors.proFormaNotEditable(pf[ProFormasTable.status])
        ProFormasTable.update({ ProFormasTable.id eq id }) { it[status] = "DECLINED"; it[declineReason] = request.reason!!.trim().take(300); it[decidedAt] = Instant.now(); it[updatedAt] = Instant.now() }
        support.record("cashier.pro-forma.declined", "pro-forma", id, pf[ProFormasTable.ref], "${pf[ProFormasTable.ref]} declined", emptyMap(), pf[ProFormasTable.locationId])
        view(row(id))
    }

    fun cancel(id: UUID, request: RejectRequest): ProFormaView = tx.execute {
        val pf = visible(id, forUpdate = true)
        if (pf[ProFormasTable.status] !in setOf("DRAFT", "PENDING_APPROVAL", "SENT")) throw ApiErrors.proFormaNotEditable(pf[ProFormasTable.status])
        ProFormasTable.update({ ProFormasTable.id eq id }) { it[status] = "CANCELLED"; it[declineReason] = request.reason!!.trim().take(300); it[decidedAt] = Instant.now(); it[updatedAt] = Instant.now() }
        support.record("cashier.pro-forma.cancelled", "pro-forma", id, pf[ProFormasTable.ref], "${pf[ProFormasTable.ref]} cancelled", emptyMap(), pf[ProFormasTable.locationId])
        view(row(id))
    }

    fun approveDiscount(id: UUID): ProFormaView = tx.execute {
        val pf = pendingDiscount(id)
        ProFormasTable.update({ ProFormasTable.id eq id }) { it[status] = "DRAFT"; it[discountApprovedByUserId] = support.userId(); it[updatedAt] = Instant.now() }
        support.record("cashier.pro-forma.discount-approved", "pro-forma", id, pf[ProFormasTable.ref], "${pf[ProFormasTable.ref]} discount approved", emptyMap(), pf[ProFormasTable.locationId])
        view(row(id))
    }

    fun rejectDiscount(id: UUID, request: RejectRequest): ProFormaView = tx.execute {
        val pf = pendingDiscount(id)
        ProFormasTable.update({ ProFormasTable.id eq id }) { it[status] = "DRAFT"; it[declineReason] = request.reason!!.trim().take(300); it[updatedAt] = Instant.now() }
        support.record("cashier.pro-forma.discount-rejected", "pro-forma", id, pf[ProFormasTable.ref], "${pf[ProFormasTable.ref]} discount rejected: reduce it", emptyMap(), pf[ProFormasTable.locationId])
        view(row(id))
    }

    fun reissue(id: UUID): ProFormaView = tx.execute {
        val pf = visible(id)
        val lines = ProFormaLinesTable.selectAll().where { ProFormaLinesTable.proFormaId eq id }.orderBy(ProFormaLinesTable.lineNo).map {
            ProFormaLineInput(it[ProFormaLinesTable.stockItemId], it[ProFormaLinesTable.quantity], it[ProFormaLinesTable.lineDiscount])
        }
        create(ProFormaRequest(pf[ProFormasTable.locationId], pf[ProFormasTable.customerId],
            if (pf[ProFormasTable.customerId] == null) ProspectInput(pf[ProFormasTable.prospectName] ?: "Prospect", pf[ProFormasTable.prospectAddress], pf[ProFormasTable.prospectContact], pf[ProFormasTable.prospectEmail]) else null,
            null, null, pf[ProFormasTable.paymentTerms], pf[ProFormasTable.deliveryTerms], pf[ProFormasTable.note], lines), send = false)
    }

    /** Convert a confirmed pro-forma to an order: a PRO_FORMA_ORDER sale, fully reserved, invoiced on account. */
    fun convert(id: UUID, request: ProFormaConvertRequest): ProFormaView = tx.execute {
        val pf = visible(id, forUpdate = true)
        if (pf[ProFormasTable.status] != "SENT") throw ApiErrors.proFormaNotEditable(pf[ProFormasTable.status])
        if (pf[ProFormasTable.validUntil].isBefore(support.today())) throw ApiErrors.proFormaExpired()
        val customerId = pf[ProFormasTable.customerId] ?: throw ApiErrors.prospectMustRegister()
        val customer = sellableCustomer(customerId)
        val locationId = pf[ProFormasTable.locationId]
        val lines = ProFormaLinesTable.selectAll().where { ProFormaLinesTable.proFormaId eq id }.orderBy(ProFormaLinesTable.lineNo).toList()
        val total = pf[ProFormasTable.total]

        // Credit: the whole order is on account until paid.
        val outstanding = receivables.outstandingFor(customerId)
        if (outstanding + total > customer.creditLimit) {
            val reason = request.creditOverrideReason?.trim()?.ifEmpty { null }
            if (reason == null || !support.can("cashier.credit.override")) throw ApiErrors.creditLimitExceeded(customer.creditLimit, outstanding, total)
        }

        // Stock: available across the showroom per item, then reserve everything.
        val wanted = lines.groupBy({ it[ProFormaLinesTable.stockItemId] }, { it[ProFormaLinesTable.quantity] }).mapValues { (_, q) -> q.fold(BigDecimal.ZERO, BigDecimal::add) }
        val have = availability.available(locationId, wanted.keys, lock = true)
        val short = wanted.filter { (itemId, qty) -> have.getValue(itemId) < qty }
        if (short.isNotEmpty()) throw ApiErrors.insufficientStockForSale(short.mapKeys { (itemId, _) -> itemId.toString() }.mapValues { "not enough available" })

        val txnRef = sequences.next("TXN")
        val now = Instant.now()
        val saleId = SalesTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[ref] = txnRef
            it[channel] = "PRO_FORMA_ORDER"
            it[status] = "COMPLETED"
            it[version] = 0
            it[SalesTable.locationId] = locationId
            it[cashierUserId] = support.userId()
            it[proFormaId] = id
            it[SalesTable.customerId] = customerId
            it[customerCode] = customer.code
            it[customerName] = customer.name
            it[customerType] = customer.type.name
            it[priceBasis] = pf[ProFormasTable.priceBasis]
            it[grossAmount] = pf[ProFormasTable.grossAmount]
            it[lineDiscountTotal] = pf[ProFormasTable.discountTotal]
            it[instantDiscountTotal] = BigDecimal.ZERO
            it[netAmount] = pf[ProFormasTable.netAmount]
            it[vatEnabled] = pf[ProFormasTable.vatEnabled]
            it[vatRate] = pf[ProFormasTable.vatRate]
            it[vatAmount] = pf[ProFormasTable.vatAmount]
            it[SalesTable.total] = total
            it[paidNow] = BigDecimal.ZERO
            it[onAccount] = total
            it[supplyStatus] = "NOT_SUPPLIED"
            it[saleDate] = support.today()
            it[startedAt] = now
            it[updatedAt] = now
            it[completedAt] = now
        }.value
        lines.forEach { l ->
            val saleLineId = SaleLinesTable.insertAndGetId {
                it[tenantId] = support.tenantId()
                it[SaleLinesTable.saleId] = saleId
                it[lineNo] = l[ProFormaLinesTable.lineNo]
                it[stockItemId] = l[ProFormaLinesTable.stockItemId]
                it[itemCode] = ""
                it[itemName] = l[ProFormaLinesTable.itemName]
                it[unitName] = l[ProFormaLinesTable.unitName]
                it[quantity] = l[ProFormaLinesTable.quantity]
                it[unitPrice] = l[ProFormaLinesTable.unitPrice]
                it[priceBasis] = "PRO_FORMA"
                it[lineDiscount] = l[ProFormaLinesTable.lineDiscount]
                it[instantDiscountShare] = BigDecimal.ZERO
                it[lineNet] = l[ProFormaLinesTable.lineNet]
                it[vatCategoryCode] = "STANDARD_RATE"
                it[vatRate] = l[ProFormaLinesTable.vatRate]
                it[vatAmount] = l[ProFormaLinesTable.vatAmount]
                it[lineTotal] = l[ProFormaLinesTable.lineTotal]
                it[supplyNowQty] = BigDecimal.ZERO
                it[suppliedQty] = BigDecimal.ZERO
            }.value
            reservations.reserve(support.tenantId(), locationId, l[ProFormaLinesTable.stockItemId], CashierSupport.SOURCE_MODULE, "SALE_LINE", saleLineId, txnRef, l[ProFormaLinesTable.quantity])
        }

        val invoiceReq = InvoiceRequest(
            sourceType = SourceType.PRO_FORMA, sourceId = id, sourceRef = pf[ProFormasTable.ref], sourceDate = support.today(), proFormaRef = pf[ProFormasTable.ref],
            locationId = locationId, customerId = customerId, paymentTermsDays = request.paymentTermsDays, paymentTermsNote = request.paymentTermsNote,
            lines = lines.map { InvoiceLineRequest(it[ProFormaLinesTable.stockItemId], it[ProFormaLinesTable.quantity], it[ProFormaLinesTable.unitPrice], it[ProFormaLinesTable.lineDiscount]) },
        )
        val invoice: InvoiceRef = if (request.issueAs!!.uppercase() == "ISSUED") invoices.issue(invoiceReq) else invoices.createDraft(invoiceReq)
        SalesTable.update({ SalesTable.id eq saleId }) { it[invoiceId] = invoice.id; it[invoiceNumber] = invoice.number }
        ProFormasTable.update({ ProFormasTable.id eq id }) { it[status] = "CONVERTED"; it[convertedSaleId] = saleId; it[decidedAt] = now; it[updatedAt] = now }
        support.record("cashier.pro-forma.converted", "pro-forma", id, pf[ProFormasTable.ref], "${pf[ProFormasTable.ref]} converted to $txnRef", mapOf("saleId" to saleId), locationId)
        view(row(id))
    }

    fun get(id: UUID): ProFormaView = tx.execute { view(visible(id)) }

    fun pdf(id: UUID): SaleDocument = tx.execute {
        val view = view(visible(id)).also(com.zifiq.platform.directory.Refs::resolve)
        val company = companyProfile.get()
        val model = mapOf(
            "pf" to mapOf("ref" to view.ref, "customerName" to view.customerName, "issueDate" to view.issueDate.toString(), "validUntil" to view.validUntil.toString(),
                "paymentTerms" to view.paymentTerms, "deliveryTerms" to view.deliveryTerms),
            "company" to company, "logo" to company.logoDataUri(), "showroom" to view.location.name, "preparedBy" to view.preparedBy.name,
            "lines" to view.lines.map { mapOf("no" to it.lineNo, "name" to it.name, "quantity" to it.quantity.stripTrailingZeros().toPlainString(), "unit" to it.unit,
                "price" to Money.naira(it.unitPrice), "discount" to (if (it.lineDiscount.signum() > 0) Money.naira(it.lineDiscount) else "—"), "amount" to Money.naira(it.lineTotal)) },
            "totals" to mapOf("gross" to Money.naira(view.totals.gross), "discount" to Money.naira(view.totals.discount), "hasDiscount" to (view.totals.discount.signum() > 0),
                "vat" to Money.naira(view.totals.vat), "total" to Money.naira(view.totals.total)),
            "vatLabel" to if (view.totals.vatEnabled) "VAT (${view.totals.vatRate.stripTrailingZeros().toPlainString()}%)" else "VAT (not collected)",
            "printedAt" to java.time.LocalDateTime.now(support.zone()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
        )
        SaleDocument("${view.ref}.pdf", pdfRenderer.render("pro-forma", model))
    }

    fun list(filter: ProFormaFilter, paging: PageRequest): PageResult<ProFormaRow> = tx.execute {
        val refU = filter.ref?.trim()?.uppercase()?.ifEmpty { null }
        val condition = listOfNotNull(
            ProFormasTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { ProFormasTable.locationId inList it },
            filter.status?.let { ProFormasTable.status eq it.uppercase() },
            filter.locationId?.let { ProFormasTable.locationId eq it },
            filter.customerId?.let { ProFormasTable.customerId eq it },
            filter.from?.let { ProFormasTable.issueDate greaterEq it },
            filter.to?.let { ProFormasTable.issueDate lessEq it },
            refU?.let { ProFormasTable.ref eq it },
        ).allOf()!!
        val page = Paging.fetch(ProFormasTable.selectAll().where(condition).orderBy(ProFormasTable.issueDate to SortOrder.DESC, ProFormasTable.id to SortOrder.DESC), paging) { it }
        page.withItems(page.items.map { pf ->
            val items = ProFormaLinesTable.select(ProFormaLinesTable.id).where { ProFormaLinesTable.proFormaId eq pf[ProFormasTable.id].value }.count().toInt()
            ProFormaRow(pf[ProFormasTable.id].value, pf[ProFormasTable.ref], pf[ProFormasTable.status], Ref.location(pf[ProFormasTable.locationId]),
                ref(pf[ProFormasTable.customerId], Ref::customer), pf[ProFormasTable.customerName], pf[ProFormasTable.issueDate], pf[ProFormasTable.validUntil],
                items, pf[ProFormasTable.total], UserRef(pf[ProFormasTable.preparedByUserId]), ref(pf[ProFormasTable.convertedSaleId], Ref::sale))
        })
    }

    /** Daily job: SENT pro-formas past their validity become EXPIRED. Returns how many. Visible for tests. */
    fun expireDue(): Int = ProFormasTable.update({
        (ProFormasTable.tenantId eq support.tenantId()) and (ProFormasTable.status eq "SENT") and (ProFormasTable.validUntil less support.today())
    }) { it[status] = "EXPIRED"; it[updatedAt] = Instant.now() }

    // ---- internals ----

    private fun price(locationId: UUID, dealer: Boolean, inputs: List<ProFormaLineInput>, date: LocalDate, vat: VatSetting): List<PricedPfLine> {
        if (inputs.isEmpty()) throw ApiErrors.validationFailed(mapOf("lines" to "at least one line"))
        val ids = inputs.map { it.stockItemId!! }.toSet()
        val unit = StockSql.unitName()
        val items = StockItemsTable.join(VatCategoriesTable, JoinType.INNER, StockItemsTable.vatCategoryId, VatCategoriesTable.id)
            .select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, StockItemsTable.status, VatCategoriesTable.code, unit)
            .where { StockItemsTable.id inList ids }.associateBy { it[StockItemsTable.id].value }
        val priced = prices.currentPrices(ids, date)
        val have = availability.available(locationId, ids)
        return inputs.mapIndexed { index, input ->
            val item = items[input.stockItemId] ?: throw ApiErrors.itemNotSellable(input.stockItemId.toString(), "no such item")
            if (item[StockItemsTable.status] != "ACTIVE") throw ApiErrors.itemNotSellable(item[StockItemsTable.code], "it is ${item[StockItemsTable.status].lowercase()}")
            val price = priced[input.stockItemId] ?: throw ApiErrors.noPriceSet(item[StockItemsTable.code])
            val vatCode = item[VatCategoriesTable.code]
            val value = LineAmounts.value(input.quantity!!, price.priceFor(dealer))
            val discount = Money.round(input.lineDiscount ?: BigDecimal.ZERO)
            if (discount > value) throw ApiErrors.discountExceedsLine()
            val amounts = LineAmounts.of(input.quantity, price.priceFor(dealer), discount, LineAmounts.vatRate(vatCode, vat.enabled, vat.rate))
            PricedPfLine(index + 1, input.stockItemId!!, item[StockItemsTable.name], item[unit] ?: "Unit", vatCode, amounts,
                (have[input.stockItemId] ?: BigDecimal.ZERO) < input.quantity)
        }
    }

    private fun totals(lines: List<PricedPfLine>, vat: VatSetting): ProFormaTotals {
        fun sum(f: (LineAmounts) -> BigDecimal) = lines.fold(BigDecimal.ZERO) { s, l -> s + f(l.amounts) }
        val gross = Money.round(sum { it.value })
        val discount = Money.round(sum { it.discount })
        val net = Money.round(sum { it.net })
        val v = Money.round(sum { it.vat })
        return ProFormaTotals(gross, discount, net, vat.enabled, vat.rate.setScale(2, RoundingMode.HALF_UP), v, Money.round(net + v))
    }

    private fun overCeiling(totals: ProFormaTotals): Boolean {
        if (totals.gross.signum() == 0) return false
        val ceiling = support.settings().decimal(CashierSettings.INSTANT_DISCOUNT_CEILING_PCT)
        return totals.discount * BigDecimal(100) / totals.gross > ceiling
    }

    private fun writeLines(id: UUID, lines: List<PricedPfLine>) {
        lines.forEach { line ->
            ProFormaLinesTable.insert {
                it[tenantId] = support.tenantId()
                it[proFormaId] = id
                it[lineNo] = line.lineNo
                it[stockItemId] = line.stockItemId
                it[itemName] = line.name.take(200)
                it[unitName] = line.unit.take(60)
                it[quantity] = line.amounts.quantity
                it[unitPrice] = line.amounts.unitPrice
                it[lineDiscount] = line.amounts.discount
                it[lineNet] = line.amounts.net
                it[vatRate] = line.amounts.vatRate
                it[vatAmount] = line.amounts.vat
                it[lineTotal] = line.amounts.total
            }
        }
    }

    private fun writeTotals(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, totals: ProFormaTotals) {
        it[ProFormasTable.grossAmount] = totals.gross
        it[ProFormasTable.discountTotal] = totals.discount
        it[ProFormasTable.netAmount] = totals.net
        it[ProFormasTable.vatEnabled] = totals.vatEnabled
        it[ProFormasTable.vatRate] = totals.vatRate
        it[ProFormasTable.vatAmount] = totals.vat
        it[ProFormasTable.total] = totals.total
    }

    private fun register(id: UUID, ref: String, locationId: UUID, customerId: UUID?, total: BigDecimal) =
        documents.register(ref, DocumentType.PRO_FORMA, CashierSupport.SOURCE_MODULE, id, locationId, rowName(id), customerId, total)

    private fun rowName(id: UUID) = ProFormasTable.select(ProFormasTable.customerName).where { ProFormasTable.id eq id }.single()[ProFormasTable.customerName]

    private fun sellableCustomer(id: UUID): CustomerRef {
        val c = customers.get(id)
        if (c.status != CustomerStatus.ACTIVE && c.status != CustomerStatus.WATCH) throw ApiErrors.customerNotActive(c.status.name.lowercase())
        return c
    }

    private fun visible(id: UUID, forUpdate: Boolean = false): ResultRow {
        val row = ProFormasTable.selectAll().where { (ProFormasTable.id eq id) and (ProFormasTable.tenantId eq support.tenantId()) }
            .let { if (forUpdate) it.forUpdate() else it }.firstOrNull() ?: throw ApiErrors.proFormaNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[ProFormasTable.locationId] !in visible) throw ApiErrors.proFormaNotFound()
        return row
    }

    private fun editable(id: UUID): ResultRow {
        val pf = visible(id, forUpdate = true)
        if (pf[ProFormasTable.status] != "DRAFT") throw ApiErrors.proFormaNotEditable(pf[ProFormasTable.status])
        return pf
    }

    private fun pendingDiscount(id: UUID): ResultRow {
        val pf = visible(id, forUpdate = true)
        if (pf[ProFormasTable.status] != "PENDING_APPROVAL") throw ApiErrors.alreadyDecided("pro-forma discount")
        return pf
    }

    private fun row(id: UUID): ResultRow = ProFormasTable.selectAll().where { ProFormasTable.id eq id }.single()

    private fun view(pf: ResultRow): ProFormaView {
        val id = pf[ProFormasTable.id].value
        val lines = ProFormaLinesTable.selectAll().where { ProFormaLinesTable.proFormaId eq id }.orderBy(ProFormaLinesTable.lineNo).map {
            ProFormaLineView(it[ProFormaLinesTable.lineNo], Ref.stockItem(it[ProFormaLinesTable.stockItemId]), it[ProFormaLinesTable.itemName], it[ProFormaLinesTable.unitName],
                it[ProFormaLinesTable.quantity], it[ProFormaLinesTable.unitPrice], it[ProFormaLinesTable.lineDiscount], it[ProFormaLinesTable.lineNet],
                it[ProFormaLinesTable.vatRate], it[ProFormaLinesTable.vatAmount], it[ProFormaLinesTable.lineTotal], false)
        }
        return ProFormaView(
            id = id, ref = pf[ProFormasTable.ref], status = pf[ProFormasTable.status], location = Ref.location(pf[ProFormasTable.locationId]),
            customer = ref(pf[ProFormasTable.customerId], Ref::customer), customerName = pf[ProFormasTable.customerName], customerType = pf[ProFormasTable.customerType],
            prospect = if (pf[ProFormasTable.customerId] == null) ProspectInput(pf[ProFormasTable.prospectName], pf[ProFormasTable.prospectAddress], pf[ProFormasTable.prospectContact], pf[ProFormasTable.prospectEmail]) else null,
            priceBasis = pf[ProFormasTable.priceBasis], issueDate = pf[ProFormasTable.issueDate], validUntil = pf[ProFormasTable.validUntil],
            paymentTerms = pf[ProFormasTable.paymentTerms], deliveryTerms = pf[ProFormasTable.deliveryTerms], note = pf[ProFormasTable.note], lines = lines,
            totals = ProFormaTotals(pf[ProFormasTable.grossAmount], pf[ProFormasTable.discountTotal], pf[ProFormasTable.netAmount], pf[ProFormasTable.vatEnabled],
                pf[ProFormasTable.vatRate], pf[ProFormasTable.vatAmount], pf[ProFormasTable.total]),
            preparedBy = UserRef(pf[ProFormasTable.preparedByUserId]), declineReason = pf[ProFormasTable.declineReason], convertedSale = ref(pf[ProFormasTable.convertedSaleId], Ref::sale),
        )
    }
}
