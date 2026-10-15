package com.zifiq.account.service

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.account.dto.RunningCostRequest
import com.zifiq.account.dto.RunningCostView
import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.ExpenseCategoriesTable
import com.zifiq.account.model.OperatingCostsTable
import com.zifiq.account.repository.LedgerPosting
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
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Payment / Running Cost (docs/07 §4.9): registered by Accounts, verified by someone else before any
 * money moves (rules A3, A4). Drafts are not documents yet; submission gives the RC-n reference and
 * runs the checks; only approval posts to the ledger.
 */
@Service
class RunningCostService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val moves: MoneyMoves,
    private val sequences: DocumentSequenceService,
    private val categories: ExpenseCategoryService,
    private val notify: Notify,
) {
    fun create(request: RunningCostRequest): RunningCostView = tx.execute {
        validateShape(request)
        val id = OperatingCostsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            fill(it, request)
            it[status] = "DRAFT"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        if (request.submit) submitRow(row(id, forUpdate = true))
        view(id)
    }

    /** A draft, or a rejected entry being corrected (resubmitting keeps its reference). */
    fun update(id: UUID, request: RunningCostRequest): RunningCostView = tx.execute {
        val existing = row(id, forUpdate = true)
        val status = existing[OperatingCostsTable.status]
        if (status != "DRAFT" && status != "REJECTED") throw ApiErrors.notEditable(status.lowercase().replace('_', ' '))
        validateShape(request)
        OperatingCostsTable.update({ OperatingCostsTable.id eq id }) { fill(it, request) }
        if (request.submit) submitRow(row(id, forUpdate = true))
        view(id)
    }

    fun submit(id: UUID): RunningCostView = tx.execute {
        val existing = row(id, forUpdate = true)
        val status = existing[OperatingCostsTable.status]
        if (status != "DRAFT" && status != "REJECTED") throw ApiErrors.alreadyDecided("running cost")
        submitRow(existing)
        view(id)
    }

    /** Someone other than the submitter approves (an Administrator may self-approve when the setting allows); the money moves now. */
    fun approve(id: UUID): RunningCostView = tx.execute {
        val row = row(id, forUpdate = true)
        if (row[OperatingCostsTable.status] != "PENDING_APPROVAL") throw ApiErrors.alreadyDecided("running cost")
        if (row[OperatingCostsTable.submittedByUserId] == support.userId()) {
            val adminMay = support.isAdministrator() && support.moduleSettings().boolean(AccountingSettings.ADMIN_SELF_APPROVAL)
            if (!adminMay) throw ApiErrors.selfApprovalNotAllowed()
        }
        val category = categories.requireUsable(row[OperatingCostsTable.expenseCategoryId], forPettyCash = row[OperatingCostsTable.paymentMethod] == "PETTY_CASH")
        val ref = row[OperatingCostsTable.ref]!!
        val amount = row[OperatingCostsTable.amount]
        val account = payingAccount(row)
        val posted = moves.out(account, amount, LedgerPosting(ref, "${category[ExpenseCategoriesTable.name]} — ${row[OperatingCostsTable.payee]}" +
            (row[OperatingCostsTable.description]?.let { ": $it" } ?: ""), "RUNNING_COST", id, category[ExpenseCategoriesTable.id].value, row[OperatingCostsTable.paymentDate], support.userId()))
        OperatingCostsTable.update({ OperatingCostsTable.id eq id }) {
            it[status] = "APPROVED"
            it[approvedByUserId] = support.userId()
            it[approvedAt] = Instant.now()
            it[cashTransactionId] = posted.cashTransactionId
            it[bankTransactionId] = posted.bankTransactionId
        }
        val eventId = support.record("account.running-cost.approved", "running_cost", id, ref, "$ref approved: ${Money.naira(amount)} to ${row[OperatingCostsTable.payee]}",
            mapOf("amount" to amount, "account" to account.code), row[OperatingCostsTable.locationId])
        tellSubmitter(row, NotificationCategory.CONFIRMATION, "Running cost approved — $ref", "${Money.naira(amount)} to ${row[OperatingCostsTable.payee]} was approved and paid from ${account.code}.", eventId)
        view(id)
    }

    fun reject(id: UUID, reason: String): RunningCostView = tx.execute {
        val row = row(id, forUpdate = true)
        if (row[OperatingCostsTable.status] != "PENDING_APPROVAL") throw ApiErrors.alreadyDecided("running cost")
        val ref = row[OperatingCostsTable.ref]!!
        OperatingCostsTable.update({ OperatingCostsTable.id eq id }) {
            it[status] = "REJECTED"
            it[rejectionReason] = reason.trim()
            it[approvedByUserId] = support.userId()
            it[approvedAt] = Instant.now()
        }
        val eventId = support.record("account.running-cost.rejected", "running_cost", id, ref, "$ref rejected: ${reason.trim()}", mapOf("amount" to row[OperatingCostsTable.amount]), row[OperatingCostsTable.locationId])
        tellSubmitter(row, NotificationCategory.ALERT, "Running cost rejected — $ref", "${Money.naira(row[OperatingCostsTable.amount])} to ${row[OperatingCostsTable.payee]}: ${reason.trim()}", eventId)
        view(id)
    }

    /** An approved cost paid in error: the money goes back to the paying account. */
    fun reverse(id: UUID, reason: String): RunningCostView = tx.execute {
        val row = row(id, forUpdate = true)
        if (row[OperatingCostsTable.status] != "APPROVED") throw ApiErrors.notEditable(row[OperatingCostsTable.status].lowercase().replace('_', ' '))
        val ref = row[OperatingCostsTable.ref]!!
        val account = moves.existing(row[OperatingCostsTable.payingBankAccountId] != null, row[OperatingCostsTable.payingBankAccountId] ?: row[OperatingCostsTable.payingCashAccountId]!!)
        moves.into(account, row[OperatingCostsTable.amount], LedgerPosting(ref, "Reversal of $ref: ${reason.trim()}", "RUNNING_COST", id, row[OperatingCostsTable.expenseCategoryId], support.today(), support.userId()))
        OperatingCostsTable.update({ OperatingCostsTable.id eq id }) {
            it[status] = "REVERSED"; it[reversalReason] = reason.trim(); it[reversedByUserId] = support.userId(); it[reversedAt] = Instant.now()
        }
        val eventId = support.record("account.running-cost.reversed", "running_cost", id, ref, "$ref reversed: ${reason.trim()}", mapOf("amount" to row[OperatingCostsTable.amount]), row[OperatingCostsTable.locationId])
        tellSubmitter(row, NotificationCategory.ALERT, "Running cost reversed — $ref", reason.trim(), eventId)
        view(id)
    }

    /** Only a draft: it isn't a document yet (docs/03 §13 — documents are never deleted). */
    fun deleteDraft(id: UUID) = tx.execute {
        val row = row(id, forUpdate = true)
        if (row[OperatingCostsTable.status] != "DRAFT") throw ApiErrors.notEditable(row[OperatingCostsTable.status].lowercase().replace('_', ' '))
        OperatingCostsTable.deleteWhere { OperatingCostsTable.id eq id }
        Unit
    }

    fun get(id: UUID): RunningCostView = tx.execute {
        row(id)
        view(id)
    }

    fun list(request: ListSearchRequest): PageResult<RunningCostView> = tx.execute {
        val context = support.filterContext()
        val parsed = searchSpec.parse(request.q)
        val awaitingMine = request.params["awaitingMyApproval"]?.firstOrNull()?.toBoolean() == true
        val params = request.params - "awaitingMyApproval"
        val condition = listOfNotNull(
            OperatingCostsTable.tenantId eq support.tenantId(),
            visibility(),
            if (awaitingMine) (OperatingCostsTable.status eq "PENDING_APPROVAL") and RawOp { +"("; +OperatingCostsTable.submittedByUserId; +" IS DISTINCT FROM '${support.userId()}'::uuid)" } else null,
            searchSpec.condition(parsed),
            filterSpec.fromParams(params, context),
            filterSpec.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) searchSpec.rank(parsed) + filterSpec.sort(emptyList(), context) else filterSpec.sort(request.sortParams, context)
        val joined = joined()
        val page = Paging.fetch(joined.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) {
            val all = joined.select(OperatingCostsTable.status, OperatingCostsTable.amount).where(condition).toList()
            val pending = all.filter { it[OperatingCostsTable.status] == "PENDING_APPROVAL" }
            mapOf(
                "count" to all.size,
                "total" to Money.round(all.fold(BigDecimal.ZERO) { s, it -> s + it[OperatingCostsTable.amount] }),
                "pendingCount" to pending.size,
                "pendingValue" to Money.round(pending.fold(BigDecimal.ZERO) { s, it -> s + it[OperatingCostsTable.amount] }),
            )
        } else null
        page.withItems(toViews(page.items)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filterSpec.metadata(support.filterContext()) }

    /** Global search: running costs by reference, payee or invoice number. */
    fun search(q: String, limit: Int): List<SearchHit> =
        list(ListSearchRequest(q = q, size = limit, summary = false)).items.filter { it.ref != null }.map {
            SearchHit("running-cost", it.id, it.ref!!, "${it.payee} · ${Money.naira(it.amount)} · ${it.status.lowercase().replace('_', ' ')}", it.ref, "/account/running-costs/${it.id}")
        }

    // ---- Helpers (caller's transaction) ----

    /** What even a draft must satisfy: the database's shape rules, so a draft can always be saved. */
    private fun validateShape(request: RunningCostRequest) {
        val method = request.paymentMethod!!
        val cashMethod = method == "PETTY_CASH" || method == "CASH"
        if (cashMethod && (request.payingCashAccountId == null || request.payingBankAccountId != null)) {
            throw ApiErrors.validationFailed(mapOf("payingCashAccountId" to "a ${method.lowercase().replace('_', ' ')} payment needs payingCashAccountId (and no payingBankAccountId)"))
        }
        if (!cashMethod && (request.payingBankAccountId == null || request.payingCashAccountId != null)) {
            throw ApiErrors.validationFailed(mapOf("payingBankAccountId" to "a ${method.lowercase().replace('_', ' ')} payment needs payingBankAccountId (and no payingCashAccountId)"))
        }
        if (method == "CHEQUE" && request.chequeNumber.isNullOrBlank()) throw ApiErrors.validationFailed(mapOf("chequeNumber" to "is required for a cheque"))
        val vat = request.inputVatAmount ?: BigDecimal.ZERO
        if (vat >= request.amount!!) throw ApiErrors.validationFailed(mapOf("inputVatAmount" to "must be less than the amount (it is the VAT included in it)"))
        if (vat.signum() > 0 && request.supplierTin.isNullOrBlank()) throw ApiErrors.validationFailed(mapOf("supplierTin" to "is required to claim input VAT"))
        support.requireNotFuture("paymentDate", request.paymentDate!!)
        request.locationId?.let { support.validateLocation(it) } ?: support.assertAccess(null)
    }

    private fun fill(it: UpdateBuilder<*>, request: RunningCostRequest) {
        it[OperatingCostsTable.locationId] = request.locationId
        it[OperatingCostsTable.expenseCategoryId] = request.expenseCategoryId!!
        it[OperatingCostsTable.payee] = request.payee.trim()
        it[OperatingCostsTable.amount] = Money.round(request.amount!!)
        it[OperatingCostsTable.paymentMethod] = request.paymentMethod!!
        it[OperatingCostsTable.payingCashAccountId] = request.payingCashAccountId
        it[OperatingCostsTable.payingBankAccountId] = request.payingBankAccountId
        it[OperatingCostsTable.chequeNumber] = request.chequeNumber?.trim()?.ifEmpty { null }
        it[OperatingCostsTable.invoiceOrReceiptNo] = request.invoiceOrReceiptNo?.trim()?.ifEmpty { null }
        it[OperatingCostsTable.inputVatAmount] = Money.round(request.inputVatAmount ?: BigDecimal.ZERO)
        it[OperatingCostsTable.supplierTin] = request.supplierTin?.trim()?.uppercase()?.ifEmpty { null }
        it[OperatingCostsTable.paymentDate] = request.paymentDate!!
        it[OperatingCostsTable.description] = request.description?.trim()?.ifEmpty { null }
    }

    /**
     * Submission checks (§4.9): usable category (petty cash needs one payable from petty cash), a
     * paying account of the right kind, enough cash for cash methods (checked again at approval),
     * and a non-blocking flag when the category's monthly cap would be passed.
     */
    private fun submitRow(row: ResultRow) {
        val id = row[OperatingCostsTable.id].value
        val method = row[OperatingCostsTable.paymentMethod]
        val category = categories.requireUsable(row[OperatingCostsTable.expenseCategoryId], forPettyCash = method == "PETTY_CASH")
        val account = payingAccount(row)
        val amount = row[OperatingCostsTable.amount]
        if (!account.bank && amount > account.balance) throw ApiErrors.insufficientCash(account.code, account.balance, amount)

        val categoryId = category[ExpenseCategoriesTable.id].value
        val exceeds = category[ExpenseCategoriesTable.monthlyCap]?.let { cap ->
            val monthStart = support.today().withDayOfMonth(1)
            val pending = OperatingCostsTable.selectAll().where {
                (OperatingCostsTable.tenantId eq support.tenantId()) and (OperatingCostsTable.expenseCategoryId eq categoryId) and
                    (OperatingCostsTable.status eq "PENDING_APPROVAL") and (OperatingCostsTable.paymentDate greaterEq monthStart)
            }.filter { it[OperatingCostsTable.id].value != id }.fold(BigDecimal.ZERO) { s, it -> s + it[OperatingCostsTable.amount] }
            (categories.spentThisMonth()[categoryId] ?: BigDecimal.ZERO) + pending + amount > cap
        } ?: false

        val ref = row[OperatingCostsTable.ref] ?: sequences.next("RC")
        OperatingCostsTable.update({ OperatingCostsTable.id eq id }) {
            it[OperatingCostsTable.ref] = ref
            it[status] = "PENDING_APPROVAL"
            it[exceedsMonthlyCap] = exceeds
            it[rejectionReason] = null
            it[approvedByUserId] = null
            it[approvedAt] = null
            it[submittedByUserId] = support.userId()
            it[submittedAt] = Instant.now()
        }
        val eventId = support.record("account.running-cost.submitted", "running_cost", id, ref,
            "$ref submitted: ${Money.naira(amount)} to ${row[OperatingCostsTable.payee]} (${category[ExpenseCategoriesTable.name]})",
            mapOf("amount" to amount, "exceedsMonthlyCap" to exceeds), row[OperatingCostsTable.locationId])
        notify.holdersOf(
            permission = "accounting.running-costs.approve", locationId = row[OperatingCostsTable.locationId], category = NotificationCategory.APPROVAL_REQUIRED,
            title = "Running cost awaiting approval — $ref",
            body = "$ref ${Money.naira(amount)} — ${row[OperatingCostsTable.payee]} (${category[ExpenseCategoriesTable.name]})" + (if (exceeds) ". Exceeds the category's monthly cap." else ""),
            linkType = "running-cost", linkRef = id.toString(), sourceEventId = eventId, exclude = support.userId(),
        )
    }

    /** The paying account must be active and of the kind the method needs. */
    private fun payingAccount(row: ResultRow): MoneyAccount = when (row[OperatingCostsTable.paymentMethod]) {
        "PETTY_CASH" -> moves.cash(row[OperatingCostsTable.payingCashAccountId]!!, setOf("PETTY_CASH"))
        "CASH" -> moves.cash(row[OperatingCostsTable.payingCashAccountId]!!, setOf("TILL", "VAULT"))
        else -> moves.bank(row[OperatingCostsTable.payingBankAccountId]!!)
    }

    private fun tellSubmitter(row: ResultRow, category: NotificationCategory, title: String, body: String, eventId: UUID) {
        val submitter = row[OperatingCostsTable.submittedByUserId] ?: return
        notify.inApp(listOf(submitter), category, title, body, "running-cost", row[OperatingCostsTable.id].value.toString(), eventId, exclude = support.userId())
    }

    /** Restricted users see their location's costs only; head-office costs only the unrestricted. */
    private fun visibility() = support.visibleLocations()?.let { OperatingCostsTable.locationId inList it }

    private fun row(id: UUID, forUpdate: Boolean = false): ResultRow {
        val query = OperatingCostsTable.selectAll().where { (OperatingCostsTable.id eq id) and (OperatingCostsTable.tenantId eq support.tenantId()) }
        if (forUpdate) query.forUpdate()
        val row = query.firstOrNull() ?: throw ApiErrors.runningCostNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[OperatingCostsTable.locationId] !in visible) throw ApiErrors.runningCostNotFound()
        return row
    }

    private val payingCash = CashAccountsTable.alias("pay_cash")
    private val payingBank = BankAccountsTable.alias("pay_bank")

    private fun joined() = OperatingCostsTable
        .join(ExpenseCategoriesTable, JoinType.INNER, OperatingCostsTable.expenseCategoryId, ExpenseCategoriesTable.id)
        .join(payingCash, JoinType.LEFT, OperatingCostsTable.payingCashAccountId, payingCash[CashAccountsTable.id])
        .join(payingBank, JoinType.LEFT, OperatingCostsTable.payingBankAccountId, payingBank[BankAccountsTable.id])

    private fun view(id: UUID): RunningCostView =
        toViews(joined().selectAll().where { (OperatingCostsTable.id eq id) and (OperatingCostsTable.tenantId eq support.tenantId()) }.toList()).single()

    private fun toViews(rows: List<ResultRow>): List<RunningCostView> {
        val locations = Names.locations(rows.mapNotNull { it[OperatingCostsTable.locationId] })
        return rows.map { row ->
            RunningCostView(
                id = row[OperatingCostsTable.id].value, ref = row[OperatingCostsTable.ref], date = row[OperatingCostsTable.paymentDate],
                description = row[OperatingCostsTable.description], expenseCategory = Ref.expenseCategory(row[OperatingCostsTable.expenseCategoryId]), payee = row[OperatingCostsTable.payee], method = row[OperatingCostsTable.paymentMethod],
                payingAccountId = row[OperatingCostsTable.payingCashAccountId] ?: row[OperatingCostsTable.payingBankAccountId]!!,
                payingAccount = row[payingCash[CashAccountsTable.code]] ?: row[payingBank[BankAccountsTable.code]],
                amount = row[OperatingCostsTable.amount], inputVatAmount = row[OperatingCostsTable.inputVatAmount], supplierTin = row[OperatingCostsTable.supplierTin],
                invoiceOrReceiptNo = row[OperatingCostsTable.invoiceOrReceiptNo], chequeNumber = row[OperatingCostsTable.chequeNumber],
                location = ref(row[OperatingCostsTable.locationId], Ref::location),
                status = row[OperatingCostsTable.status], exceedsMonthlyCap = row[OperatingCostsTable.exceedsMonthlyCap],
                submittedBy = UserRef.of(row[OperatingCostsTable.submittedByUserId]), submittedAt = row[OperatingCostsTable.submittedAt],
                approvedBy = UserRef.of(row[OperatingCostsTable.approvedByUserId]), approvedAt = row[OperatingCostsTable.approvedAt],
                rejectionReason = row[OperatingCostsTable.rejectionReason], reversalReason = row[OperatingCostsTable.reversalReason],
                createdAt = row[OperatingCostsTable.createdAt],
            )
        }
    }

    private val payingAccountId = RawExpression<UUID>({ +"coalesce("; +OperatingCostsTable.payingCashAccountId; +", "; +OperatingCostsTable.payingBankAccountId; +")" })
    private val hasInputVat = RawExpression<Boolean>({ +"("; +OperatingCostsTable.inputVatAmount; +" > 0)" })

    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("status", "Status", FilterType.ENUM, OperatingCostsTable.status,
                listOf("DRAFT", "PENDING_APPROVAL", "APPROVED", "REJECTED", "REVERSED").map { FilterOption(it, BankAccountService.label(it)) }, facetable = true),
            FilterField("expenseCategoryId", "Category", FilterType.UUID, OperatingCostsTable.expenseCategoryId, aliases = setOf("category"), lookup = "/api/v1/account/expense-categories/lookup", facetable = true),
            FilterField("paymentMethod", "Method", FilterType.ENUM, OperatingCostsTable.paymentMethod,
                listOf("PETTY_CASH", "CASH", "BANK_TRANSFER", "CHEQUE").map { FilterOption(it, BankAccountService.label(it)) }, aliases = setOf("method"), facetable = true),
            FilterField("locationId", "Location", FilterType.LOCATION, OperatingCostsTable.locationId),
            FilterField("payingAccountId", "Paying account", FilterType.UUID, payingAccountId),
            FilterField("submittedBy", "Submitted by", FilterType.UUID, OperatingCostsTable.submittedByUserId),
            FilterField("approvedBy", "Approved by", FilterType.UUID, OperatingCostsTable.approvedByUserId),
            FilterField("exceedsMonthlyCap", "Exceeds monthly cap", FilterType.BOOLEAN, OperatingCostsTable.exceedsMonthlyCap),
            FilterField("hasInputVat", "Has input VAT", FilterType.BOOLEAN, hasInputVat),
            FilterField("amount", "Amount", FilterType.MONEY, OperatingCostsTable.amount, sortable = true),
            FilterField("paymentDate", "Payment date", FilterType.DATE, OperatingCostsTable.paymentDate, sortable = true),
            FilterField("submittedAt", "Submitted", FilterType.DATETIME, OperatingCostsTable.submittedAt, sortable = true),
        ),
        primaryDateKey = "paymentDate",
        defaultSort = listOf("paymentDate" to SortOrder.DESC, "submittedAt" to SortOrder.DESC),
        tiebreaker = OperatingCostsTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(OperatingCostsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(OperatingCostsTable.invoiceOrReceiptNo, SearchKind.CODE, "Invoice / receipt no."),
            SearchField(OperatingCostsTable.chequeNumber, SearchKind.CODE, "Cheque"),
            SearchField(OperatingCostsTable.supplierTin, SearchKind.CODE, "Supplier TIN"),
            SearchField(OperatingCostsTable.payee, SearchKind.TRIGRAM, "Payee"),
            SearchField(RawExpression<String>({ +"operating_costs.search_vector" }), SearchKind.FULLTEXT, "Description"),
            SearchField(OperatingCostsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = filterSpec,
    )
}

/** Global search provider `running-cost` (docs/07 §9). */
@Component
class RunningCostSearchProvider(private val support: AccountSupport, private val runningCosts: RunningCostService) : SearchProvider {
    override val type = "running-cost"
    override val label = "Running costs"
    override val listRoute = "/account/running-costs"

    override fun canSearch() = support.can("accounting.read")

    override fun search(q: String, limit: Int): List<SearchHit> = runningCosts.search(q, limit)
}
