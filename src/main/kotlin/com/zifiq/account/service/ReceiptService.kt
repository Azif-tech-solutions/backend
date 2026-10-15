package com.zifiq.account.service

import com.zifiq.platform.directory.UserRef
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import com.zifiq.common.sql.Aggregates
import com.zifiq.platform.directory.UserRefs
import com.zifiq.common.money.Money
import com.zifiq.account.dto.ConfirmationStats
import com.zifiq.account.dto.OtherIncomeRequest
import com.zifiq.account.dto.ReceiptRow
import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.PaymentReceiptsTable
import com.zifiq.account.spi.Channel
import com.zifiq.account.spi.ConfirmationStatus
import com.zifiq.account.spi.MoneyLedger
import com.zifiq.account.spi.ReceiptRef
import com.zifiq.account.spi.ReceiptRequest
import com.zifiq.account.spi.ReceiptView
import com.zifiq.account.spi.Receipts
import com.zifiq.account.spi.RefundRequest
import com.zifiq.common.errors.ApiErrors
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
import com.zifiq.platform.documents.DocumentLinkProvider
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.Recipients
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.settings.spi.CompanyProfileProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Payment receipts (docs/07 §4.10, §4.18): the single record of money received. Lists them (a
 * cashier sees only their own), runs the POS / Fund Transfer Confirmation queue, voids wallet-funding
 * and other-income receipts, prints receipts, records other income, and is the `MoneyLedger` /
 * `Receipts` spi other modules post through.
 */
