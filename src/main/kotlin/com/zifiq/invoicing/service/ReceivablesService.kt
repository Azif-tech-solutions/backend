package com.zifiq.invoicing.service

import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.DecimalColumnType
import com.zifiq.invoicing.model.DebtorBalancesTable
import com.zifiq.customers.spi.CustomerColumns
import com.zifiq.common.search.anyOf
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchField
import com.zifiq.common.paging.Paging
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PagingPolicy
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.filter.SortField
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.allOf
import com.zifiq.common.search.equalsText
import com.zifiq.common.sql.Aggregates
import com.zifiq.customers.spi.Correspondents
import com.zifiq.customers.spi.CustomerQueries
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.invoicing.dto.DebtorAccount
import com.zifiq.invoicing.dto.DebtorDetail
import com.zifiq.invoicing.dto.DebtorRow
import com.zifiq.invoicing.dto.DebtorTransaction
import com.zifiq.invoicing.dto.ReceivableRow
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.max
import org.springframework.stereotype.Service

/**
 * Outstanding receivables and the Debtors List (docs/08 §4.9–4.10). Receivables are open invoices
 * (issued, balance above the paid tolerance) and use the shared invoice filters; header totals come
 * from the same condition as the rows, so the view reconciles exactly with them. The Debtors List
 * groups open invoices by customer in SQL and adds customer details (credit limit, rep, home
 * showroom) through the Customers spi, so its customer filters run on that small grouped set.
 */
