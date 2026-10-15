package com.zifiq.invoicing.service

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
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
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
     * One row per customer with an open balance. Filters: `locationId` (the customer's home showroom),
     * `customerType`, `ageing`, `correspondentId` (repeatable or comma-separated), `overLimitOnly`,
     * `outstanding.gte` / `outstanding.lte`, `q` (code, name, phone). Sort `outstanding` (default, desc),
     * `oldestInvoiceDays`, `name`, `lastPaymentAt`.
     */
    fun debtors(request: ListSearchRequest): PageResult<DebtorRow> = tx.execute {
        val all = debtorRows(null)
        fun values(key: String) = request.params[key].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val locations = values("locationId").map(UUID::fromString).toSet()
        val types = values("customerType")
        val ageing = values("ageing") + values("ageingBucket")
        val reps = values("correspondentId").map(UUID::fromString).toSet()
        val overLimitOnly = request.params["overLimitOnly"]?.firstOrNull() == "true"
        val min = request.params["outstanding.gte"]?.firstOrNull()?.toBigDecimalOrNull()
        val max = request.params["outstanding.lte"]?.firstOrNull()?.toBigDecimalOrNull()
        val q = request.q?.trim()?.lowercase()?.ifEmpty { null }
        val filtered = all.filter { row ->
            (locations.isEmpty() || row.locationId in locations) && (types.isEmpty() || row.type in types) && (ageing.isEmpty() || row.ageing in ageing) &&
                (reps.isEmpty() || row.correspondentId in reps) && (!overLimitOnly || row.overLimit) &&
                (min == null || row.outstanding >= min) && (max == null || row.outstanding <= max) &&
                (q == null || row.code.lowercase().contains(q) || row.name.lowercase().contains(q) || (row.phone?.filter(Char::isDigit)?.let { p -> q.filter(Char::isDigit).let { it.isNotEmpty() && p.contains(it) } } ?: false))
        }
        val sort = request.sort.firstOrNull()
        val sortedRows = when (sort?.field) {
            "oldestInvoiceDays" -> filtered.sortedBy { it.oldestInvoiceDays }
            "name" -> filtered.sortedBy { it.name.lowercase() }
            "lastPaymentAt" -> filtered.sortedBy { it.lastPaymentAt ?: Instant.EPOCH }
            null, "outstanding" -> filtered.sortedBy { it.outstanding }
            else -> throw ApiErrors.invalidFilter(sort.field, "Not sortable", listOf("outstanding", "oldestInvoiceDays", "name", "lastPaymentAt"))
        }.let { if (sort == null || sort.dir.equals("desc", ignoreCase = true)) it.reversed() else it }
        val paging = PageRequest.of(request.page, request.size)
        val items = sortedRows.drop(paging.offset.toInt()).take(paging.size)
        PageResult(
            items = items, page = paging.page, size = paging.size, totalItems = sortedRows.size.toLong(),
            totalPages = if (sortedRows.isEmpty()) 0 else (sortedRows.size + paging.size - 1) / paging.size, totalIsEstimate = false,
            summary = if (request.summary) mapOf(
                "debtors" to sortedRows.size,
                "outstandingTotal" to sortedRows.fold(BigDecimal.ZERO) { s, it -> s + it.outstanding }.setScale(2),
                "overLimitCount" to sortedRows.count { it.overLimit },
            ) else null,
        )
    }

    /** One customer's account: open invoices, totals and their invoice history. `from`/`to` (issue date) and `openOnly` narrow the history. */
    fun debtor(customerId: UUID, from: LocalDate?, to: LocalDate?, openOnly: Boolean): DebtorDetail = tx.execute {
        val d = listing.expressions()
        val customer = customers.byIds(listOf(customerId)).firstOrNull() ?: throw ApiErrors.customerNotFound()
        val row = debtorRows(customerId).firstOrNull() ?: rowFor(customer, 0, BigDecimal.ZERO.setScale(2), null, lastPaymentFor(listOf(customerId))[customerId])
        val base = listing.visibility() and (InvoicesTable.customerId eq customerId) and (InvoicesTable.status inList listOf("ISSUED", "VOID"))
        val invoices = InvoicesTable.select(InvoicesTable.columns + listOf(d.balance, d.displayStatus)).where(base)
            .orderBy(InvoicesTable.issueDate to SortOrder.DESC, InvoicesTable.issuedAt to SortOrder.DESC).toList()
        val issued = invoices.filter { it[InvoicesTable.status] == "ISSUED" }
        val transactions = invoices
            .filter { (from == null || (it[InvoicesTable.issueDate]?.isBefore(from) == false)) && (to == null || (it[InvoicesTable.issueDate]?.isAfter(to) == false)) }
            .filter { !openOnly || (it[InvoicesTable.status] == "ISSUED" && it[d.balance] > support.tolerance()) }
            .map {
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
            totalInvoiced = issued.fold(BigDecimal.ZERO) { s, it -> s + it[InvoicesTable.taxInclusiveAmount] }.setScale(2),
            totalPaid = issued.fold(BigDecimal.ZERO) { s, it -> s + it[InvoicesTable.amountPaid] }.setScale(2),
            totalCredited = issued.fold(BigDecimal.ZERO) { s, it -> s + it[InvoicesTable.amountCredited] }.setScale(2),
            outstanding = row.outstanding, creditLimit = row.creditLimit, overLimit = row.overLimit,
        )
        DebtorDetail(row, account, transactions,
            if (row.overLimit) "Further credit sales should be withheld until the balance is brought within limit." else null)
    }

    // ---- Internals (caller's transaction) ----

    private fun receivablesSummary(condition: Op<Boolean>, d: InvoiceExpressions): Map<String, Any?> {
        val overdueOnly: QueryBuilder.() -> Unit = { +d.daysOverdue; +" > 0" }
        val partialOnly: QueryBuilder.() -> Unit = { +d.displayStatus; +" = 'PARTIALLY_PAID'" }
        fun sumOf(filter: (QueryBuilder.() -> Unit)?) = RawExpression<BigDecimal>({
            +"coalesce(sum("; +d.balance; +")"
            if (filter != null) { +" filter (where "; filter(); +")" }
            +", 0)"
        })
        fun countOf(filter: (QueryBuilder.() -> Unit)?) = RawExpression<Long>({
            +"count(*)"
            if (filter != null) { +" filter (where "; filter(); +")" }
        })
        val outstanding = sumOf(null)
        val outstandingCount = countOf(null)
        val overdue = sumOf(overdueOnly)
        val overdueCount = countOf(overdueOnly)
        val partial = sumOf(partialOnly)
        val partialCount = countOf(partialOnly)
        val row = listing.joined().select(outstanding, outstandingCount, overdue, overdueCount, partial, partialCount).where(condition).single()
        val voided = listing.joined().select(InvoicesTable.id).where(listing.visibility() and (InvoicesTable.status eq "VOID")).count()
        val byCustomerBalance = RawExpression<BigDecimal>({ +"sum("; +d.balance; +")" })
        val byCustomerCount = RawExpression<Long>({ +"count(*)" })
        val byCustomer = listing.joined().select(InvoicesTable.customerId, InvoicesTable.buyerName, byCustomerCount, byCustomerBalance).where(condition)
            .groupBy(InvoicesTable.customerId, InvoicesTable.buyerName).orderBy(byCustomerBalance to SortOrder.DESC).limit(PagingPolicy.SUMMARY_TOP_N)
            .map {
                mapOf("customerId" to it[InvoicesTable.customerId], "customer" to it[InvoicesTable.buyerName],
                    "invoiceCount" to (it[byCustomerCount] as Number).toLong(), "balance" to Money.round(it[byCustomerBalance]))
            }
        return mapOf(
            "outstandingTotal" to Money.round(row[outstanding]), "outstandingCount" to (row[outstandingCount] as Number).toLong(),
            "overdueTotal" to Money.round(row[overdue]), "overdueCount" to (row[overdueCount] as Number).toLong(),
            "partiallyPaidTotal" to Money.round(row[partial]), "partiallyPaidCount" to (row[partialCount] as Number).toLong(),
            "voidedCount" to voided, "outstandingByCustomer" to byCustomer,
        )
    }

    /** Debtor rows from the open invoices in the caller's scope (all customers, or one). */
    private fun debtorRows(onlyCustomer: UUID?): List<DebtorRow> {
        val d = listing.expressions()
        val count = RawExpression<Long>({ +"count(*)" })
        val outstanding = RawExpression<BigDecimal>({ +"sum("; +d.balance; +")" })
        // As text: a raw aggregate comes back from JDBC as java.sql.Date, not LocalDate.
        val oldest = RawExpression<String?>({ +"min("; +InvoicesTable.issueDate; +")::text" })
        val condition = listOfNotNull(
            listing.visibility(), RawOp { +d.open }, InvoicesTable.customerId.isNotNull(), onlyCustomer?.let { InvoicesTable.customerId eq it },
        ).allOf()!!
        val grouped = InvoicesTable.select(InvoicesTable.customerId, count, outstanding, oldest).where(condition).groupBy(InvoicesTable.customerId).toList()
        if (grouped.isEmpty()) return emptyList()
        val ids = grouped.map { it[InvoicesTable.customerId]!! }
        val refs = customers.byIds(ids).associateBy { it.id }
        val lastPayments = lastPaymentFor(ids)
        return grouped.mapNotNull { g ->
            val id = g[InvoicesTable.customerId]!!
            val customer = refs[id] ?: return@mapNotNull null
            val oldestDate = g[oldest]?.let(LocalDate::parse)
            rowFor(customer, (g[count] as Number).toInt(), Money.round(g[outstanding]), oldestDate, lastPayments[id])
        }
    }

    private fun rowFor(customer: CustomerRef, openInvoices: Int, outstanding: BigDecimal, oldest: LocalDate?, lastPaymentAt: Instant?): DebtorRow {
        val days = oldest?.let { ChronoUnit.DAYS.between(it, support.today()).toInt() } ?: 0
        val ageing = when { days <= 30 -> "WITHIN_30"; days <= 60 -> "DAYS_31_60"; else -> "OVER_60" }
        val rep = customer.correspondentId?.let { id -> runCatching { correspondents.get(id).name }.getOrNull() }
        return DebtorRow(
            customerId = customer.id, code = customer.code, name = customer.name, type = customer.type.name, phone = customer.phone,
            locationId = customer.locationId, showroom = customer.locationId?.let { Names.locations(listOf(it))[it] },
            correspondentId = customer.correspondentId, rep = rep, openInvoices = openInvoices, outstanding = outstanding,
            creditLimit = customer.creditLimit, overLimit = outstanding > customer.creditLimit && openInvoices > 0,
            oldestInvoiceDays = days, ageing = ageing, ageingLabel = AGEING_LABELS.getValue(ageing), lastPaymentAt = lastPaymentAt,
        )
    }

    private fun lastPaymentFor(customerIds: Collection<UUID>): Map<UUID, Instant?> {
        val last = InvoicesTable.lastPaymentAt.max()
        return InvoicesTable.select(InvoicesTable.customerId, last)
            .where { listing.visibility() and (InvoicesTable.customerId inList customerIds) }
            .groupBy(InvoicesTable.customerId).associate { it[InvoicesTable.customerId]!! to it[last] }
    }

    companion object {
        private val AGEING_LABELS = mapOf("WITHIN_30" to "Within 30 days", "DAYS_31_60" to "31–60 days", "OVER_60" to "Over 60 days")
    }
}