@Service
class ReceiptService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val writer: ReceiptWriter,
    private val handlers: List<ReceiptSourceHandler>,
    private val documents: DocumentRegistry,
    private val pdfRenderer: PdfRenderer,
    private val companyProfile: CompanyProfileProvider,
    private val notify: Notify,
    private val recipients: Recipients,
) : MoneyLedger, Receipts {
    // ---- MoneyLedger / Receipts (spi, caller's transaction) ----

    override fun recordReceipt(request: ReceiptRequest): ReceiptRef = writer.record(request).also { recorded(it, request.locationId, request.sourceRef, request.customerName) }

    override fun recordRefund(request: RefundRequest): ReceiptRef = writer.refund(request).also {
        support.record("account.receipt.recorded", "payment_receipt", it.id, it.ref, "${it.ref}: refund of ${Money.naira(it.amount)} by ${it.channel.name.lowercase()} for ${request.sourceRef}",
            mapOf("kind" to "REFUND", "channel" to it.channel.name, "amount" to it.amount), request.locationId)
    }

    override fun voidReceipt(receiptId: UUID, reason: String, sourceModule: String) {
        val receipt = writer.row(receiptId, forUpdate = true)
        if (receipt[PaymentReceiptsTable.sourceModule] != sourceModule) throw ApiErrors.reverseInSourceModule(receipt[PaymentReceiptsTable.sourceModule])
        doVoid(receipt, reason)
    }

    override fun bySource(sourceModule: String, sourceIds: Collection<UUID>): List<ReceiptView> =
        if (sourceIds.isEmpty()) emptyList()
        else PaymentReceiptsTable.selectAll()
            .where { (PaymentReceiptsTable.tenantId eq support.tenantId()) and (PaymentReceiptsTable.sourceModule eq sourceModule) and (PaymentReceiptsTable.sourceId inList sourceIds.toSet()) }
            .orderBy(PaymentReceiptsTable.receivedAt to SortOrder.ASC).map(::toView)

    override fun get(id: UUID): ReceiptView? =
        PaymentReceiptsTable.selectAll().where { (PaymentReceiptsTable.id eq id) and (PaymentReceiptsTable.tenantId eq support.tenantId()) }.firstOrNull()?.let(::toView)

    // ---- Confirmation (§4.10) ----

    /** The money is confirmed in the bank: post the credit, and complete what it paid for (a wallet funding). */
    fun approve(id: UUID): ReceiptRow = tx.execute {
        val receipt = pending(id)
        writer.postApproval(receipt)
        review(id, "APPROVED", null)
        val fresh = writer.row(id)
        handlerFor(fresh)?.approved(fresh)
        support.record("account.receipt.approved", "payment_receipt", id, fresh[PaymentReceiptsTable.ref],
            "${fresh[PaymentReceiptsTable.ref]} approved: ${Money.naira(fresh[PaymentReceiptsTable.amount])} ${channelLabel(fresh)}",
            mapOf("amount" to fresh[PaymentReceiptsTable.amount], "channel" to fresh[PaymentReceiptsTable.channel]), fresh[PaymentReceiptsTable.locationId])
        rowView(id)
    }

    /** Nothing arrived: no posting; the sale stays and its invoice shows the shortfall (decision §1.3-4). */
    fun decline(id: UUID, note: String): ReceiptRow = tx.execute {
        val receipt = pending(id)
        review(id, "DECLINED", note.trim())
        val fresh = writer.row(id)
        handlerFor(fresh)?.declined(fresh, note.trim())
        val ref = fresh[PaymentReceiptsTable.ref]
        val eventId = support.record("account.receipt.declined", "payment_receipt", id, ref,
            "$ref declined: ${Money.naira(fresh[PaymentReceiptsTable.amount])} ${channelLabel(fresh)} — ${note.trim()}",
            mapOf("amount" to fresh[PaymentReceiptsTable.amount], "channel" to fresh[PaymentReceiptsTable.channel], "sourceType" to fresh[PaymentReceiptsTable.sourceType],
                "sourceId" to fresh[PaymentReceiptsTable.sourceId]), fresh[PaymentReceiptsTable.locationId])
        val people = setOf(receipt[PaymentReceiptsTable.cashierUserId]) + recipients.withPermission("invoicing.receivables.read", receipt[PaymentReceiptsTable.locationId])
        notify.inApp(people, NotificationCategory.ALERT, "Payment declined — $ref",
            "${Money.naira(fresh[PaymentReceiptsTable.amount])} ${channelLabel(fresh)} for ${fresh[PaymentReceiptsTable.sourceRef]}: ${note.trim()}",
            "receipt", id.toString(), eventId, exclude = support.userId())
        rowView(id)
    }

    /** Only wallet/deposit fundings and other income are voided here; sales and invoice payments in their own modules. */
    fun void(id: UUID, reason: String): ReceiptRow = tx.execute {
        val receipt = writer.row(id, forUpdate = true)
        assertVisible(receipt)
        if (receipt[PaymentReceiptsTable.kind] != "RECEIPT" || receipt[PaymentReceiptsTable.sourceType] !in VOIDABLE_HERE) {
            throw ApiErrors.reverseInSourceModule(receipt[PaymentReceiptsTable.sourceModule])
        }
        doVoid(receipt, reason.trim())
        rowView(id)
    }

    /** The confirmation screen's header: one aggregate query over the caller's receipts. */
    fun confirmationStats(locationId: UUID?): ConfirmationStats = tx.execute {
        val today = support.today().atStartOfDay(support.moduleSettings().zone()).toInstant()
        val pendingWhere = (PaymentReceiptsTable.confirmationStatus eq "PENDING") and (PaymentReceiptsTable.status eq "ISSUED")
        fun reviewedToday(status: String) = Aggregates.count((PaymentReceiptsTable.confirmationStatus eq status) and (PaymentReceiptsTable.reviewedAt greaterEq today))
        val pendingCount = Aggregates.count(pendingWhere)
        val pendingValue = Aggregates.sum(PaymentReceiptsTable.amount, pendingWhere)
        val approved = reviewedToday("APPROVED")
        val declined = reviewedToday("DECLINED")
        val row = PaymentReceiptsTable.select(pendingCount, pendingValue, approved, declined).where(scope(locationId)).single()
        ConfirmationStats(Aggregates.long(row, pendingCount), Aggregates.money(row, pendingValue), Aggregates.long(row, approved), Aggregates.long(row, declined))
    }

    // ---- Lists ----

    fun list(request: ListSearchRequest): PageResult<ReceiptRow> = tx.execute {
        val context = support.filterContext()
        val parsed = searchSpec.parse(request.q)
        val condition = listOfNotNull(
            visibility(),
            searchSpec.condition(parsed),
            filterSpec.fromParams(request.params, context),
            filterSpec.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) searchSpec.rank(parsed) + filterSpec.sort(emptyList(), context) else filterSpec.sort(request.sortParams, context)
        val joined = joined()
        val page = Paging.fetch(joined.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) {
            // Live = issued and not declined; refunds count against their channel.
            val live = (PaymentReceiptsTable.status eq "ISSUED") and (PaymentReceiptsTable.confirmationStatus neq "DECLINED")
            val pendingWhere = live and (PaymentReceiptsTable.confirmationStatus eq "PENDING")
            val count = Aggregates.count()
            val pendingCount = Aggregates.count(pendingWhere)
            val pendingValue = Aggregates.sum(PaymentReceiptsTable.amount, pendingWhere)
            val totals = joined.select(count, pendingCount, pendingValue).where(condition).single()
            val signed = RawExpression<BigDecimal>({ +"(case when "; +PaymentReceiptsTable.kind; +" = 'REFUND' then -"; +PaymentReceiptsTable.amount; +" else "; +PaymentReceiptsTable.amount; +" end)" })
            val byChannel = Aggregates.sum(signed)
            mapOf(
                "count" to Aggregates.long(totals, count),
                "totalByChannel" to joined.select(PaymentReceiptsTable.channel, byChannel).where(condition and live).groupBy(PaymentReceiptsTable.channel)
                    .associate { it[PaymentReceiptsTable.channel] to Aggregates.money(it, byChannel) },
                "pendingCount" to Aggregates.long(totals, pendingCount),
                "pendingValue" to Aggregates.money(totals, pendingValue),
            )
        } else null
        page.withItems(toRows(page.items)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filterSpec.metadata(support.filterContext()) }

    fun getRow(id: UUID): ReceiptRow = tx.execute {
        assertVisible(writer.row(id))
        rowView(id)
    }

    // ---- Other income (§4.18) ----

    /** By-product and scrap sales, rent, interest received: a receipt whose own reference is its source. */
    fun recordOtherIncome(request: OtherIncomeRequest): ReceiptRow = tx.execute {
        support.requireNotFuture("receivedDate", request.receivedDate!!)
        val sourceId = UUID.randomUUID()
        val recorded = writer.record(
            ReceiptRequest(
                locationId = request.locationId, cashAccountId = request.cashAccountId, customerId = null, customerName = request.payerName.trim(),
                channel = Channel.valueOf(request.channel!!), amount = request.amount!!, sourceModule = ReceiptWriter.SOURCE_MODULE, sourceType = "OTHER_INCOME",
                sourceId = sourceId, sourceRef = "OI", receivingBankAccountId = request.receivingBankAccountId, payerReference = request.payerReference,
                incomeCategory = request.incomeCategory, description = request.description.trim(), entryDate = request.receivedDate,
            ),
        )
        // Its own reference is its source reference, and its own id its source id.
        PaymentReceiptsTable.update({ PaymentReceiptsTable.id eq recorded.id }) { it[sourceRef] = recorded.ref; it[PaymentReceiptsTable.sourceId] = recorded.id }
        support.record("account.other-income.recorded", "payment_receipt", recorded.id, recorded.ref,
            "${recorded.ref}: ${Money.naira(recorded.amount)} ${request.incomeCategory!!.lowercase().replace('_', ' ')} from ${request.payerName.trim()}",
            mapOf("category" to request.incomeCategory, "amount" to recorded.amount, "channel" to request.channel), request.locationId)
        recorded(recorded, request.locationId, recorded.ref, request.payerName.trim())
        rowView(recorded.id)
    }

    /** Other income only: the receipts list narrowed to source OTHER_INCOME (filter by incomeCategory as usual). */
    fun otherIncome(request: ListSearchRequest): PageResult<ReceiptRow> = list(request.copy(params = request.params + ("sourceType" to listOf("OTHER_INCOME"))))

    // ---- Printable receipt (§4.10) ----

    data class PrintedReceipt(val ref: String, val pdf: ByteArray)

    fun pdf(id: UUID): PrintedReceipt {
        val model = tx.execute {
            val receipt = writer.row(id)
            assertVisible(receipt)
            val row = rowView(id).also(UserRefs::resolve)
            val company = companyProfile.get()
            val stamp = when {
                row.status == "VOIDED" -> "Void"
                row.kind == "REFUND" -> "Refunded"
                row.confirmationStatus == "PENDING" -> "Pending confirmation"
                row.confirmationStatus == "DECLINED" -> "Declined"
                else -> "Paid"
            }
            mapOf(
                "company" to company,
                "logo" to company.logoDataUri(),
                "receipt" to row,
                "title" to if (row.kind == "REFUND") "Refund Receipt" else "Payment Receipt",
                "stamp" to stamp,
                "stampClass" to when (stamp) { "Paid", "Refunded" -> "ok"; "Pending confirmation" -> "pending"; else -> "void" },
                "channel" to channelName(row.channel),
                "receivedAt" to LocalDateTime.ofInstant(row.receivedAt, support.moduleSettings().zone()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
                "printedAt" to LocalDateTime.now(support.moduleSettings().zone()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
                "duplicate" to documents.isReprint(row.ref),
                "amount" to Money.naira(row.amount),
            )
        }
        return PrintedReceipt((model["receipt"] as ReceiptRow).ref, pdfRenderer.render("receipt", model))
    }

    /** Global search: receipts by reference, source reference (INV-…, TXN-…), payer reference or customer. */
    fun search(q: String, limit: Int): List<SearchHit> =
        list(ListSearchRequest(q = q, size = limit, summary = false)).items.map {
            SearchHit("receipt", it.id, it.ref, "${channelName(it.channel)} · ${Money.naira(it.amount)} · ${it.customerName ?: "Walk-in"} · ${it.confirmationStatus.lowercase().replace('_', ' ')}",
                it.ref, "/account/receipts/${it.id}")
        }

    // ---- Helpers (caller's transaction) ----

    private fun recorded(ref: ReceiptRef, locationId: UUID?, sourceRef: String, customerName: String?) {
        val eventId = support.record("account.receipt.recorded", "payment_receipt", ref.id, ref.ref,
            "${ref.ref}: ${Money.naira(ref.amount)} by ${channelName(ref.channel.name).lowercase()} for $sourceRef" + (customerName?.let { " — $it" } ?: ""),
            mapOf("channel" to ref.channel.name, "amount" to ref.amount, "confirmationStatus" to ref.confirmationStatus.name), locationId)
        // POS / transfer / cheque receipts wait for Accounts; the configured channels notify one by one (card payments are too frequent).
        if (ref.confirmationStatus == ConfirmationStatus.PENDING && ref.channel.name in support.moduleSettings().stringList(AccountingSettings.NOTIFY_PER_RECEIPT_CHANNELS)) {
            notify.holdersOf(
                permission = "accounting.receipts.confirm", locationId = locationId, category = NotificationCategory.APPROVAL_REQUIRED,
                title = "${channelName(ref.channel.name)} awaiting confirmation — ${ref.ref}",
                body = "${Money.naira(ref.amount)} for $sourceRef" + (customerName?.let { " from $it" } ?: "") + ". Confirm it against the bank.",
                linkType = "receipt", linkRef = ref.id.toString(), sourceEventId = eventId, exclude = support.userId(),
            )
        }
    }

    private fun doVoid(receipt: ResultRow, reason: String) {
        val id = receipt[PaymentReceiptsTable.id].value
        val ref = receipt[PaymentReceiptsTable.ref]
        if (receipt[PaymentReceiptsTable.status] == "VOIDED") throw ApiErrors.alreadyReversed()
        // Whatever the documents behind it did is undone first: a spent wallet funding can't be voided.
        handlerFor(receipt)?.voided(receipt, reason)
        writer.undoPosting(receipt, reason)
        PaymentReceiptsTable.update({ PaymentReceiptsTable.id eq id }) {
            it[status] = "VOIDED"; it[voidReason] = reason; it[voidedByUserId] = support.userId(); it[voidedAt] = Instant.now()
        }
        documents.markVoided(ref)
        support.record("account.receipt.voided", "payment_receipt", id, ref, "$ref voided: $reason",
            mapOf("amount" to receipt[PaymentReceiptsTable.amount], "sourceType" to receipt[PaymentReceiptsTable.sourceType]), receipt[PaymentReceiptsTable.locationId])
    }

    private fun pending(id: UUID): ResultRow {
        val receipt = writer.row(id, forUpdate = true)
        assertVisible(receipt)
        receipt[PaymentReceiptsTable.locationId]?.let { support.assertAccess(it) } ?: support.assertAccess(null)
        if (receipt[PaymentReceiptsTable.confirmationStatus] != "PENDING" || receipt[PaymentReceiptsTable.status] != "ISSUED") throw ApiErrors.alreadyDecided("receipt")
        return receipt
    }

    private fun review(id: UUID, status: String, note: String?) {
        PaymentReceiptsTable.update({ PaymentReceiptsTable.id eq id }) {
            it[confirmationStatus] = status; it[reviewedByUserId] = support.userId(); it[reviewedAt] = Instant.now(); it[declineNote] = note
        }
    }

    private fun handlerFor(receipt: ResultRow): ReceiptSourceHandler? = handlers.firstOrNull { receipt[PaymentReceiptsTable.sourceType] in it.sourceTypes }

    /**
     * Location scope, plus PRD §7 "Cashier — own transactions": a caller without accounting.read
     * (a cashier holding only accounting.receipts.read) sees only receipts they took.
     */
    private fun visibility() = listOfNotNull(
        PaymentReceiptsTable.tenantId eq support.tenantId(),
        support.visibleLocations()?.let { PaymentReceiptsTable.locationId inList it },
        if (support.can("accounting.read")) null else PaymentReceiptsTable.cashierUserId eq support.userId(),
    ).allOf()!!

    private fun scope(locationId: UUID?) = listOfNotNull(visibility(), support.resolveLocation(locationId)?.let { PaymentReceiptsTable.locationId eq it }).allOf()!!

    private fun assertVisible(receipt: ResultRow) {
        val visible = support.visibleLocations()
        if (visible != null && receipt[PaymentReceiptsTable.locationId] !in visible) throw ApiErrors.receiptNotFound()
        if (!support.can("accounting.read") && receipt[PaymentReceiptsTable.cashierUserId] != support.userId()) throw ApiErrors.receiptNotFound()
    }

    private val till = CashAccountsTable.alias("rc_till")
    private val bank = BankAccountsTable.alias("rc_bank")

    private fun joined() = PaymentReceiptsTable
        .join(LocationsTable, JoinType.LEFT, additionalConstraint = { RawOp { +PaymentReceiptsTable.locationId; +" = "; +LocationsTable.id } })
        .join(till, JoinType.LEFT, PaymentReceiptsTable.cashAccountId, till[CashAccountsTable.id])
        .join(bank, JoinType.LEFT, PaymentReceiptsTable.receivingBankAccountId, bank[BankAccountsTable.id])

    private fun rowView(id: UUID): ReceiptRow = toRows(joined().selectAll().where { PaymentReceiptsTable.id eq id }.toList()).single()

    private fun toRows(rows: List<ResultRow>): List<ReceiptRow> {
        return rows.map { row ->
            ReceiptRow(
                id = row[PaymentReceiptsTable.id].value, ref = row[PaymentReceiptsTable.ref], kind = row[PaymentReceiptsTable.kind], receivedAt = row[PaymentReceiptsTable.receivedAt],
                locationId = row[PaymentReceiptsTable.locationId], locationName = row.getOrNull(LocationsTable.name),
                cashAccountId = row[PaymentReceiptsTable.cashAccountId], till = row.getOrNull(till[CashAccountsTable.code]),
                cashier = UserRef(row[PaymentReceiptsTable.cashierUserId]),
                customerId = row[PaymentReceiptsTable.customerId], customerName = row[PaymentReceiptsTable.customerName],
                channel = row[PaymentReceiptsTable.channel], amount = row[PaymentReceiptsTable.amount], sourceModule = row[PaymentReceiptsTable.sourceModule],
                sourceType = row[PaymentReceiptsTable.sourceType], sourceRef = row[PaymentReceiptsTable.sourceRef], incomeCategory = row[PaymentReceiptsTable.incomeCategory],
                description = row[PaymentReceiptsTable.description], receivingBankAccountId = row[PaymentReceiptsTable.receivingBankAccountId],
                receivingBank = row.getOrNull(bank[BankAccountsTable.code])?.let { "$it ${row[bank[BankAccountsTable.bankName]]}" },
                terminalRef = row[PaymentReceiptsTable.terminalRef], payerReference = row[PaymentReceiptsTable.payerReference],
                confirmationStatus = row[PaymentReceiptsTable.confirmationStatus], reviewedBy = UserRef.of(row[PaymentReceiptsTable.reviewedByUserId]),
                reviewedAt = row[PaymentReceiptsTable.reviewedAt], declineNote = row[PaymentReceiptsTable.declineNote], status = row[PaymentReceiptsTable.status],
                voidReason = row[PaymentReceiptsTable.voidReason],
            )
        }
    }

    private fun toView(row: ResultRow) = ReceiptView(
        id = row[PaymentReceiptsTable.id].value, ref = row[PaymentReceiptsTable.ref], kind = row[PaymentReceiptsTable.kind], channel = Channel.valueOf(row[PaymentReceiptsTable.channel]),
        amount = row[PaymentReceiptsTable.amount], confirmationStatus = ConfirmationStatus.valueOf(row[PaymentReceiptsTable.confirmationStatus]), status = row[PaymentReceiptsTable.status],
        sourceModule = row[PaymentReceiptsTable.sourceModule], sourceType = row[PaymentReceiptsTable.sourceType], sourceId = row[PaymentReceiptsTable.sourceId],
        sourceRef = row[PaymentReceiptsTable.sourceRef], customerId = row[PaymentReceiptsTable.customerId], locationId = row[PaymentReceiptsTable.locationId],
        receivedAt = row[PaymentReceiptsTable.receivedAt],
    )

    private fun channelLabel(row: ResultRow) = channelName(row[PaymentReceiptsTable.channel]).lowercase()

    private val pendingAge = RawExpression<BigDecimal>({ +"(extract(epoch from (now() - "; +PaymentReceiptsTable.receivedAt; +")) / 3600)" })

    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("locationId", "Location", FilterType.LOCATION, PaymentReceiptsTable.locationId, facetable = true),
            FilterField("cashAccountId", "Till", FilterType.UUID, PaymentReceiptsTable.cashAccountId, lookup = "/api/v1/account/cash-accounts/lookup?kind=TILL"),
            FilterField("cashierUserId", "Cashier", FilterType.UUID, PaymentReceiptsTable.cashierUserId, aliases = setOf("cashier"), facetable = true),
            FilterField("channel", "Channel", FilterType.ENUM, PaymentReceiptsTable.channel, CHANNELS.map { FilterOption(it, channelName(it)) }, facetable = true),
            FilterField("kind", "Kind", FilterType.ENUM, PaymentReceiptsTable.kind, listOf(FilterOption("RECEIPT", "Receipt"), FilterOption("REFUND", "Refund"))),
            FilterField("confirmationStatus", "Confirmation", FilterType.ENUM, PaymentReceiptsTable.confirmationStatus,
                listOf("NOT_REQUIRED", "PENDING", "APPROVED", "DECLINED").map { FilterOption(it, BankAccountService.label(it)) }, aliases = setOf("confirmation"), facetable = true),
            FilterField("status", "Status", FilterType.ENUM, PaymentReceiptsTable.status, listOf(FilterOption("ISSUED", "Issued"), FilterOption("VOIDED", "Voided"))),
            FilterField("sourceType", "Source", FilterType.ENUM, PaymentReceiptsTable.sourceType,
                listOf("POS_SALE", "INVOICE_PAYMENT", "WALLET_FUNDING", "DEPOSIT_FUNDING", "SALE_REFUND", "DEPOSIT_REFUND", "OTHER_INCOME").map { FilterOption(it, BankAccountService.label(it)) }, facetable = true),
            FilterField("customerId", "Customer", FilterType.UUID, PaymentReceiptsTable.customerId, lookup = "/api/v1/customers/lookup"),
            FilterField("receivingBankAccountId", "Receiving bank", FilterType.UUID, PaymentReceiptsTable.receivingBankAccountId, lookup = "/api/v1/account/bank-accounts/lookup"),
            FilterField("incomeCategory", "Income category", FilterType.ENUM, PaymentReceiptsTable.incomeCategory,
                listOf("BY_PRODUCT_SALES", "SCRAP_SALES", "RENTAL", "INTEREST", "OTHER").map { FilterOption(it, BankAccountService.label(it)) }),
            FilterField("pendingOlderThanHours", "Pending for (hours)", FilterType.NUMBER, pendingAge),
            FilterField("amount", "Amount", FilterType.MONEY, PaymentReceiptsTable.amount, sortable = true),
            FilterField("receivedAt", "Received", FilterType.DATETIME, PaymentReceiptsTable.receivedAt, sortable = true),
        ),
        primaryDateKey = "receivedAt",
        defaultSort = listOf("receivedAt" to SortOrder.DESC),
        tiebreaker = PaymentReceiptsTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(PaymentReceiptsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(PaymentReceiptsTable.sourceRef, SearchKind.REFERENCE, "Source reference"),
            SearchField(PaymentReceiptsTable.payerReference, SearchKind.CODE, "Payer reference"),
            SearchField(PaymentReceiptsTable.terminalRef, SearchKind.CODE, "Terminal"),
            SearchField(PaymentReceiptsTable.customerName, SearchKind.TRIGRAM, "Customer"),
            SearchField(RawExpression<String>({ +"payment_receipts.search_vector" }), SearchKind.FULLTEXT, "Text"),
            SearchField(PaymentReceiptsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = filterSpec,
    )

    companion object {
        val CHANNELS = listOf("CASH", "POS", "FUND_TRANSFER", "CHEQUE", "WALLET", "DEPOSIT")
        private val VOIDABLE_HERE = setOf("WALLET_FUNDING", "DEPOSIT_FUNDING", "OTHER_INCOME")

        fun channelName(channel: String): String = Channel.labelOf(channel)
    }
}

/** Lets the document registry link receipts to their PDF. */
@Component
class AccountDocumentLinks : DocumentLinkProvider {
    override val sourceModule = ReceiptWriter.SOURCE_MODULE
    override fun pdfUrl(docType: DocumentType, sourceId: UUID): String? =
        if (docType == DocumentType.RECEIPT) "/api/v1/account/receipts/$sourceId/receipt.pdf" else null
}

/** Global search provider `receipt` (docs/07 §9); an exact INV-/TXN- source reference also finds its receipts. */
@Component
class ReceiptSearchProvider(private val support: AccountSupport, private val receipts: ReceiptService) : SearchProvider {
    override val type = "receipt"
    override val label = "Receipts"
    override val listRoute = "/account/receipts"

    override fun canSearch() = support.can("accounting.receipts.read") || support.can("accounting.read")

    override fun search(q: String, limit: Int): List<SearchHit> = receipts.search(q, limit)
}
