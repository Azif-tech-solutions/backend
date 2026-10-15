package com.zifiq.account.service

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.account.dto.AccountLookupRow
import com.zifiq.account.dto.BankAccountRequest
import com.zifiq.account.dto.BankAccountUpdateRequest
import com.zifiq.account.dto.BankAccountView
import com.zifiq.account.dto.BankEntryView
import com.zifiq.account.dto.BankStatementRow
import com.zifiq.account.dto.ManualBankEntryRequest
import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.BankTransactionsTable
import com.zifiq.account.repository.BankEntryType
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
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
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
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/** Bank Register, manual entries and the Bank Statement (docs/07 §4.1–4.2). */
@Service
class BankAccountService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val ledger: MoneyLedgerRepository,
    private val sequences: DocumentSequenceService,
    private val categories: ExpenseCategoryService,
) {
    // ---- Register ----

    fun register(request: BankAccountRequest): BankAccountView = tx.execute {
        support.validateLocation(request.locationId)
        val type = request.accountType!!
        val currency = currencyFor(type, request.currencyCode)
        val bank = request.bankName.trim()
        val number = request.accountNumber.trim()
        ensureNumberFree(bank, number, null)
        val openingDate = request.openingBalanceDate ?: support.today()
        if (openingDate.isAfter(support.today())) throw ApiErrors.validationFailed(mapOf("openingBalanceDate" to "can't be in the future"))
        val opening = Money.round(request.openingBalance ?: BigDecimal.ZERO)
        val code = generateSequence { sequences.next("BA") }.first { candidate ->
            BankAccountsTable.selectAll().where { (BankAccountsTable.tenantId eq support.tenantId()) and (BankAccountsTable.code eq candidate) }.empty()
        }

        val id = BankAccountsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[BankAccountsTable.code] = code
            it[bankName] = bank
            it[branch] = request.branch.clean()
            it[accountName] = request.accountName.trim()
            it[accountNumber] = number
            it[accountType] = type
            it[currencyCode] = currency
            it[sortCodeOrBic] = request.sortCodeOrBic.clean()
            it[authorisedSignatory] = request.authorisedSignatory.clean()
            it[relationshipManager] = request.relationshipManager.clean()
            it[dateOpened] = request.dateOpened
            it[openingBalance] = opening
            it[openingBalanceDate] = openingDate
            it[currentBalance] = BigDecimal.ZERO
            it[locationId] = request.locationId
            it[status] = "ACTIVE"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        // The opening balance is the first ledger entry, so statements and the balance cache agree from day one.
        if (opening.signum() != 0) {
            ledger.postBank(
                support.tenantId(), id, if (opening.signum() > 0) BankEntryType.CREDIT else BankEntryType.DEBIT, opening.abs(),
                LedgerPosting(code, "Opening balance", "OPENING", entryDate = openingDate, userId = support.userId()),
            )
        }
        if (!request.active) BankAccountsTable.update({ BankAccountsTable.id eq id }) { it[status] = "DORMANT" }
        support.record(
            "account.bank-account.registered", "bank_account", id, code, "Bank account $code registered — $bank, ${request.accountName.trim()}",
            mapOf("accountType" to type, "currency" to currency, "openingBalance" to opening), request.locationId,
        )
        view(row(id))
    }

    fun list(q: String?, accountTypes: Set<String>, status: String?, locationId: UUID?, currencyCode: String?, sort: String?): List<BankAccountView> = tx.execute {
        val parsed = searchSpec().parse(q)
        val condition = listOfNotNull(
            BankAccountsTable.tenantId eq support.tenantId(),
            visibility(),
            support.resolveLocation(locationId)?.let { BankAccountsTable.locationId eq it },
            accountTypes.takeIf { it.isNotEmpty() }?.let { types -> BankAccountsTable.accountType inList types.map { it.uppercase() } },
            status?.let { BankAccountsTable.status eq it.uppercase() },
            currencyCode?.let { BankAccountsTable.currencyCode eq it.uppercase() },
            searchSpec().condition(parsed),
        ).allOf()!!
        val sortColumn = when (sort?.substringBefore(',')?.trim()) {
            null, "", "bankName" -> BankAccountsTable.bankName
            "currentBalance" -> BankAccountsTable.currentBalance
            "dateOpened" -> BankAccountsTable.dateOpened
            else -> throw ApiErrors.invalidSortField(sort.substringBefore(','), listOf("bankName", "currentBalance", "dateOpened"))
        }
        val order = if (sort?.substringAfter(',', "")?.trim().equals("desc", true)) SortOrder.DESC_NULLS_LAST else SortOrder.ASC_NULLS_LAST
        val rows = BankAccountsTable.selectAll().where(condition)
            .orderBy(*(searchSpec().rank(parsed) + (sortColumn to order) + (BankAccountsTable.code to SortOrder.ASC)).toTypedArray()).toList()
        val names = Names.locations(rows.mapNotNull { it[BankAccountsTable.locationId] })
        rows.map { toView(it, names) }
    }

    fun get(id: UUID): BankAccountView = tx.execute { view(row(id)) }

    fun lookup(q: String?, activeOnly: Boolean): List<AccountLookupRow> =
        list(q, emptySet(), if (activeOnly) "ACTIVE" else null, null, null, null).take(50)
            .map { AccountLookupRow(it.id, it.code, "${it.bankName} · ${it.accountName}", "${it.accountNumberMasked} · ${it.currencyCode}", it.status) }

    fun update(id: UUID, request: BankAccountUpdateRequest): BankAccountView = tx.execute {
        val existing = row(id)
        support.assertAccess(existing[BankAccountsTable.locationId])
        support.validateLocation(request.locationId)
        val bank = request.bankName.trim()
        val number = request.accountNumber.trim()
        ensureNumberFree(bank, number, id)
        BankAccountsTable.update({ (BankAccountsTable.id eq id) and (BankAccountsTable.tenantId eq support.tenantId()) }) {
            it[bankName] = bank
            it[branch] = request.branch.clean()
            it[accountName] = request.accountName.trim()
            it[accountNumber] = number
            it[sortCodeOrBic] = request.sortCodeOrBic.clean()
            it[authorisedSignatory] = request.authorisedSignatory.clean()
            it[relationshipManager] = request.relationshipManager.clean()
            it[dateOpened] = request.dateOpened
            it[locationId] = request.locationId
        }
        support.record("account.bank-account.updated", "bank_account", id, existing[BankAccountsTable.code], "Bank account ${existing[BankAccountsTable.code]} updated", locationId = request.locationId)
        view(row(id))
    }

    /** A DORMANT account stays in historic statements but takes no new entries (rule A10). */
    fun changeStatus(id: UUID, status: String): BankAccountView = tx.execute {
        val existing = row(id)
        support.assertAccess(existing[BankAccountsTable.locationId])
        if (existing[BankAccountsTable.status] != status) {
            BankAccountsTable.update({ (BankAccountsTable.id eq id) and (BankAccountsTable.tenantId eq support.tenantId()) }) { it[BankAccountsTable.status] = status }
            support.record(
                "account.bank-account.status-changed", "bank_account", id, existing[BankAccountsTable.code],
                "Bank account ${existing[BankAccountsTable.code]} is now ${status.lowercase()}", mapOf("status" to status), existing[BankAccountsTable.locationId],
            )
        }
        view(row(id))
    }

    /** Bank charges, interest and reconciling items that appear on the real statement. A debit needs an expense category. */
    fun postManualEntry(id: UUID, request: ManualBankEntryRequest): BankEntryView = tx.execute {
        val account = row(id)
        support.assertAccess(account[BankAccountsTable.locationId])
        val type = BankEntryType.valueOf(request.entryType!!)
        val date = request.entryDate!!
        if (date.isAfter(support.today())) throw ApiErrors.validationFailed(mapOf("entryDate" to "can't be in the future"))
        if (date.isBefore(account[BankAccountsTable.openingBalanceDate])) {
            throw ApiErrors.validationFailed(mapOf("entryDate" to "can't be before the account's opening balance date (${account[BankAccountsTable.openingBalanceDate]})"))
        }
        if (type == BankEntryType.DEBIT && request.expenseCategoryId == null) throw ApiErrors.validationFailed(mapOf("expenseCategoryId" to "is required for a debit"))
        request.expenseCategoryId?.let { categories.requireUsable(it) }
        val amount = Money.round(request.amount!!)
        val reference = sequences.next("MAN")
        val posted = ledger.postBank(
            support.tenantId(), id, type, amount,
            LedgerPosting(reference, request.description.trim(), "MANUAL", expenseCategoryId = request.expenseCategoryId, entryDate = date, userId = support.userId()),
        )
        support.record(
            "account.bank.manual-entry-posted", "bank_account", id, reference,
            "$reference: ${type.name.lowercase()} of ${Money.naira(amount)} on ${account[BankAccountsTable.code]} — ${request.description.trim()}",
            mapOf("entryType" to type.name, "amount" to amount, "entryId" to posted.id), account[BankAccountsTable.locationId],
        )
        BankEntryView(posted.id, reference, type.name, amount, posted.balanceAfter, date, request.description.trim())
    }

    // ---- Bank Statement ----

    /**
     * The statement in date order with a running balance per row, plus opening/closing balances and
     * period totals. With no from/to/period it covers the current month to date.
     */
    fun statement(id: UUID, request: ListSearchRequest): PageResult<BankStatementRow> = tx.execute {
        val account = row(id)
        support.assertAccess(account[BankAccountsTable.locationId])
        val context = context()
        val hasPeriod = listOf("from", "to", "period").any { it in request.params }
        val params = if (hasPeriod) request.params else request.params + mapOf("from" to listOf(support.today().withDayOfMonth(1).toString()), "to" to listOf(support.today().toString()))
        val (from, to) = dateRange(params, context)
        val parsed = statementSearch.parse(request.q)
        val base = (BankTransactionsTable.tenantId eq support.tenantId()) and (BankTransactionsTable.bankAccountId eq id)
        val condition = listOfNotNull(base, statementSearch.condition(parsed), statementFilters.fromParams(params, context), statementFilters.fromJson(request.filter, context)).allOf()!!

        val inPeriod = listOfNotNull(base, from?.let { BankTransactionsTable.entryDate greaterEq it }, to?.let { BankTransactionsTable.entryDate lessEq it }).allOf()!!
        val opening = from?.let { LedgerSql.bankNet(base and (BankTransactionsTable.entryDate less it)) } ?: BigDecimal.ZERO
        val balances = LedgerSql.bankBalances(inPeriod)
        val query = BankTransactionsTable.join(balances.alias, JoinType.INNER, BankTransactionsTable.id, balances.id)
            .select(BankTransactionsTable.columns + balances.column).where(condition)
            .orderBy(BankTransactionsTable.entryDate to SortOrder.ASC, BankTransactionsTable.createdAt to SortOrder.ASC, BankTransactionsTable.id to SortOrder.ASC)
        val page = Paging.fetch(query, PageRequest.of(request.page, request.size)) { it }
        val categoryNames = categories.names(page.items.mapNotNull { it[BankTransactionsTable.expenseCategoryId] })
        val items = page.items.map { row ->
            val credit = row[BankTransactionsTable.entryType] == "CREDIT"
            BankStatementRow(
                id = row[BankTransactionsTable.id].value,
                date = row[BankTransactionsTable.entryDate],
                reference = row[BankTransactionsTable.reference],
                description = row[BankTransactionsTable.description],
                type = if (credit) "Credit" else "Debit",
                credit = if (credit) row[BankTransactionsTable.amount] else null,
                debit = if (credit) null else row[BankTransactionsTable.amount],
                balance = balances.after(row, opening),
                sourceType = row[BankTransactionsTable.sourceType],
                expenseCategoryName = row[BankTransactionsTable.expenseCategoryId]?.let(categoryNames::get),
                createdAt = row[BankTransactionsTable.createdAt],
            )
        }

        val summary = if (request.summary) {
            val closing = LedgerSql.bankNet(if (to == null) base else base and (BankTransactionsTable.entryDate lessEq to))
            val masked = mask(account[BankAccountsTable.accountNumber])
            mapOf(
                "account" to mapOf(
                    "id" to id, "code" to account[BankAccountsTable.code], "bankName" to account[BankAccountsTable.bankName],
                    "accountName" to account[BankAccountsTable.accountName], "accountNumberMasked" to masked,
                    "accountType" to account[BankAccountsTable.accountType], "currencyCode" to account[BankAccountsTable.currencyCode],
                    "branch" to account[BankAccountsTable.branch], "signatory" to account[BankAccountsTable.authorisedSignatory],
                    "status" to account[BankAccountsTable.status],
                ),
                "period" to mapOf("from" to from, "to" to to),
                "openingBalance" to Money.round(opening),
                "closingBalance" to Money.round(closing),
                "totalCredits" to total(inPeriod, "CREDIT"),
                "totalDebits" to total(inPeriod, "DEBIT"),
            )
        } else null
        page.withItems(items).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun statementFilterMetadata(): FilterMetadata = tx.execute { statementFilters.metadata(context()) }

    /** Global search: bank entries by reference, within the accounts the caller can see. */
    fun searchEntries(q: String, limit: Int): List<SearchHit> {
        val term = q.trim()
        if (term.length < 2) return emptyList()
        val accounts = BankAccountsTable.selectAll().where(listOfNotNull(BankAccountsTable.tenantId eq support.tenantId(), visibility()).allOf()!!)
            .associateBy { it[BankAccountsTable.id].value }
        if (accounts.isEmpty()) return emptyList()
        return BankTransactionsTable.selectAll()
            .where { (BankTransactionsTable.tenantId eq support.tenantId()) and (BankTransactionsTable.bankAccountId inList accounts.keys) and (BankTransactionsTable.reference.lowerCase() eq term.lowercase()) }
            .orderBy(BankTransactionsTable.createdAt to SortOrder.DESC).limit(limit)
            .map { row ->
                val account = accounts.getValue(row[BankTransactionsTable.bankAccountId])
                SearchHit(
                    type = "bank-transaction",
                    id = row[BankTransactionsTable.id].value,
                    title = row[BankTransactionsTable.reference],
                    subtitle = "${account[BankAccountsTable.code]} · ${row[BankTransactionsTable.entryType].lowercase()} ${Money.naira(row[BankTransactionsTable.amount])} · ${row[BankTransactionsTable.entryDate]}",
                    ref = row[BankTransactionsTable.reference],
                    route = "/account/bank-accounts/${account[BankAccountsTable.id].value}/statement?q=${row[BankTransactionsTable.reference]}",
                )
            }
    }

    // ---- Helpers (caller's transaction) ----

    /** Restricted users see only their location's accounts, never head office's. */
    private fun visibility(): Op<Boolean>? = support.visibleLocations()?.let { BankAccountsTable.locationId inList it }

    internal fun row(id: UUID): ResultRow {
        val row = BankAccountsTable.selectAll().where { (BankAccountsTable.id eq id) and (BankAccountsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.bankAccountNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[BankAccountsTable.locationId] !in visible) throw ApiErrors.bankAccountNotFound()
        return row
    }

    private fun view(row: ResultRow) = toView(row, Names.locations(listOfNotNull(row[BankAccountsTable.locationId])))

    private fun toView(row: ResultRow, locationNames: Map<UUID, String>): BankAccountView {
        val number = row[BankAccountsTable.accountNumber]
        val masked = mask(number)
        return BankAccountView(
            id = row[BankAccountsTable.id].value,
            code = row[BankAccountsTable.code],
            bankName = row[BankAccountsTable.bankName],
            branch = row[BankAccountsTable.branch],
            accountName = row[BankAccountsTable.accountName],
            accountNumber = if (support.can(MANAGE)) number else masked,
            accountNumberMasked = masked,
            accountType = row[BankAccountsTable.accountType],
            currencyCode = row[BankAccountsTable.currencyCode],
            sortCodeOrBic = row[BankAccountsTable.sortCodeOrBic],
            authorisedSignatory = row[BankAccountsTable.authorisedSignatory],
            relationshipManager = row[BankAccountsTable.relationshipManager],
            dateOpened = row[BankAccountsTable.dateOpened],
            openingBalance = row[BankAccountsTable.openingBalance],
            openingBalanceDate = row[BankAccountsTable.openingBalanceDate],
            currentBalance = row[BankAccountsTable.currentBalance],
            location = ref(row[BankAccountsTable.locationId], Ref::location),
            status = row[BankAccountsTable.status],
            createdAt = row[BankAccountsTable.createdAt],
        )
    }

    /** DOMICILIARY accounts hold a tracked foreign currency; every other type is in naira. */
    private fun currencyFor(type: String, requested: String?): String {
        val code = requested?.trim()?.uppercase()
        if (type != "DOMICILIARY") {
            if (code != null && code != "NGN") throw ApiErrors.validationFailed(mapOf("currencyCode" to "only DOMICILIARY accounts hold a foreign currency"))
            return "NGN"
        }
        val tracked = support.moduleSettings().stringList(AccountingSettings.TRACKED_CURRENCIES)
        if (code == null) throw ApiErrors.validationFailed(mapOf("currencyCode" to "is required for a DOMICILIARY account"))
        if (code !in tracked) throw ApiErrors.currencyNotTracked(code, tracked)
        return code
    }

    private fun ensureNumberFree(bank: String, number: String, exceptId: UUID?) {
        val clash = BankAccountsTable.selectAll()
            .where { (BankAccountsTable.tenantId eq support.tenantId()) and (BankAccountsTable.accountNumber eq number) and (BankAccountsTable.bankName.lowerCase() eq bank.lowercase()) }
            .any { it[BankAccountsTable.id].value != exceptId }
        if (clash) throw ApiErrors.duplicateAccountNumber(bank, number)
    }

    /**
     * Account numbers are searchable only by those who can see them in full (docs/07 §9); for others
     * a 4-digit term matches the last four digits, which they can see.
     */
    private fun searchSpec() = SearchSpec(
        fields = listOfNotNull(
            SearchField(BankAccountsTable.code, SearchKind.REFERENCE, "Code"),
            SearchField(BankAccountsTable.bankName, SearchKind.TRIGRAM, "Bank"),
            SearchField(BankAccountsTable.accountName, SearchKind.TRIGRAM, "Account name"),
            SearchField(BankAccountsTable.branch, SearchKind.TRIGRAM, "Branch"),
            if (support.can(MANAGE)) SearchField(BankAccountsTable.accountNumber, SearchKind.CODE, "Account number")
            else SearchField(RawExpression<String>({ +"right("; +BankAccountsTable.accountNumber; +", 4)" }), SearchKind.CODE, "Account number (last 4)"),
        ),
    )

    private val statementFilters = FilterSpec(
        fields = listOf(
            FilterField("entryType", "Type", FilterType.ENUM, BankTransactionsTable.entryType, listOf(FilterOption("CREDIT", "Credit"), FilterOption("DEBIT", "Debit")), facetable = true),
            FilterField("sourceType", "Source", FilterType.ENUM, BankTransactionsTable.sourceType, SOURCE_TYPES.map { FilterOption(it, label(it)) }, aliases = setOf("source"), facetable = true),
            FilterField("amount", "Amount", FilterType.MONEY, BankTransactionsTable.amount),
            FilterField("expenseCategoryId", "Expense category", FilterType.UUID, BankTransactionsTable.expenseCategoryId, aliases = setOf("category"), lookup = "/api/v1/account/expense-categories/lookup"),
            FilterField("entryDate", "Date", FilterType.DATE, BankTransactionsTable.entryDate),
        ),
        primaryDateKey = "entryDate",
        tiebreaker = BankTransactionsTable.id,
    )

    private val statementSearch = SearchSpec(
        fields = listOf(
            SearchField(BankTransactionsTable.reference, SearchKind.REFERENCE, "Reference"),
            SearchField(RawExpression<String>({ +"bank_transactions.search_vector" }), SearchKind.FULLTEXT, "Description"),
            SearchField(BankTransactionsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = statementFilters,
    )

    private fun total(condition: Op<Boolean>, type: String): BigDecimal {
        val sum = Sum(BankTransactionsTable.amount, DecimalColumnType(16, 2))
        return Money.round(BankTransactionsTable.select(sum).where(condition and (BankTransactionsTable.entryType eq type)).single()[sum] ?: BigDecimal.ZERO)
    }

    private fun context() = FilterContext(costVisible = true, zone = support.moduleSettings().zone())

    private fun dateRange(params: Map<String, List<String>>, context: FilterContext): Pair<LocalDate?, LocalDate?> {
        val period = params["period"]?.firstOrNull()?.let { DatePeriod.parse(it) }?.resolve(context.today)
        val from = params["from"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: period?.from
        val to = params["to"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: period?.to
        return from to to
    }

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

    companion object {
        const val MANAGE = "accounting.bank.manage"

        val SOURCE_TYPES = listOf(
            "OPENING", "CASH_LODGEMENT", "PETTY_CASH_FUNDING", "RUNNING_COST", "RECEIPT_CONFIRMED", "RECEIPT_REVERSED",
            "FUND_DISBURSEMENT_OUT", "FUND_DISBURSEMENT_IN", "FUND_DISBURSEMENT_RETURN", "PETTY_CASH_ALLOCATION_OUT",
            "PETTY_CASH_ALLOCATION_RETURN", "WALLET_WITHDRAWAL", "DEPOSIT_REFUND", "COMMISSION_PAYOUT", "MANUAL",
        )

        fun mask(number: String): String = "•••• " + number.takeLast(4)

        fun label(code: String): String = code.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)
    }
}

/** Global search provider `bank-transaction` (docs/07 §9): an exact bank reference finds its entry. */
@Component
class BankTransactionSearchProvider(
    private val support: AccountSupport,
    private val bankAccounts: BankAccountService,
) : SearchProvider {
    override val type = "bank-transaction"
    override val label = "Bank transactions"
    override val listRoute = "/account/bank-accounts"

    override fun canSearch() = support.can("accounting.read")

    override fun search(q: String, limit: Int): List<SearchHit> = bankAccounts.searchEntries(q, limit)
}
