package com.zifiq.account.service

import org.jetbrains.exposed.sql.SqlExpressionBuilder.notLike
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.countDistinct
import com.zifiq.common.sql.Aggregates
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.account.dto.AccountBrief
import com.zifiq.account.dto.CategoryUtilisation
import com.zifiq.account.dto.FundingEntry
import com.zifiq.account.dto.PettyCashAllocationRequest
import com.zifiq.account.dto.PettyCashAllocationRow
import com.zifiq.account.dto.PettyCashFundingRequest
import com.zifiq.account.dto.PettyCashFundingRow
import com.zifiq.account.dto.PettyCashHistoryRow
import com.zifiq.account.dto.PettyCashLedgerLine
import com.zifiq.account.dto.PettyCashPaymentRequest
import com.zifiq.account.dto.PettyCashPaymentView
import com.zifiq.account.dto.PettyCashStatementSection
import com.zifiq.account.dto.PettyCashStatementSummary
import com.zifiq.account.dto.PettyCashStatementView
import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.CashTransactionsTable
import com.zifiq.account.model.ExpenseCategoriesTable
import com.zifiq.account.model.OperatingCostsTable
import com.zifiq.account.model.PettyCashAllocationsTable
import com.zifiq.account.model.PettyCashFundingsTable
import com.zifiq.account.model.PettyCashPaymentsTable
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
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Petty cash (docs/07 §4.6–4.8): funding a petty-cash box, paying from it, sending petty cash to a
 * branch for it to accept or reject, the Payment History and the Petty Cash Statement. Every
 * document posts through the cash/bank ledgers, so the Cash Inflow/Outflow Statement and the bank
 * statements show the same movements.
 */