@Service
class ReceivablesService(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
    private val listing: InvoiceListing,
    private val customers: CustomerQueries,
    private val correspondents: Correspondents,
) {
    fun receivables(request: ListSearchRequest): PageResult<ReceivableRow> = tx.execute {
        val d = listing.expressions()
        val sorted = if (request.sort.isEmpty() && request.q.isNullOrBlank()) request.copy(sort = listOf(SortField("daysOverdue", "desc"), SortField("balance", "desc"))) else request
        val page = listing.page(sorted, d, RawOp { +d.open })
        val items = page.result.items.map { row ->
            ReceivableRow(
                id = row[InvoicesTable.id].value, number = row[InvoicesTable.number]!!, issued = row[InvoicesTable.issueDate], due = row[InvoicesTable.dueDate],
                customerId = row[InvoicesTable.customerId], customer = row[InvoicesTable.buyerName], customerType = row[InvoicesTable.buyerType],
                showroom = row.getOrNull(LocationsTable.name), total = row[InvoicesTable.taxInclusiveAmount], paid = row[InvoicesTable.amountPaid],
                credited = row[InvoicesTable.amountCredited], balance = Money.round(row[d.balance]),
                status = InvoiceExpressions.label(row[d.displayStatus]), daysOverdue = row[d.daysOverdue], ageingBucket = row[d.ageingBucket] ?: "NOT_DUE",
            )
        }
        val summary = if (request.summary) receivablesSummary(page.condition, d) else null
        page.result.withItems(items).copy(summary = summary, facets = listing.facets(request, page, d).ifEmpty { null }, meta = PageMeta(ignoredQualifiers = page.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { listing.expressions().filterSpec.metadata(support.filterContext()) }

    /**
     * One row per customer with an open balance, read from the debtor_balances read model (V14) joined
     * to the customer's published columns, so filters, `q`, sorting, paging and the header all run in
     * SQL over an index. Filters: `locationId` (the customer's home showroom), `customerType`, `ageing`,
     * `correspondentId` (repeatable or comma-separated), `overLimitOnly`, `outstanding.gte` /
     * `outstanding.lte`, `q` (code, name, phone). Sort `outstanding` (default, desc),
     * `oldestInvoiceDays`, `name`, `lastPaymentAt`.
     */
    fun debtors(request: ListSearchRequest): PageResult<DebtorRow> = tx.execute {
        fun values(key: String) = request.params[key].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        fun uuids(key: String) = values(key).map { runCatching { UUID.fromString(it) }.getOrElse { _ -> throw ApiErrors.invalidFilter(key, "Not an id", emptyList()) } }
        fun amount(key: String) = request.params[key]?.firstOrNull()?.let { it.toBigDecimalOrNull() ?: throw ApiErrors.invalidFilter(key, "Not an amount", emptyList()) }
        val t = DebtorTotals(support.visibleLocations())
        val today = support.today()
        val ageing = (values("ageing") + values("ageingBucket")).map { bucket ->
            when (bucket) {
                "WITHIN_30" -> t.oldest.atOrAfter(today.minusDays(30))
                "DAYS_31_60" -> t.oldest.before(today.minusDays(30)) and t.oldest.atOrAfter(today.minusDays(60))
                "OVER_60" -> t.oldest.before(today.minusDays(60))
                else -> throw ApiErrors.invalidFilter("ageing", "Unknown bucket", AGEING_LABELS.keys.toList())
            }
        }.anyOf()
        val parsed = request.q?.takeIf { it.isNotBlank() }?.let(DEBTOR_SEARCH::parse)
        val customerFilters = listOfNotNull(
            uuids("locationId").takeIf { it.isNotEmpty() }?.let { CustomerColumns.locationId inList it },
            values("customerType").takeIf { it.isNotEmpty() }?.let { CustomerColumns.type inList it },
            uuids("correspondentId").takeIf { it.isNotEmpty() }?.let { CustomerColumns.correspondentId inList it },
            parsed?.let(DEBTOR_SEARCH::condition),
        )
        val totalFilters = listOfNotNull(
            ageing,
            t.overLimit.takeIf { request.params["overLimitOnly"]?.firstOrNull() == "true" },
            amount("outstanding.gte")?.let { t.outstanding.atLeast(it) },
            amount("outstanding.lte")?.let { t.outstanding.atMost(it) },
        )
        val sort = request.sort.firstOrNull()
        val desc = sort == null || sort.dir.equals("desc", ignoreCase = true)
        val order: Pair<Expression<*>, SortOrder> = when (sort?.field) {
            null, "outstanding" -> t.outstanding to if (desc) SortOrder.DESC else SortOrder.ASC
            // More days since the oldest open invoice = an earlier date.
            "oldestInvoiceDays" -> t.oldest to if (desc) SortOrder.ASC_NULLS_LAST else SortOrder.DESC_NULLS_LAST
            "name" -> CustomerColumns.name to if (desc) SortOrder.DESC else SortOrder.ASC
            "lastPaymentAt" -> t.lastPaymentAt to if (desc) SortOrder.DESC_NULLS_LAST else SortOrder.ASC_NULLS_FIRST
            else -> throw ApiErrors.invalidFilter(sort.field, "Not sortable", listOf("outstanding", "oldestInvoiceDays", "name", "lastPaymentAt"))
        }
        // With `q` and no explicit sort, best match first (as every searchable list does).
        val rank = if (sort == null) parsed?.let(DEBTOR_SEARCH::rank).orEmpty() else emptyList()
        val query = t.query(customerFilters, totalFilters).orderBy(*(rank + order + (DebtorBalancesTable.customerId to SortOrder.ASC)).toTypedArray())
        val page = Paging.fetch(query, PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) {
            val debtors = t.query(customerFilters, totalFilters).alias("debtors")
            val count = Aggregates.count()
            val total = Aggregates.sum(debtors[t.outstandingAlias])
            val overLimit = Aggregates.count(debtors[t.overLimitAlias])
            val row = debtors.select(count, total, overLimit).single()
            mapOf("debtors" to Aggregates.long(row, count), "outstandingTotal" to Aggregates.money(row, total), "overLimitCount" to Aggregates.long(row, overLimit))
        } else null
        page.withItems(t.rows(page.items, today)).copy(summary = summary)
    }

    /**
     * One customer's account: totals, and their invoice history newest first, one page at a time.
     * `from`/`to` (issue date) and `openOnly` narrow the history.
     */
    fun debtor(customerId: UUID, from: LocalDate?, to: LocalDate?, openOnly: Boolean, paging: PageRequest): DebtorDetail = tx.execute {
        val d = listing.expressions()
        val customer = customers.byIds(listOf(customerId)).firstOrNull() ?: throw ApiErrors.customerNotFound()
        val t = DebtorTotals(support.visibleLocations())
        val row = t.rows(t.query(listOf(CustomerColumns.id eq customerId), emptyList(), debtorsOnly = false).toList(), support.today()).firstOrNull()
            ?: zeroRow(customer)
        val invoiced = Aggregates.sum(InvoicesTable.taxInclusiveAmount)
        val paid = Aggregates.sum(InvoicesTable.amountPaid)
        val credited = Aggregates.sum(InvoicesTable.amountCredited)
        val totals = InvoicesTable.select(invoiced, paid, credited)
            .where(listing.visibility() and (InvoicesTable.customerId eq customerId) and (InvoicesTable.status eq "ISSUED")).single()
        val history = listOfNotNull(
            listing.visibility(), InvoicesTable.customerId eq customerId, InvoicesTable.status inList listOf("ISSUED", "VOID"),
            from?.let { InvoicesTable.issueDate greaterEq it }, to?.let { InvoicesTable.issueDate lessEq it },
            if (openOnly) RawOp { +d.open } else null,
        ).allOf()!!
        val query = InvoicesTable.select(InvoicesTable.columns + listOf(d.balance, d.displayStatus)).where(history)
            .orderBy(InvoicesTable.issueDate to SortOrder.DESC_NULLS_LAST, InvoicesTable.issuedAt to SortOrder.DESC, InvoicesTable.id to SortOrder.DESC)
        val transactions = Paging.fetch(query, paging) {
            val void = it[InvoicesTable.status] == "VOID"
            DebtorTransaction(
                invoiceId = it[InvoicesTable.id].value, date = it[InvoicesTable.issueDate], invoice = it[InvoicesTable.number]!!,
                description = (if (it[InvoicesTable.sourceType] == "PRO_FORMA") "Invoice from pro-forma " else "Invoice for sale ") + it[InvoicesTable.sourceRef] + if (void) " (void)" else "",
                value = it[InvoicesTable.taxInclusiveAmount], paid = it[InvoicesTable.amountPaid] + it[InvoicesTable.amountCredited],
                balance = if (void) BigDecimal.ZERO.setScale(2) else Money.round(it[d.balance].max(BigDecimal.ZERO)),
                dueDate = it[InvoicesTable.dueDate], status = InvoiceExpressions.label(it[d.displayStatus]),
            )
        }
        val account = DebtorAccount(
            openInvoices = row.openInvoices, lastPaymentAt = row.lastPaymentAt,
            totalInvoiced = Aggregates.money(totals, invoiced), totalPaid = Aggregates.money(totals, paid), totalCredited = Aggregates.money(totals, credited),
            outstanding = row.outstanding, creditLimit = row.creditLimit, overLimit = row.overLimit,
        )
        DebtorDetail(row, account, transactions,
            if (row.overLimit) "Further credit sales should be withheld until the balance is brought within limit." else null)
    }

    // ---- Internals (caller's transaction) ----

    private fun receivablesSummary(condition: Op<Boolean>, d: InvoiceExpressions): Map<String, Any?> {
        val overdueOnly = RawOp { +d.daysOverdue; +" > 0" }
        val partialOnly = d.displayStatus.equalsText("PARTIALLY_PAID")
        val outstanding = Aggregates.sum(d.balance)
        val outstandingCount = Aggregates.count()
        val overdue = Aggregates.sum(d.balance, overdueOnly)
        val overdueCount = Aggregates.count(overdueOnly)
        val partial = Aggregates.sum(d.balance, partialOnly)
        val partialCount = Aggregates.count(partialOnly)
        val row = listing.joined().select(outstanding, outstandingCount, overdue, overdueCount, partial, partialCount).where(condition).single()
        val voided = listing.joined().select(InvoicesTable.id).where(listing.visibility() and (InvoicesTable.status eq "VOID")).count()
        val byCustomerBalance = Aggregates.sum(d.balance)
        val byCustomerCount = Aggregates.count()
        val byCustomer = listing.joined().select(InvoicesTable.customerId, InvoicesTable.buyerName, byCustomerCount, byCustomerBalance).where(condition)
            .groupBy(InvoicesTable.customerId, InvoicesTable.buyerName).orderBy(byCustomerBalance to SortOrder.DESC).limit(PagingPolicy.SUMMARY_TOP_N)
            .map {
                mapOf("customerId" to it[InvoicesTable.customerId], "customer" to it[InvoicesTable.buyerName],
                    "invoiceCount" to Aggregates.long(it, byCustomerCount), "balance" to Aggregates.money(it, byCustomerBalance))
            }
        return mapOf(
            "outstandingTotal" to Aggregates.money(row, outstanding), "outstandingCount" to Aggregates.long(row, outstandingCount),
            "overdueTotal" to Aggregates.money(row, overdue), "overdueCount" to Aggregates.long(row, overdueCount),
            "partiallyPaidTotal" to Aggregates.money(row, partial), "partiallyPaidCount" to Aggregates.long(row, partialCount),
            "voidedCount" to voided, "outstandingByCustomer" to byCustomer,
        )
    }

    /**
     * Per-customer totals for the caller's location scope, joined to the customer's columns. A user who
     * sees every showroom reads each customer's all-showrooms row, and a one-showroom user that
     * showroom's row: either way one indexed row per customer, so the list's sort order is an index
     * order (debtor_balances_*_idx). Only a user limited to several showrooms needs the rows summed.
     */
    private inner class DebtorTotals(scope: Set<UUID>?) {
        private val b = DebtorBalancesTable
        private val grouped = scope != null && scope.size > 1
        private val scopeCondition: Op<Boolean> =
            if (grouped) b.locationId inList scope!! else b.locationId eq (scope?.singleOrNull() ?: DebtorBalancesTable.ALL_LOCATIONS)

        @Suppress("UNCHECKED_CAST")
        val openInvoices = (if (grouped) b.openInvoices.sum() else b.openInvoices) as Expression<Int?>
        @Suppress("UNCHECKED_CAST")
        val outstanding = (if (grouped) b.outstanding.sum() else b.outstanding) as Expression<BigDecimal?>
        val oldest: Expression<LocalDate?> = if (grouped) b.oldestOpenIssueDate.min() else b.oldestOpenIssueDate
        val lastPaymentAt: Expression<Instant?> = if (grouped) b.lastPaymentAt.max() else b.lastPaymentAt
        val overLimit: Op<Boolean> = RawOp { +outstanding; +" > "; +CustomerColumns.creditLimit }
        val outstandingAlias = outstanding.alias("outstanding")
        val overLimitAlias = overLimit.alias("over_limit")
        private val openAlias = openInvoices.alias("open_invoices")
        private val oldestAlias = oldest.alias("oldest_open")
        private val lastPaymentAlias = lastPaymentAt.alias("last_payment")

        /** [customerFilters] are on the customer's columns; [totalFilters] on the totals (WHERE, or HAVING when summed). */
        fun query(customerFilters: List<Op<Boolean>>, totalFilters: List<Op<Boolean>>, debtorsOnly: Boolean = true): Query {
            val open = if (debtorsOnly) RawOp { +openInvoices; +" > 0" } else null
            val totals = listOfNotNull(open) + totalFilters
            val where = (listOf(b.tenantId eq support.tenantId(), scopeCondition) + customerFilters + if (grouped) emptyList() else totals).allOf()!!
            val query = b.join(CustomerColumns, JoinType.INNER, b.customerId, CustomerColumns.id) { CustomerColumns.tenantId eq b.tenantId }
                .select(b.customerId, CustomerColumns.code, CustomerColumns.name, CustomerColumns.type, CustomerColumns.phone, CustomerColumns.locationId,
                    CustomerColumns.correspondentId, CustomerColumns.creditLimit, openAlias, outstandingAlias, oldestAlias, lastPaymentAlias, overLimitAlias)
                .where(where)
            if (!grouped) return query
            val having = totals.allOf()
            return query.groupBy(b.customerId, CustomerColumns.id).let { q -> if (having != null) q.having { having } else q }
        }

        /** [rows] of [query] as debtor rows; a page's rep and showroom names are read in one query each. */
        fun rows(rows: List<ResultRow>, today: LocalDate): List<DebtorRow> {
            val reps = correspondents.names(rows.mapNotNull { it[CustomerColumns.correspondentId] })
            val showrooms = Names.locations(rows.mapNotNull { it[CustomerColumns.locationId] })
            return rows.map { row ->
                val outstanding = Money.round(row[outstandingAlias] ?: BigDecimal.ZERO)
                val openInvoices = row[openAlias] ?: 0
                val days = row[oldestAlias]?.let { ChronoUnit.DAYS.between(it, today).toInt() } ?: 0
                val ageing = ageingOf(days)
                DebtorRow(
                    customerId = row[b.customerId], code = row[CustomerColumns.code], name = row[CustomerColumns.name], type = row[CustomerColumns.type],
                    phone = row[CustomerColumns.phone], locationId = row[CustomerColumns.locationId], showroom = row[CustomerColumns.locationId]?.let(showrooms::get),
                    correspondentId = row[CustomerColumns.correspondentId], rep = row[CustomerColumns.correspondentId]?.let(reps::get),
                    openInvoices = openInvoices, outstanding = outstanding, creditLimit = row[CustomerColumns.creditLimit],
                    overLimit = openInvoices > 0 && outstanding > row[CustomerColumns.creditLimit],
                    oldestInvoiceDays = days, ageing = ageing, ageingLabel = AGEING_LABELS.getValue(ageing), lastPaymentAt = row[lastPaymentAlias],
                )
            }
        }
    }

    /** A customer with no invoices in the caller's scope yet. */
    private fun zeroRow(customer: CustomerRef) = DebtorRow(
        customerId = customer.id, code = customer.code, name = customer.name, type = customer.type.name, phone = customer.phone,
        locationId = customer.locationId, showroom = customer.locationId?.let { Names.locations(listOf(it))[it] },
        correspondentId = customer.correspondentId, rep = customer.correspondentId?.let { correspondents.names(listOf(it))[it] },
        openInvoices = 0, outstanding = BigDecimal.ZERO.setScale(2), creditLimit = customer.creditLimit, overLimit = false,
        oldestInvoiceDays = 0, ageing = ageingOf(0), ageingLabel = AGEING_LABELS.getValue(ageingOf(0)), lastPaymentAt = null,
    )

    private fun ageingOf(days: Int) = when { days <= 30 -> "WITHIN_30"; days <= 60 -> "DAYS_31_60"; else -> "OVER_60" }

    private fun Expression<*>.compared(op: String, type: IColumnType<*>, value: Any): Op<Boolean> =
        RawOp { +this@compared; +" $op "; registerArgument(type, value) }

    private fun Expression<LocalDate?>.atOrAfter(date: LocalDate) = compared(">=", JavaLocalDateColumnType(), date)
    private fun Expression<LocalDate?>.before(date: LocalDate) = compared("<", JavaLocalDateColumnType(), date)
    private fun Expression<BigDecimal?>.atLeast(amount: BigDecimal) = compared(">=", DecimalColumnType(16, 2), amount)
    private fun Expression<BigDecimal?>.atMost(amount: BigDecimal) = compared("<=", DecimalColumnType(16, 2), amount)

    companion object {
        private val DEBTOR_SEARCH = SearchSpec(
            listOf(
                SearchField(CustomerColumns.code, SearchKind.REFERENCE, "Code"),
                SearchField(CustomerColumns.phoneE164, SearchKind.PHONE, "Phone"),
                SearchField(CustomerColumns.name, SearchKind.TRIGRAM, "Name"),
            ),
        )
        private val AGEING_LABELS = mapOf("WITHIN_30" to "Within 30 days", "DAYS_31_60" to "31–60 days", "OVER_60" to "Over 60 days")
    }
}
