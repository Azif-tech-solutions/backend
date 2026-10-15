package com.zifiq.account.service

import com.zifiq.platform.directory.Refs
import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.account.dto.AccountLookupRow
import com.zifiq.account.dto.CashAccountRequest
import com.zifiq.account.dto.CashAccountUpdateRequest
import com.zifiq.account.dto.CashAccountView
import com.zifiq.account.dto.CashCountRequest
import com.zifiq.account.dto.CashCountResult
import com.zifiq.account.dto.CashFlowRow
import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.CashTransactionsTable
import com.zifiq.account.repository.CashDirection
import com.zifiq.account.repository.LedgerPosting
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.DatePeriod
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
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.DecimalColumnType
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Sum
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Cash accounts — tills, petty-cash boxes and vaults (docs/07 §4.3) — their cash counts, and the
 * Cash Inflow/Outflow Statement over them (§4.4).
 */
@Service
class CashAccountService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val ledger: MoneyLedgerRepository,
    private val sequences: DocumentSequenceService,
    private val categories: ExpenseCategoryService,
    private val notify: Notify,
) {
    // ---- Register ----

    fun register(request: CashAccountRequest): CashAccountView = tx.execute {
        support.validateLocation(request.locationId)
        val kind = request.kind!!
        val code = request.code.trim().uppercase()
        if (!CashAccountsTable.selectAll().where { (CashAccountsTable.tenantId eq support.tenantId()) and (CashAccountsTable.code.lowerCase() eq code.lowercase()) }.empty()) {
            throw ApiErrors.duplicateCode(code)
        }
        validateKindFields(kind, request.floatLimit, request.posTerminalRef, request.posSettlementBankAccountId)
        val currency = request.currencyCode?.trim()?.uppercase() ?: "NGN"
        if (currency != "NGN" && currency !in support.moduleSettings().stringList(AccountingSettings.TRACKED_CURRENCIES)) {
            throw ApiErrors.currencyNotTracked(currency, support.moduleSettings().stringList(AccountingSettings.TRACKED_CURRENCIES))
        }
        val opening = Money.round(request.openingBalance ?: BigDecimal.ZERO)

        val id = CashAccountsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[CashAccountsTable.code] = code
            it[name] = request.name.trim()
            it[CashAccountsTable.kind] = kind
            it[locationId] = request.locationId
            it[currencyCode] = currency
            it[openingBalance] = opening
            it[currentBalance] = BigDecimal.ZERO
            it[floatLimit] = request.floatLimit
            it[posTerminalRef] = request.posTerminalRef?.trim()?.ifEmpty { null }
            it[posSettlementBankAccountId] = request.posSettlementBankAccountId
            it[status] = "ACTIVE"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        if (opening.signum() > 0) {
            ledger.postCash(support.tenantId(), id, CashDirection.IN, opening, LedgerPosting(code, "Opening balance", "OPENING", entryDate = support.today(), userId = support.userId()))
        }
        support.record(
            "account.cash-account.registered", "cash_account", id, code, "Cash account $code (${kind.lowercase().replace('_', ' ')}) registered",
            mapOf("kind" to kind, "openingBalance" to opening), request.locationId,
        )
        view(row(id))
    }

    fun list(q: String?, kinds: Set<String>, locationId: UUID?, status: String?, belowFloat: Boolean?): List<CashAccountView> = tx.execute {
        val parsed = accountSearch.parse(q)
        val condition = listOfNotNull(
            CashAccountsTable.tenantId eq support.tenantId(),
            visibility(),
            support.resolveLocation(locationId)?.let { CashAccountsTable.locationId eq it },
            kinds.takeIf { it.isNotEmpty() }?.let { values -> CashAccountsTable.kind inList values.map { it.uppercase() } },
            status?.let { CashAccountsTable.status eq it.uppercase() },
            accountSearch.condition(parsed),
        ).allOf()!!
        val rows = CashAccountsTable.selectAll().where(condition)
            .orderBy(*(accountSearch.rank(parsed) + (CashAccountsTable.code to SortOrder.ASC)).toTypedArray()).toList()
        toViews(rows).filter { belowFloat == null || it.belowFloat == belowFloat }
    }

    fun get(id: UUID): CashAccountView = tx.execute { view(row(id)) }

    fun lookup(q: String?, kinds: Set<String>, locationId: UUID?, activeOnly: Boolean): List<AccountLookupRow> =
        list(q, kinds, locationId, if (activeOnly) "ACTIVE" else null, null).take(50).also { tx.execute { Refs.resolve(it) } }
            .map { AccountLookupRow(it.id, it.code, it.name, listOfNotNull(it.kind.lowercase().replace('_', ' '), it.location?.name ?: "Head office", Money.naira(it.currentBalance)).joinToString(" · "), it.status) }

    fun update(id: UUID, request: CashAccountUpdateRequest): CashAccountView = tx.execute {
        val existing = row(id)
        support.assertAccess(existing[CashAccountsTable.locationId])
        validateKindFields(existing[CashAccountsTable.kind], request.floatLimit, request.posTerminalRef, request.posSettlementBankAccountId)
        CashAccountsTable.update({ (CashAccountsTable.id eq id) and (CashAccountsTable.tenantId eq support.tenantId()) }) {
            it[name] = request.name.trim()
            it[floatLimit] = request.floatLimit
            it[posTerminalRef] = request.posTerminalRef?.trim()?.ifEmpty { null }
            it[posSettlementBankAccountId] = request.posSettlementBankAccountId
        }
        support.record("account.cash-account.updated", "cash_account", id, existing[CashAccountsTable.code], "Cash account ${existing[CashAccountsTable.code]} updated", locationId = existing[CashAccountsTable.locationId])
        view(row(id))
    }

    /** Deactivating needs an empty account: cash still in it would vanish from every statement. */
    fun changeStatus(id: UUID, status: String): CashAccountView = tx.execute {
        val existing = row(id)
        support.assertAccess(existing[CashAccountsTable.locationId])
        val code = existing[CashAccountsTable.code]
        if (status == "INACTIVE" && existing[CashAccountsTable.currentBalance].signum() != 0) throw ApiErrors.accountHasBalance(code, existing[CashAccountsTable.currentBalance])
        if (existing[CashAccountsTable.status] != status) {
            CashAccountsTable.update({ (CashAccountsTable.id eq id) and (CashAccountsTable.tenantId eq support.tenantId()) }) { it[CashAccountsTable.status] = status }
            support.record("account.cash-account.status-changed", "cash_account", id, code, "Cash account $code is now ${status.lowercase()}", mapOf("status" to status), existing[CashAccountsTable.locationId])
        }
        view(row(id))
    }

    /**
     * A physical count: the difference from the book balance is posted as a CASH_COUNT_ADJUSTMENT so
     * the books match the drawer, and a variance alerts Management (it feeds anomaly detection).
     */
    fun recordCount(id: UUID, request: CashCountRequest): CashCountResult = tx.execute {
        val account = row(id)
        support.assertAccess(account[CashAccountsTable.locationId])
        val code = account[CashAccountsTable.code]
        val date = request.countDate!!
        if (date.isAfter(support.today())) throw ApiErrors.validationFailed(mapOf("countDate" to "can't be in the future"))
        val counted = Money.round(request.countedAmount!!)
        val book = account[CashAccountsTable.currentBalance]
        val variance = counted - book
        val note = request.note?.trim()?.ifEmpty { null }
        if (variance.signum() != 0 && note == null) throw ApiErrors.validationFailed(mapOf("note" to "explain the difference between the count and the book balance"))

        var reference: String? = null
        if (variance.signum() != 0) {
            reference = sequences.next("CC")
            val remark = "Cash count ${if (variance.signum() > 0) "surplus" else "shortage"}: $note"
            val posted = ledger.postCash(
                support.tenantId(), id, if (variance.signum() > 0) CashDirection.IN else CashDirection.OUT, variance.abs(),
                LedgerPosting(reference, remark, "CASH_COUNT_ADJUSTMENT", entryDate = date, userId = support.userId()),
            )
            val eventId = support.record(
                "account.cash.count-variance", "cash_account", id, reference,
                "$reference: $code counted ${Money.naira(counted)} against ${Money.naira(book)} (${if (variance.signum() > 0) "+" else ""}${Money.naira(variance)})",
                mapOf("book" to book, "counted" to counted, "variance" to variance, "entryId" to posted.id), account[CashAccountsTable.locationId],
            )
            notify.holdersOf(
                permission = MANAGEMENT_ALERTS, locationId = account[CashAccountsTable.locationId], category = NotificationCategory.ALERT,
                title = "Cash count variance — $code",
                body = "${account[CashAccountsTable.name]}: counted ${Money.naira(counted)}, book ${Money.naira(book)}. $note",
                linkType = "cash-account", linkRef = id.toString(), sourceEventId = eventId, exclude = support.userId(),
            )
        }
        CashCountResult(view(row(id)), book, counted, variance, reference)
    }

    // ---- Cash Inflow/Outflow Statement ----

    /**
     * Every cash movement across the accounts in scope, in date order, with a running balance over
     * all of them. Scope: one location's accounts (a restricted user always gets their own), or
     * company-wide when no location is given; narrowed by `cashAccountId` / `kind`. With no
     * from/to/period it covers the current month to date.
     */
    fun cashFlow(request: ListSearchRequest): PageResult<CashFlowRow> = tx.execute {
        val context = context()
        val requestedLocation = request.params["locationId"]?.firstOrNull()?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ApiErrors.invalidFilter("locationId", "'$it' is not a valid uuid") } }
        val location = support.resolveLocation(requestedLocation)
        val accountIds = request.params["cashAccountId"].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            .map { runCatching { UUID.fromString(it) }.getOrElse { _ -> throw ApiErrors.invalidFilter("cashAccountId", "'$it' is not a valid uuid") } }
        val kinds = request.params["kind"].orEmpty().flatMap { it.split(',') }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }

        val scopeAccounts = CashAccountsTable.selectAll().where(
            listOfNotNull(
                CashAccountsTable.tenantId eq support.tenantId(),
                visibility(),
                location?.let { CashAccountsTable.locationId eq it },
                accountIds.takeIf { it.isNotEmpty() }?.let { CashAccountsTable.id inList it },
                kinds.takeIf { it.isNotEmpty() }?.let { CashAccountsTable.kind inList it },
            ).allOf()!!,
        ).orderBy(CashAccountsTable.code).toList()
        val scopeIds = scopeAccounts.map { it[CashAccountsTable.id].value }

        val hasPeriod = listOf("from", "to", "period").any { it in request.params }
        val params = (if (hasPeriod) request.params else request.params + mapOf("from" to listOf(support.today().withDayOfMonth(1).toString()), "to" to listOf(support.today().toString())))
            .filterKeys { it !in SCOPE_PARAMS }
        val (from, to) = dateRange(params, context)
        val parsed = flowSearch.parse(request.q)
        val base = (CashTransactionsTable.tenantId eq support.tenantId()) and (CashTransactionsTable.cashAccountId inList scopeIds.ifEmpty { listOf(NO_ACCOUNT) })
        val condition = listOfNotNull(base, flowSearch.condition(parsed), flowFilters.fromParams(params, context), flowFilters.fromJson(request.filter, context)).allOf()!!

        val inPeriod = listOfNotNull(base, from?.let { CashTransactionsTable.entryDate greaterEq it }, to?.let { CashTransactionsTable.entryDate lessEq it }).allOf()!!
        val opening = from?.let { LedgerSql.cashNet(base and (CashTransactionsTable.entryDate less it)) } ?: BigDecimal.ZERO
        val balances = LedgerSql.cashBalances(inPeriod)
        val joined = CashTransactionsTable.join(balances.alias, JoinType.INNER, CashTransactionsTable.id, balances.id)
            .join(CashAccountsTable, JoinType.INNER, CashTransactionsTable.cashAccountId, CashAccountsTable.id)
        val query = joined.select(CashTransactionsTable.columns + CashAccountsTable.code + CashAccountsTable.name + CashAccountsTable.kind + balances.column).where(condition)
            .orderBy(CashTransactionsTable.entryDate to SortOrder.ASC, CashTransactionsTable.createdAt to SortOrder.ASC, CashTransactionsTable.id to SortOrder.ASC)
        val pageRequest = PageRequest.of(request.page, request.size)
        val page = Paging.fetch(query, pageRequest) { it }
        val categoryNames = categories.names(page.items.mapNotNull { it[CashTransactionsTable.expenseCategoryId] })
        val items = page.items.mapIndexed { index, row ->
            val inflow = row[CashTransactionsTable.direction] == "IN"
            CashFlowRow(
                sn = pageRequest.offset + index + 1,
                id = row[CashTransactionsTable.id].value,
                date = row[CashTransactionsTable.entryDate],
                reference = row[CashTransactionsTable.reference],
                remark = row[CashTransactionsTable.remark],
                inflow = if (inflow) row[CashTransactionsTable.amount] else null,
                outflow = if (inflow) null else row[CashTransactionsTable.amount],
                balance = balances.after(row, opening),
                accountCode = row[CashAccountsTable.code],
                accountName = row[CashAccountsTable.name],
                kind = row[CashAccountsTable.kind],
                sourceType = row[CashTransactionsTable.sourceType],
                expenseCategoryName = row[CashTransactionsTable.expenseCategoryId]?.let(categoryNames::get),
            )
        }

        val summary = if (request.summary) {
            mapOf(
                "scope" to mapOf(
                    "locationId" to location,
                    "locationName" to location?.let { Names.locations(listOf(it))[it] },
                    "accounts" to scopeAccounts.map { mapOf("id" to it[CashAccountsTable.id].value, "code" to it[CashAccountsTable.code], "name" to it[CashAccountsTable.name], "kind" to it[CashAccountsTable.kind]) },
                ),
                "period" to mapOf("from" to from, "to" to to),
                "openingCash" to Money.round(opening),
                "totalInflow" to total(inPeriod, "IN"),
                "totalOutflow" to total(inPeriod, "OUT"),
                "closingCash" to LedgerSql.cashNet(if (to == null) base else base and (CashTransactionsTable.entryDate lessEq to)),
            )
        } else null
        page.withItems(items).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun cashFlowFilterMetadata(): FilterMetadata = tx.execute { flowFilters.metadata(context()) }

    // ---- Helpers (caller's transaction) ----

    private fun visibility(): Op<Boolean>? = support.visibleLocations()?.let { CashAccountsTable.locationId inList it }

    internal fun row(id: UUID): ResultRow {
        val row = CashAccountsTable.selectAll().where { (CashAccountsTable.id eq id) and (CashAccountsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.cashAccountNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[CashAccountsTable.locationId] !in visible) throw ApiErrors.cashAccountNotFound()
        return row
    }

    private fun view(row: ResultRow) = toViews(listOf(row)).single()

    private fun toViews(rows: List<ResultRow>): List<CashAccountView> {
        val locations = Names.locations(rows.mapNotNull { it[CashAccountsTable.locationId] })
        val bankIds = rows.mapNotNull { it[CashAccountsTable.posSettlementBankAccountId] }
        val banks = if (bankIds.isEmpty()) emptyMap() else BankAccountsTable.select(BankAccountsTable.id, BankAccountsTable.code)
            .where { BankAccountsTable.id inList bankIds }.associate { it[BankAccountsTable.id].value to it[BankAccountsTable.code] }
        return rows.map { row ->
            val balance = row[CashAccountsTable.currentBalance]
            val limit = row[CashAccountsTable.floatLimit]
            CashAccountView(
                id = row[CashAccountsTable.id].value,
                code = row[CashAccountsTable.code],
                name = row[CashAccountsTable.name],
                kind = row[CashAccountsTable.kind],
                location = ref(row[CashAccountsTable.locationId], Ref::location),
                currencyCode = row[CashAccountsTable.currencyCode],
                openingBalance = row[CashAccountsTable.openingBalance],
                currentBalance = balance,
                floatLimit = limit,
                belowFloat = limit != null && balance * BigDecimal(2) < limit,
                posTerminalRef = row[CashAccountsTable.posTerminalRef],
                posSettlementBankAccount = ref(row[CashAccountsTable.posSettlementBankAccountId], Ref::bankAccount),
                status = row[CashAccountsTable.status],
                createdAt = row[CashAccountsTable.createdAt],
            )
        }
    }

    /** A float limit belongs to petty cash; a terminal and settlement account to tills. */
    private fun validateKindFields(kind: String, floatLimit: BigDecimal?, terminal: String?, settlementAccountId: UUID?) {
        if (floatLimit != null && kind != "PETTY_CASH") throw ApiErrors.validationFailed(mapOf("floatLimit" to "only petty-cash accounts have a float limit"))
        if (!terminal.isNullOrBlank() && kind != "TILL") throw ApiErrors.validationFailed(mapOf("posTerminalRef" to "only tills have a card terminal"))
        if (settlementAccountId != null) {
            if (kind != "TILL") throw ApiErrors.validationFailed(mapOf("posSettlementBankAccountId" to "only tills have a settlement account"))
            val bank = BankAccountsTable.selectAll().where { (BankAccountsTable.id eq settlementAccountId) and (BankAccountsTable.tenantId eq support.tenantId()) }
                .firstOrNull() ?: throw ApiErrors.bankAccountNotFound()
            if (bank[BankAccountsTable.status] != "ACTIVE") throw ApiErrors.accountNotActive(bank[BankAccountsTable.code])
        }
    }

    private val accountSearch = SearchSpec(
        fields = listOf(
            SearchField(CashAccountsTable.code, SearchKind.CODE, "Code"),
            SearchField(CashAccountsTable.name, SearchKind.TRIGRAM, "Name"),
            SearchField(CashAccountsTable.posTerminalRef, SearchKind.CODE, "Terminal"),
        ),
    )

    private val flowFilters = FilterSpec(
        fields = listOf(
            FilterField("direction", "Direction", FilterType.ENUM, CashTransactionsTable.direction, listOf(FilterOption("IN", "In-flow"), FilterOption("OUT", "Out-flow")), facetable = true),
            FilterField("sourceType", "Source", FilterType.ENUM, CashTransactionsTable.sourceType, CASH_SOURCE_TYPES.map { FilterOption(it, BankAccountService.label(it)) }, aliases = setOf("source"), facetable = true),
            FilterField("expenseCategoryId", "Expense category", FilterType.UUID, CashTransactionsTable.expenseCategoryId, aliases = setOf("category"), lookup = "/api/v1/account/expense-categories/lookup"),
            FilterField("amount", "Amount", FilterType.MONEY, CashTransactionsTable.amount),
            FilterField("entryDate", "Date", FilterType.DATE, CashTransactionsTable.entryDate),
        ),
        primaryDateKey = "entryDate",
        tiebreaker = CashTransactionsTable.id,
    )

    private val flowSearch = SearchSpec(
        fields = listOf(
            SearchField(CashTransactionsTable.reference, SearchKind.REFERENCE, "Reference"),
            SearchField(RawExpression<String>({ +"cash_transactions.search_vector" }), SearchKind.FULLTEXT, "Remark"),
            SearchField(CashTransactionsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = flowFilters,
    )

    private fun total(condition: Op<Boolean>, direction: String): BigDecimal {
        val sum = Sum(CashTransactionsTable.amount, DecimalColumnType(16, 2))
        return Money.round(CashTransactionsTable.select(sum).where(condition and (CashTransactionsTable.direction eq direction)).single()[sum] ?: BigDecimal.ZERO)
    }

    private fun context() = FilterContext(costVisible = true, zone = support.moduleSettings().zone())

    private fun dateRange(params: Map<String, List<String>>, context: FilterContext): Pair<LocalDate?, LocalDate?> {
        val period = params["period"]?.firstOrNull()?.let { DatePeriod.parse(it) }?.resolve(context.today)
        val from = params["from"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: period?.from
        val to = params["to"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: period?.to
        return from to to
    }

    companion object {
        /** Notified of Management-level alerts: only Administrator and Management hold it by default. */
        const val MANAGEMENT_ALERTS = "platform.activity.read"

        /** Scope parameters of the cash-flow statement, handled before the row filters. */
        private val SCOPE_PARAMS = setOf("locationId", "cashAccountId", "kind")

        /** Matches nothing: keeps `IN ()` valid when the scope is empty. */
        private val NO_ACCOUNT = UUID(0, 0)

        val CASH_SOURCE_TYPES = listOf(
            "OPENING", "RECEIPT", "REFUND", "CASH_LODGEMENT", "PETTY_CASH_FUNDING", "PETTY_CASH_PAYMENT", "RUNNING_COST",
            "FUND_DISBURSEMENT_OUT", "FUND_DISBURSEMENT_IN", "FUND_DISBURSEMENT_RETURN",
            "PETTY_CASH_ALLOCATION_OUT", "PETTY_CASH_ALLOCATION_IN", "PETTY_CASH_ALLOCATION_RETURN",
            "WALLET_WITHDRAWAL", "DEPOSIT_REFUND", "COMMISSION_PAYOUT", "CASH_COUNT_ADJUSTMENT",
        )
    }
}