@Service
class PettyCashService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val moves: MoneyMoves,
    private val sequences: DocumentSequenceService,
    private val categories: ExpenseCategoryService,
    private val notify: Notify,
) {
    // ---- Fund / Credit Petty Cash ----

    /** Funding appears as a debit on the source and a receipt into the petty cash; the float limit can't be passed. */
    fun fund(request: PettyCashFundingRequest): PettyCashFundingRow = tx.execute {
        val target = moves.cash(request.pettyCashAccountId!!, setOf("PETTY_CASH"))
        val source = moves.source(request.sourceKind!!, request.sourceBankAccountId, request.sourceCashAccountId)
        if (!source.bank && source.id == target.id) throw ApiErrors.validationFailed(mapOf("sourceCashAccountId" to "can't fund a petty cash box from itself"))
        val amount = Money.round(request.amount!!)
        val date = request.fundingDate!!
        support.requireNotFuture("fundingDate", date)
        target.floatLimit?.let { limit -> if (target.balance + amount > limit) throw ApiErrors.floatLimitExceeded(target.code, limit, target.balance + amount) }

        val ref = sequences.next("PCF")
        val id = PettyCashFundingsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[PettyCashFundingsTable.ref] = ref
            it[pettyCashAccountId] = target.id
            it[sourceKind] = request.sourceKind
            it[sourceBankAccountId] = source.id.takeIf { source.bank }
            it[sourceCashAccountId] = source.id.takeUnless { source.bank }
            it[PettyCashFundingsTable.amount] = amount
            it[fundingDate] = date
            it[transferOrChequeReference] = request.transferOrChequeReference.clean()
            it[authorisedBy] = request.authorisedBy.clean()
            it[description] = request.description.clean()
            it[status] = "POSTED"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        val remark = request.description.clean() ?: "Petty cash top-up for ${target.code}"
        moves.out(source, amount, LedgerPosting(ref, "$remark — $ref", "PETTY_CASH_FUNDING", id, entryDate = date, userId = support.userId()))
        moves.into(target, amount, LedgerPosting(ref, "Funding from ${source.label} — $ref", "PETTY_CASH_FUNDING", id, entryDate = date, userId = support.userId()))
        support.record("account.petty-cash.funded", "petty_cash_funding", id, ref, "$ref: ${Money.naira(amount)} into ${target.code} from ${source.code}",
            mapOf("amount" to amount, "source" to source.code), target.locationId)
        fundingRows(PettyCashFundingsTable.id eq id).single()
    }

    /** Only while the petty cash still holds the money; the source is credited back. */
    fun reverseFunding(id: UUID, reason: String): PettyCashFundingRow = tx.execute {
        val row = PettyCashFundingsTable.selectAll().where { (PettyCashFundingsTable.id eq id) and (PettyCashFundingsTable.tenantId eq support.tenantId()) }
            .forUpdate().firstOrNull() ?: throw ApiErrors.pettyCashFundingNotFound()
        val target = moves.existing(false, row[PettyCashFundingsTable.pettyCashAccountId])
        support.assertAccess(target.locationId)
        if (row[PettyCashFundingsTable.status] != "POSTED") throw ApiErrors.alreadyReversed()
        val ref = row[PettyCashFundingsTable.ref]
        val amount = row[PettyCashFundingsTable.amount]
        val source = moves.existing(row[PettyCashFundingsTable.sourceKind] == "BANK", row[PettyCashFundingsTable.sourceBankAccountId] ?: row[PettyCashFundingsTable.sourceCashAccountId]!!)
        val text = "Reversal of $ref: ${reason.trim()}"
        moves.out(target, amount, LedgerPosting(ref, text, "PETTY_CASH_FUNDING", id, entryDate = support.today(), userId = support.userId()))
        moves.into(source, amount, LedgerPosting(ref, text, "PETTY_CASH_FUNDING", id, entryDate = support.today(), userId = support.userId()))
        PettyCashFundingsTable.update({ PettyCashFundingsTable.id eq id }) {
            it[status] = "REVERSED"; it[reversalReason] = reason.trim(); it[reversedByUserId] = support.userId(); it[reversedAt] = Instant.now()
        }
        support.record("account.petty-cash.reversed", "petty_cash_funding", id, ref, "$ref reversed: ${reason.trim()}", mapOf("amount" to amount), target.locationId)
        fundingRows(PettyCashFundingsTable.id eq id).single()
    }

    /**
     * Fundings, newest first. With `pettyCashAccountId` the summary is the screen's aside for that box:
     * opening balance, funded to date, utilised to date, current balance.
     */
    fun fundings(request: ListSearchRequest): PageResult<PettyCashFundingRow> = tx.execute {
        val context = support.filterContext()
        val parsed = fundingSearch.parse(request.q)
        val condition = listOfNotNull(
            PettyCashFundingsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { CashAccountsTable.locationId inList it },
            fundingSearch.condition(parsed),
            fundingFilters.fromParams(request.params, context),
            fundingFilters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) fundingSearch.rank(parsed) + fundingFilters.sort(emptyList(), context) else fundingFilters.sort(request.sortParams, context)
        val query = fundingJoin().selectAll().where(condition).orderBy(*sort.toTypedArray())
        val page = Paging.fetch(query, PageRequest.of(request.page, request.size)) { it }
        val summary = request.params["pettyCashAccountId"]?.singleOrNull()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.takeIf { request.summary }?.let(::fundingAside)
        page.withItems(toFundingRows(page.items)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun fundingFilterMetadata(): FilterMetadata = tx.execute { fundingFilters.metadata(support.filterContext()) }

    // ---- Petty Cash Payment ----

    /**
     * A petty payment has no approval step, so its rules are enforced on the spot: an active category
     * payable from petty cash, the cash must be there (petty cash is never overdrawn), and the
     * category's monthly cap can't be passed.
     */
    fun pay(request: PettyCashPaymentRequest): PettyCashPaymentView = tx.execute {
        val box = moves.cash(request.pettyCashAccountId!!, setOf("PETTY_CASH"))
        val category = categories.requireUsable(request.expenseCategoryId!!, forPettyCash = true)
        val amount = Money.round(request.amount!!)
        val date = request.paymentDate!!
        support.requireNotFuture("paymentDate", date)
        category[ExpenseCategoriesTable.monthlyCap]?.let { cap ->
            val spent = categories.spentThisMonth()[category[ExpenseCategoriesTable.id].value] ?: BigDecimal.ZERO
            if (spent + amount > cap) throw ApiErrors.monthlyCapExceeded(category[ExpenseCategoriesTable.name], cap, Money.round(spent))
        }
        if (amount > box.balance) throw ApiErrors.insufficientCash(box.code, box.balance, amount)

        val ref = sequences.next("PCP")
        val id = PettyCashPaymentsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[PettyCashPaymentsTable.ref] = ref
            it[pettyCashAccountId] = box.id
            it[expenseCategoryId] = category[ExpenseCategoriesTable.id].value
            it[PettyCashPaymentsTable.amount] = amount
            it[paidTo] = request.paidTo.trim()
            it[paymentDate] = date
            it[approvedBy] = request.approvedBy.clean()
            it[description] = request.description.clean()
            it[status] = "POSTED"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        val remark = "${category[ExpenseCategoriesTable.name]} — ${request.paidTo.trim()}" + (request.description.clean()?.let { ": $it" } ?: "")
        moves.out(box, amount, LedgerPosting(ref, remark, "PETTY_CASH_PAYMENT", id, category[ExpenseCategoriesTable.id].value, date, support.userId()))
        support.record("account.petty-cash.paid", "petty_cash_payment", id, ref, "$ref: ${Money.naira(amount)} to ${request.paidTo.trim()} from ${box.code}",
            mapOf("amount" to amount, "category" to category[ExpenseCategoriesTable.name]), box.locationId)
        payment(id)
    }

    fun reversePayment(id: UUID, reason: String): PettyCashPaymentView = tx.execute {
        val row = PettyCashPaymentsTable.selectAll().where { (PettyCashPaymentsTable.id eq id) and (PettyCashPaymentsTable.tenantId eq support.tenantId()) }
            .forUpdate().firstOrNull() ?: throw ApiErrors.pettyCashPaymentNotFound()
        val box = moves.existing(false, row[PettyCashPaymentsTable.pettyCashAccountId])
        support.assertAccess(box.locationId)
        if (row[PettyCashPaymentsTable.status] != "POSTED") throw ApiErrors.alreadyReversed()
        val ref = row[PettyCashPaymentsTable.ref]
        moves.into(box, row[PettyCashPaymentsTable.amount], LedgerPosting(ref, "Reversal of $ref: ${reason.trim()}", "PETTY_CASH_PAYMENT", id,
            row[PettyCashPaymentsTable.expenseCategoryId], support.today(), support.userId()))
        PettyCashPaymentsTable.update({ PettyCashPaymentsTable.id eq id }) {
            it[status] = "REVERSED"; it[reversalReason] = reason.trim(); it[reversedByUserId] = support.userId(); it[reversedAt] = Instant.now()
        }
        support.record("account.petty-cash.reversed", "petty_cash_payment", id, ref, "$ref reversed: ${reason.trim()}", mapOf("amount" to row[PettyCashPaymentsTable.amount]), box.locationId)
        payment(id)
    }

    fun getPayment(id: UUID): PettyCashPaymentView = tx.execute { payment(id) }

    // ---- Petty Cash Payment History ----

    private val pcp = PettyCashPaymentsTable.alias("pcp")
    private val oc = OperatingCostsTable.alias("oc")
    private val historySource = RawExpression<String>({ +"(CASE WHEN "; +CashTransactionsTable.sourceType; +" = 'RUNNING_COST' THEN 'Running cost' ELSE 'Petty cash payment' END)" })
    private val historyPaidTo = RawExpression<String>({ +"coalesce(pcp.paid_to, oc.payee)" })
    private val historyStatus = RawExpression<String>({ +"coalesce(pcp.status, oc.status)" })

    private fun historyJoin() = CashTransactionsTable
        .join(CashAccountsTable, JoinType.INNER, CashTransactionsTable.cashAccountId, CashAccountsTable.id)
        .join(pcp, JoinType.LEFT, additionalConstraint = { RawOp { +"pcp.id = "; +CashTransactionsTable.sourceId; +" AND "; +CashTransactionsTable.sourceType; +" = 'PETTY_CASH_PAYMENT'" } })
        .join(oc, JoinType.LEFT, additionalConstraint = { RawOp { +"oc.id = "; +CashTransactionsTable.sourceId; +" AND "; +CashTransactionsTable.sourceType; +" = 'RUNNING_COST'" } })

    /**
     * Everything paid out of petty cash: petty cash payments and running costs approved with the
     * PETTY_CASH method, read from the cash ledger's OUT entries (reversals post separately and show
     * the document as REVERSED).
     */
    fun paymentHistory(request: ListSearchRequest): PageResult<PettyCashHistoryRow> = tx.execute {
        val context = support.filterContext()
        val parsed = historySearch.parse(request.q)
        val base = listOfNotNull(
            CashTransactionsTable.tenantId eq support.tenantId(),
            CashAccountsTable.kind eq "PETTY_CASH",
            CashTransactionsTable.direction eq "OUT",
            CashTransactionsTable.sourceType inList listOf("PETTY_CASH_PAYMENT", "RUNNING_COST"),
            support.visibleLocations()?.let { CashAccountsTable.locationId inList it },
        ).allOf()!!
        val condition = listOfNotNull(base, historySearch.condition(parsed), historyFilters.fromParams(request.params, context), historyFilters.fromJson(request.filter, context)).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) historySearch.rank(parsed) + historyFilters.sort(emptyList(), context) else historyFilters.sort(request.sortParams, context)
        val joined = historyJoin()
        val query = joined.select(CashTransactionsTable.columns + CashAccountsTable.code + historySource + historyPaidTo + historyStatus + pcp[PettyCashPaymentsTable.approvedBy] + oc[OperatingCostsTable.approvedByUserId])
            .where(condition).orderBy(*sort.toTypedArray())
        val pageRequest = PageRequest.of(request.page, request.size)
        val page = Paging.fetch(query, pageRequest) { it }
        val categoryNames = categories.names(page.items.mapNotNull { it[CashTransactionsTable.expenseCategoryId] })
        val approvers = Names.users(page.items.mapNotNull { it[oc[OperatingCostsTable.approvedByUserId]] })
        val items = page.items.mapIndexed { index, row ->
            PettyCashHistoryRow(
                sn = pageRequest.offset + index + 1,
                id = row[CashTransactionsTable.sourceId] ?: row[CashTransactionsTable.id].value,
                date = row[CashTransactionsTable.entryDate],
                ref = row[CashTransactionsTable.reference],
                description = row[CashTransactionsTable.remark],
                category = row[CashTransactionsTable.expenseCategoryId]?.let(categoryNames::get),
                paidTo = row[historyPaidTo],
                approvedBy = row[pcp[PettyCashPaymentsTable.approvedBy]] ?: row[oc[OperatingCostsTable.approvedByUserId]]?.let(approvers::get),
                amount = row[CashTransactionsTable.amount],
                source = row[historySource],
                status = row[historyStatus],
                pettyCashAccountCode = row[CashAccountsTable.code],
            )
        }
        val summary = if (request.summary) {
            val count = Aggregates.count()
            val total = Aggregates.sum(CashTransactionsTable.amount)
            val categoriesUsed = CashTransactionsTable.expenseCategoryId.countDistinct()
            val largest = CashTransactionsTable.amount.max()
            val row = joined.select(count, total, categoriesUsed, largest).where(condition and RawOp { +"coalesce(pcp.status, oc.status) <> 'REVERSED'" }).single()
            mapOf(
                "paymentsMade" to Aggregates.long(row, count),
                "totalPaidOut" to Aggregates.money(row, total),
                "categoriesUsed" to row[categoriesUsed],
                "largestPayment" to Money.round(row[largest] ?: BigDecimal.ZERO),
            )
        } else null
        page.withItems(items).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun historyFilterMetadata(): FilterMetadata = tx.execute { historyFilters.metadata(support.filterContext()) }

    // ---- Petty Cash Statement ----

    /**
     * One petty-cash box (or every box at a location) over a period: the ledger (paged) with a running
     * balance, then the screen's statement — funding (opening + top-ups), utilisation by category,
     * closing balance and the share used. With no period it covers the current month to date.
     */
    fun statement(pettyCashAccountId: UUID?, locationId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageRequest): PettyCashStatementView = tx.execute {
        val periodFrom = from ?: support.today().withDayOfMonth(1)
        val periodTo = to ?: support.today()
        if (periodFrom.isAfter(periodTo)) throw ApiErrors.validationFailed(mapOf("from" to "is after 'to'"))
        val accounts = if (pettyCashAccountId != null) {
            listOf(moves.existing(false, pettyCashAccountId).also {
                support.assertAccess(it.locationId)
                if (it.kind != "PETTY_CASH") throw ApiErrors.invalidPayingAccount("${it.code} is not a petty cash account")
            })
        } else {
            val location = support.resolveLocation(locationId)
            CashAccountsTable.selectAll().where(
                listOfNotNull(
                    CashAccountsTable.tenantId eq support.tenantId(),
                    CashAccountsTable.kind eq "PETTY_CASH",
                    location?.let { CashAccountsTable.locationId eq it },
                    support.visibleLocations()?.let { CashAccountsTable.locationId inList it },
                ).allOf()!!,
            ).orderBy(CashAccountsTable.code).map { moves.existing(false, it[CashAccountsTable.id].value) }
        }
        val ids = accounts.map { it.id }.ifEmpty { listOf(UUID(0, 0)) }
        val codes = accounts.associate { it.id to it.code }
        val inScope = (CashTransactionsTable.tenantId eq support.tenantId()) and (CashTransactionsTable.cashAccountId inList ids)
        val inPeriod = inScope and (CashTransactionsTable.entryDate greaterEq periodFrom) and (CashTransactionsTable.entryDate lessEq periodTo)
        val opening = LedgerSql.cashNet(inScope and (CashTransactionsTable.entryDate less periodFrom))

        val balances = LedgerSql.cashBalances(inPeriod)
        val ledgerQuery = CashTransactionsTable.join(balances.alias, JoinType.INNER, CashTransactionsTable.id, balances.id)
            .select(CashTransactionsTable.columns + balances.column).where(inPeriod)
            .orderBy(CashTransactionsTable.entryDate to SortOrder.ASC, CashTransactionsTable.createdAt to SortOrder.ASC, CashTransactionsTable.id to SortOrder.ASC)
        val ledgerPage = Paging.fetch(ledgerQuery, paging) { it }
        val ledgerNames = categories.names(ledgerPage.items.mapNotNull { it[CashTransactionsTable.expenseCategoryId] })
        val ledger = ledgerPage.withItems(ledgerPage.items.map { row ->
            val inflow = row[CashTransactionsTable.direction] == "IN"
            PettyCashLedgerLine(
                date = row[CashTransactionsTable.entryDate],
                ref = row[CashTransactionsTable.reference],
                description = row[CashTransactionsTable.remark],
                category = row[CashTransactionsTable.expenseCategoryId]?.let(ledgerNames::get),
                direction = directionLabel(row[CashTransactionsTable.sourceType], inflow),
                `in` = if (inflow) row[CashTransactionsTable.amount] else BigDecimal.ZERO.setScale(2),
                out = if (inflow) BigDecimal.ZERO.setScale(2) else row[CashTransactionsTable.amount],
                balance = balances.after(row, opening),
                accountCode = codes[row[CashTransactionsTable.cashAccountId]] ?: "",
            )
        })

        // Funding = money put into the box (top-ups and accepted allocations); utilisation = spending by category, net of reversals.
        val funding = inPeriod and (CashTransactionsTable.direction eq "IN") and (CashTransactionsTable.sourceType inList FUNDING_SOURCES) and
            (CashTransactionsTable.remark notLike "Reversal of%")
        val fundingEntries = CashTransactionsTable.select(CashTransactionsTable.reference, CashTransactionsTable.entryDate, CashTransactionsTable.amount).where(funding)
            .orderBy(CashTransactionsTable.entryDate to SortOrder.ASC, CashTransactionsTable.createdAt to SortOrder.ASC, CashTransactionsTable.id to SortOrder.ASC)
            .map { FundingEntry(it[CashTransactionsTable.reference], it[CashTransactionsTable.entryDate], it[CashTransactionsTable.amount]) }
        val fundingSum = Aggregates.sum(CashTransactionsTable.amount)
        val fundingTotal = Aggregates.money(CashTransactionsTable.select(fundingSum).where(funding).single(), fundingSum)
        val spent = Aggregates.sum(RawExpression<BigDecimal>({ +"-"; +LedgerSql.cashSigned }))
        val payments = Aggregates.count(CashTransactionsTable.direction eq "OUT")
        val refunds = Aggregates.count(CashTransactionsTable.direction eq "IN")
        val perCategory = CashTransactionsTable.select(CashTransactionsTable.expenseCategoryId, spent, payments, refunds)
            .where(inPeriod and CashTransactionsTable.expenseCategoryId.isNotNull())
            .groupBy(CashTransactionsTable.expenseCategoryId).toList()
        val categoryNames = categories.names(perCategory.mapNotNull { it[CashTransactionsTable.expenseCategoryId] })
        val byCategory = perCategory.map {
            CategoryUtilisation(categoryNames[it[CashTransactionsTable.expenseCategoryId]] ?: "", Aggregates.long(it, payments) - Aggregates.long(it, refunds), Aggregates.money(it, spent))
        }.filter { it.amount.signum() != 0 }.sortedByDescending { it.amount }
        val utilised = byCategory.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }
        val available = opening + fundingTotal
        val closing = opening + LedgerSql.cashNet(inPeriod)
        val locationNames = Names.locations(accounts.mapNotNull { it.locationId })
        PettyCashStatementView(
            accounts = accounts.map { AccountBrief(it.id, it.code, it.name, it.locationId?.let(locationNames::get)) },
            period = mapOf("from" to periodFrom, "to" to periodTo),
            ledger = ledger,
            statement = PettyCashStatementSummary(
                funding = PettyCashStatementSection(Money.round(opening), fundingEntries, Money.round(available)),
                utilisationByCategory = byCategory,
                totalUtilised = Money.round(utilised),
                closingBalance = Money.round(closing),
                utilisedPct = if (available.signum() > 0) utilised.multiply(BigDecimal(100)).divide(available, 1, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(1),
            ),
        )
    }

    // ---- Petty cash sent to a branch (Accept or Reject Pending) ----

    /** The source pays now; the branch's petty cash is credited only when the branch accepts. */
    fun sendAllocation(request: PettyCashAllocationRequest): PettyCashAllocationRow = tx.execute {
        val source = moves.source(request.sourceKind!!, request.sourceBankAccountId, request.sourceCashAccountId)
        val destination = moves.existing(false, request.destinationPettyCashAccountId!!)
        if (destination.kind != "PETTY_CASH") throw ApiErrors.invalidDestinationAccount("${destination.code} is not a petty cash account")
        val destinationLocation = destination.locationId ?: throw ApiErrors.invalidDestinationAccount("Petty cash is sent to a branch's petty cash, not head office's")
        if (source.locationId == destinationLocation) throw ApiErrors.invalidDestinationAccount("The source and the destination are at the same location; fund the petty cash directly instead")
        val amount = Money.round(request.amount!!)
        val ref = sequences.next("PPC")
        val id = PettyCashAllocationsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[PettyCashAllocationsTable.ref] = ref
            it[sourceKind] = request.sourceKind
            it[sourceBankAccountId] = source.id.takeIf { source.bank }
            it[sourceCashAccountId] = source.id.takeUnless { source.bank }
            it[destinationLocationId] = destinationLocation
            it[destinationPettyCashAccountId] = destination.id
            it[PettyCashAllocationsTable.amount] = amount
            it[purpose] = request.purpose.trim()
            it[status] = "PENDING"
            it[sentByUserId] = support.userId()
            it[sentAt] = Instant.now()
        }.value
        moves.out(source, amount, LedgerPosting(ref, "Petty cash sent to ${destination.code}: ${request.purpose.trim()}", "PETTY_CASH_ALLOCATION_OUT", id, entryDate = support.today(), userId = support.userId()))
        val eventId = support.record("account.petty-cash.allocation-sent", "petty_cash_allocation", id, ref,
            "$ref: ${Money.naira(amount)} petty cash sent to ${destination.code}", mapOf("amount" to amount, "purpose" to request.purpose.trim()), destinationLocation)
        notify.holdersOf(
            permission = "accounting.petty-cash.respond", locationId = destinationLocation, category = NotificationCategory.APPROVAL_REQUIRED,
            title = "Petty cash sent to your branch — $ref", body = "${Money.naira(amount)} for ${destination.code}: ${request.purpose.trim()}. Accept or reject it.",
            linkType = "petty-cash-allocation", linkRef = id.toString(), sourceEventId = eventId, exclude = support.userId(),
        )
        allocation(id)
    }

    /** Binary: the whole amount lands in the branch's petty cash. */
    fun acceptAllocation(id: UUID): PettyCashAllocationRow = tx.execute {
        val row = pendingAllocation(id)
        val ref = row[PettyCashAllocationsTable.ref]
        val destination = moves.existing(false, row[PettyCashAllocationsTable.destinationPettyCashAccountId])
        moves.into(destination, row[PettyCashAllocationsTable.amount], LedgerPosting(ref, "Petty cash received: ${row[PettyCashAllocationsTable.purpose]}", "PETTY_CASH_ALLOCATION_IN", id, entryDate = support.today(), userId = support.userId()))
        respond(row, "ACCEPTED", null)
        allocation(id)
    }

    /** The money goes back to the source. */
    fun rejectAllocation(id: UUID, note: String): PettyCashAllocationRow = tx.execute {
        val row = pendingAllocation(id)
        val ref = row[PettyCashAllocationsTable.ref]
        val source = moves.existing(row[PettyCashAllocationsTable.sourceKind] == "BANK", row[PettyCashAllocationsTable.sourceBankAccountId] ?: row[PettyCashAllocationsTable.sourceCashAccountId]!!)
        moves.into(source, row[PettyCashAllocationsTable.amount], LedgerPosting(ref, "Petty cash returned, rejected by the branch: ${note.trim()}", "PETTY_CASH_ALLOCATION_RETURN", id, entryDate = support.today(), userId = support.userId()))
        respond(row, "REJECTED", note.trim())
        allocation(id)
    }

    fun allocations(request: ListSearchRequest): PageResult<PettyCashAllocationRow> = tx.execute {
        val context = support.filterContext()
        val parsed = allocationSearch.parse(request.q)
        val condition = listOfNotNull(
            PettyCashAllocationsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { PettyCashAllocationsTable.destinationLocationId inList it },
            allocationSearch.condition(parsed),
            allocationFilters.fromParams(request.params, context),
            allocationFilters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) allocationSearch.rank(parsed) + allocationFilters.sort(emptyList(), context) else allocationFilters.sort(request.sortParams, context)
        val joined = allocationJoin()
        val page = Paging.fetch(joined.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) {
            val pending = PettyCashAllocationsTable.status eq "PENDING"
            val awaiting = Aggregates.count(pending)
            val valuePending = Aggregates.sum(PettyCashAllocationsTable.amount, pending)
            val accepted = Aggregates.sum(PettyCashAllocationsTable.amount, PettyCashAllocationsTable.status eq "ACCEPTED")
            val rejected = Aggregates.count(PettyCashAllocationsTable.status eq "REJECTED")
            val row = joined.select(awaiting, valuePending, accepted, rejected).where(condition).single()
            mapOf(
                "awaitingCount" to Aggregates.long(row, awaiting), "valuePending" to Aggregates.money(row, valuePending),
                "acceptedValue" to Aggregates.money(row, accepted), "rejectedCount" to Aggregates.long(row, rejected),
            )
        } else null
        page.withItems(toAllocationRows(page.items)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun allocationFilterMetadata(): FilterMetadata = tx.execute { allocationFilters.metadata(support.filterContext()) }

    // ---- Helpers (caller's transaction) ----

    private fun pendingAllocation(id: UUID): ResultRow {
        val row = PettyCashAllocationsTable.selectAll().where { (PettyCashAllocationsTable.id eq id) and (PettyCashAllocationsTable.tenantId eq support.tenantId()) }
            .forUpdate().firstOrNull() ?: throw ApiErrors.allocationNotFound()
        support.assertAccess(row[PettyCashAllocationsTable.destinationLocationId])
        if (row[PettyCashAllocationsTable.status] != "PENDING") throw ApiErrors.alreadyDecided("petty cash allocation")
        return row
    }

    private fun respond(row: ResultRow, status: String, note: String?) {
        val id = row[PettyCashAllocationsTable.id].value
        val ref = row[PettyCashAllocationsTable.ref]
        PettyCashAllocationsTable.update({ PettyCashAllocationsTable.id eq id }) {
            it[PettyCashAllocationsTable.status] = status
            it[respondedByUserId] = support.userId()
            it[respondedAt] = Instant.now()
            it[PettyCashAllocationsTable.note] = note
        }
        val verb = if (status == "ACCEPTED") "accepted" else "rejected"
        val eventId = support.record("account.petty-cash.allocation-$verb", "petty_cash_allocation", id, ref,
            "$ref $verb by the branch" + (note?.let { ": $it" } ?: ""), mapOf("amount" to row[PettyCashAllocationsTable.amount]), row[PettyCashAllocationsTable.destinationLocationId])
        notify.inApp(
            listOf(row[PettyCashAllocationsTable.sentByUserId]), if (status == "ACCEPTED") NotificationCategory.CONFIRMATION else NotificationCategory.ALERT,
            "Petty cash $verb — $ref", "${Money.naira(row[PettyCashAllocationsTable.amount])} was $verb" + (note?.let { ": $it" } ?: "."),
            "petty-cash-allocation", id.toString(), eventId, exclude = support.userId(),
        )
    }

    private fun payment(id: UUID): PettyCashPaymentView {
        val row = PettyCashPaymentsTable.join(CashAccountsTable, JoinType.INNER, PettyCashPaymentsTable.pettyCashAccountId, CashAccountsTable.id)
            .join(ExpenseCategoriesTable, JoinType.INNER, PettyCashPaymentsTable.expenseCategoryId, ExpenseCategoriesTable.id)
            .selectAll().where { (PettyCashPaymentsTable.id eq id) and (PettyCashPaymentsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.pettyCashPaymentNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[CashAccountsTable.locationId] !in visible) throw ApiErrors.pettyCashPaymentNotFound()
        return PettyCashPaymentView(
            id = id, ref = row[PettyCashPaymentsTable.ref], date = row[PettyCashPaymentsTable.paymentDate],
            pettyCashAccountId = row[PettyCashPaymentsTable.pettyCashAccountId], pettyCashAccountCode = row[CashAccountsTable.code],
            expenseCategoryId = row[PettyCashPaymentsTable.expenseCategoryId], category = row[ExpenseCategoriesTable.name],
            amount = row[PettyCashPaymentsTable.amount], paidTo = row[PettyCashPaymentsTable.paidTo], approvedBy = row[PettyCashPaymentsTable.approvedBy],
            description = row[PettyCashPaymentsTable.description], status = row[PettyCashPaymentsTable.status], reversalReason = row[PettyCashPaymentsTable.reversalReason],
            balanceAfter = row[CashAccountsTable.currentBalance], createdAt = row[PettyCashPaymentsTable.createdAt],
        )
    }

    private val sourceBank = BankAccountsTable.alias("src_bank")
    private val sourceCash = CashAccountsTable.alias("src_cash")

    private fun fundingJoin() = PettyCashFundingsTable
        .join(CashAccountsTable, JoinType.INNER, PettyCashFundingsTable.pettyCashAccountId, CashAccountsTable.id)
        .join(sourceBank, JoinType.LEFT, PettyCashFundingsTable.sourceBankAccountId, sourceBank[BankAccountsTable.id])
        .join(sourceCash, JoinType.LEFT, PettyCashFundingsTable.sourceCashAccountId, sourceCash[CashAccountsTable.id])

    private fun fundingRows(condition: Op<Boolean>): List<PettyCashFundingRow> = toFundingRows(fundingJoin().selectAll().where(condition).toList())

    private fun toFundingRows(rows: List<ResultRow>): List<PettyCashFundingRow> {
        val users = Names.users(rows.map { it[PettyCashFundingsTable.createdByUserId] })
        return rows.map { row ->
            PettyCashFundingRow(
                id = row[PettyCashFundingsTable.id].value, ref = row[PettyCashFundingsTable.ref], date = row[PettyCashFundingsTable.fundingDate],
                pettyCashAccountId = row[PettyCashFundingsTable.pettyCashAccountId], pettyCashAccountCode = row[CashAccountsTable.code],
                sourceKind = row[PettyCashFundingsTable.sourceKind],
                sourceName = row[sourceBank[BankAccountsTable.code]]?.let { "$it ${row[sourceBank[BankAccountsTable.bankName]]}" }
                    ?: "${row[sourceCash[CashAccountsTable.code]]} ${row[sourceCash[CashAccountsTable.name]]}",
                amount = row[PettyCashFundingsTable.amount], transferOrChequeReference = row[PettyCashFundingsTable.transferOrChequeReference],
                authorisedBy = row[PettyCashFundingsTable.authorisedBy], description = row[PettyCashFundingsTable.description],
                fundedBy = users[row[PettyCashFundingsTable.createdByUserId]], status = row[PettyCashFundingsTable.status],
                reversalReason = row[PettyCashFundingsTable.reversalReason], createdAt = row[PettyCashFundingsTable.createdAt],
            )
        }
    }

    /** The funding aside for one petty cash box. */
    private fun fundingAside(accountId: UUID): Map<String, Any?> {
        val account = moves.existing(false, accountId)
        val opening = Aggregates.sum(CashTransactionsTable.amount, CashTransactionsTable.sourceType eq "OPENING")
        val funded = Aggregates.sum(LedgerSql.cashSigned, CashTransactionsTable.sourceType inList FUNDING_SOURCES)
        val spent = Aggregates.sum(RawExpression<BigDecimal>({ +"-"; +LedgerSql.cashSigned }), CashTransactionsTable.sourceType inList listOf("PETTY_CASH_PAYMENT", "RUNNING_COST"))
        val row = CashTransactionsTable.select(opening, funded, spent)
            .where { (CashTransactionsTable.tenantId eq support.tenantId()) and (CashTransactionsTable.cashAccountId eq accountId) }.single()
        return mapOf(
            "pettyCashAccountId" to accountId, "code" to account.code,
            "openingBalance" to Aggregates.money(row, opening),
            "fundedToDate" to Aggregates.money(row, funded),
            "utilisedToDate" to Aggregates.money(row, spent),
            "currentBalance" to account.balance,
        )
    }

    private val destinationAccount = CashAccountsTable.alias("dest_cash")

    private fun allocationJoin() = PettyCashAllocationsTable
        .join(destinationAccount, JoinType.INNER, PettyCashAllocationsTable.destinationPettyCashAccountId, destinationAccount[CashAccountsTable.id])
        .join(LocationsTable, JoinType.INNER, additionalConstraint = { RawOp { +PettyCashAllocationsTable.destinationLocationId; +" = "; +LocationsTable.id } })
        .join(sourceBank, JoinType.LEFT, PettyCashAllocationsTable.sourceBankAccountId, sourceBank[BankAccountsTable.id])
        .join(sourceCash, JoinType.LEFT, PettyCashAllocationsTable.sourceCashAccountId, sourceCash[CashAccountsTable.id])

    private fun allocation(id: UUID): PettyCashAllocationRow =
        toAllocationRows(allocationJoin().selectAll().where { (PettyCashAllocationsTable.id eq id) and (PettyCashAllocationsTable.tenantId eq support.tenantId()) }.toList())
            .singleOrNull() ?: throw ApiErrors.allocationNotFound()

    private fun toAllocationRows(rows: List<ResultRow>): List<PettyCashAllocationRow> {
        val users = Names.users(rows.flatMap { listOfNotNull(it[PettyCashAllocationsTable.sentByUserId], it[PettyCashAllocationsTable.respondedByUserId]) })
        return rows.map { row ->
            PettyCashAllocationRow(
                id = row[PettyCashAllocationsTable.id].value, ref = row[PettyCashAllocationsTable.ref], date = row[PettyCashAllocationsTable.sentAt],
                sourceName = row[sourceBank[BankAccountsTable.code]]?.let { "$it ${row[sourceBank[BankAccountsTable.bankName]]}" }
                    ?: "${row[sourceCash[CashAccountsTable.code]]} ${row[sourceCash[CashAccountsTable.name]]}",
                destinationLocationId = row[PettyCashAllocationsTable.destinationLocationId], branch = row[LocationsTable.name],
                destinationAccountCode = row[destinationAccount[CashAccountsTable.code]], purpose = row[PettyCashAllocationsTable.purpose],
                sentBy = users[row[PettyCashAllocationsTable.sentByUserId]], amount = row[PettyCashAllocationsTable.amount],
                status = row[PettyCashAllocationsTable.status], respondedBy = row[PettyCashAllocationsTable.respondedByUserId]?.let(users::get),
                respondedOn = row[PettyCashAllocationsTable.respondedAt], note = row[PettyCashAllocationsTable.note],
            )
        }
    }

    private fun directionLabel(sourceType: String, inflow: Boolean): String = when (sourceType) {
        "OPENING" -> "Opening"
        "PETTY_CASH_FUNDING" -> if (inflow) "Funding" else "Funding reversal"
        "PETTY_CASH_ALLOCATION_IN" -> "Funding"
        "PETTY_CASH_PAYMENT" -> if (inflow) "Payment reversal" else "Payment"
        "RUNNING_COST" -> if (inflow) "Running cost reversal" else "Running cost"
        "CASH_COUNT_ADJUSTMENT" -> "Count adjustment"
        else -> BankAccountService.label(sourceType)
    }

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

    // ---- Search & filters ----

    private val fundingFilters = FilterSpec(
        fields = listOf(
            FilterField("pettyCashAccountId", "Petty cash", FilterType.UUID, PettyCashFundingsTable.pettyCashAccountId, lookup = "/api/v1/account/cash-accounts/lookup?kind=PETTY_CASH"),
            FilterField("locationId", "Location", FilterType.LOCATION, CashAccountsTable.locationId, facetable = true),
            FilterField("sourceKind", "Source", FilterType.ENUM, PettyCashFundingsTable.sourceKind, listOf(FilterOption("BANK", "Bank"), FilterOption("CASH", "Cash")), facetable = true),
            FilterField("fundedBy", "Funded by", FilterType.UUID, PettyCashFundingsTable.createdByUserId),
            FilterField("status", "Status", FilterType.ENUM, PettyCashFundingsTable.status, listOf(FilterOption("POSTED", "Posted"), FilterOption("REVERSED", "Reversed"))),
            FilterField("amount", "Amount", FilterType.MONEY, PettyCashFundingsTable.amount, sortable = true),
            FilterField("fundingDate", "Date", FilterType.DATE, PettyCashFundingsTable.fundingDate, sortable = true),
            FilterField("createdAt", "Recorded", FilterType.DATETIME, PettyCashFundingsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "fundingDate",
        defaultSort = listOf("fundingDate" to SortOrder.DESC, "createdAt" to SortOrder.DESC),
        tiebreaker = PettyCashFundingsTable.id,
    )

    private val fundingSearch = SearchSpec(
        fields = listOf(
            SearchField(PettyCashFundingsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(PettyCashFundingsTable.transferOrChequeReference, SearchKind.CODE, "Transfer / cheque"),
            SearchField(RawExpression<String>({ +"petty_cash_fundings.search_vector" }), SearchKind.FULLTEXT, "Description"),
            SearchField(PettyCashFundingsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = fundingFilters,
    )

    private val historyFilters = FilterSpec(
        fields = listOf(
            FilterField("pettyCashAccountId", "Petty cash", FilterType.UUID, CashTransactionsTable.cashAccountId, lookup = "/api/v1/account/cash-accounts/lookup?kind=PETTY_CASH"),
            FilterField("locationId", "Location", FilterType.LOCATION, CashAccountsTable.locationId, facetable = true),
            FilterField("expenseCategoryId", "Category", FilterType.UUID, CashTransactionsTable.expenseCategoryId, aliases = setOf("category"), lookup = "/api/v1/account/expense-categories/lookup", facetable = true),
            FilterField("source", "Source", FilterType.ENUM, historySource, listOf(FilterOption("Petty cash payment", "Petty cash payment"), FilterOption("Running cost", "Running cost")), facetable = true),
            FilterField("status", "Status", FilterType.ENUM, historyStatus, listOf(FilterOption("POSTED", "Posted"), FilterOption("APPROVED", "Approved"), FilterOption("REVERSED", "Reversed"))),
            FilterField("amount", "Amount", FilterType.MONEY, CashTransactionsTable.amount, sortable = true),
            FilterField("entryDate", "Date", FilterType.DATE, CashTransactionsTable.entryDate, sortable = true),
            FilterField("createdAt", "Recorded", FilterType.DATETIME, CashTransactionsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "entryDate",
        defaultSort = listOf("entryDate" to SortOrder.DESC, "createdAt" to SortOrder.DESC),
        tiebreaker = CashTransactionsTable.id,
    )

    private val historySearch = SearchSpec(
        fields = listOf(
            SearchField(CashTransactionsTable.reference, SearchKind.REFERENCE, "Reference"),
            SearchField(historyPaidTo, SearchKind.TRIGRAM, "Paid to"),
            SearchField(RawExpression<String>({ +"cash_transactions.search_vector" }), SearchKind.FULLTEXT, "Description"),
            SearchField(CashTransactionsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = historyFilters,
    )

    private val allocationFilters = FilterSpec(
        fields = listOf(
            FilterField("status", "Status", FilterType.ENUM, PettyCashAllocationsTable.status,
                listOf(FilterOption("PENDING", "Pending"), FilterOption("ACCEPTED", "Accepted"), FilterOption("REJECTED", "Rejected")), facetable = true),
            FilterField("locationId", "Branch", FilterType.LOCATION, PettyCashAllocationsTable.destinationLocationId, aliases = setOf("branch"), facetable = true),
            FilterField("sentBy", "Sent by", FilterType.UUID, PettyCashAllocationsTable.sentByUserId),
            FilterField("respondedBy", "Responded by", FilterType.UUID, PettyCashAllocationsTable.respondedByUserId),
            FilterField("amount", "Amount", FilterType.MONEY, PettyCashAllocationsTable.amount, sortable = true),
            FilterField("sentAt", "Sent", FilterType.DATETIME, PettyCashAllocationsTable.sentAt, sortable = true),
        ),
        primaryDateKey = "sentAt",
        defaultSort = listOf("sentAt" to SortOrder.DESC),
        tiebreaker = PettyCashAllocationsTable.id,
    )

    private val allocationSearch = SearchSpec(
        fields = listOf(
            SearchField(PettyCashAllocationsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(PettyCashAllocationsTable.purpose, SearchKind.TRIGRAM, "Purpose"),
            SearchField(LocationsTable.name, SearchKind.TRIGRAM, "Branch"),
            SearchField(PettyCashAllocationsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = allocationFilters,
    )

    companion object {
        private val FUNDING_SOURCES = setOf("PETTY_CASH_FUNDING", "PETTY_CASH_ALLOCATION_IN")
    }
}
