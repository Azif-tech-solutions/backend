package com.zifiq.invoicing.service

import com.zifiq.platform.directory.Names
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.Facets
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.FacetCount
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.allOf
import com.zifiq.customers.spi.CustomerType
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.tenancy.model.LocationsTable
import java.util.UUID
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.springframework.stereotype.Component

/**
 * The query machinery every invoice list shares (history, voided, receivables, deliveries): the
 * caller's location scope, `q` search, URL and JSON filters, sorting, paging and facets, all over
 * one [InvoiceExpressions]. Runs in the caller's transaction.
 */
@Component
class InvoiceListing(private val support: InvoiceSupport) {
    internal class Page(
        val result: PageResult<ResultRow>,
        val condition: Op<Boolean>,
        val scope: Op<Boolean>,
        val search: Op<Boolean>?,
        val ignoredQualifiers: List<String>,
    )

    internal fun expressions() = support.expressions()

    /** [extra] narrows the list for every purpose, facets included (e.g. "only VOID", "only open"). */
    internal fun page(request: ListSearchRequest, d: InvoiceExpressions, extra: Op<Boolean>? = null): Page {
        val context = support.filterContext()
        val parsed = d.searchSpec.parse(request.q)
        val scope = listOfNotNull(visibility(), extra).allOf()!!
        val search = d.searchSpec.condition(parsed)
        val condition = listOfNotNull(scope, search, d.filterSpec.fromParams(request.params, context), d.filterSpec.fromJson(request.filter, context)).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) d.searchSpec.rank(parsed) + d.filterSpec.sort(emptyList(), context) else d.filterSpec.sort(request.sortParams, context)
        val result = Paging.fetch(joined().select(selection(d)).where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        return Page(result, condition, scope, search, parsed.ignoredQualifiers)
    }

    /** Each facet counts with every other filter applied but its own. */
    internal fun facets(request: ListSearchRequest, page: Page, d: InvoiceExpressions): Map<String, List<FacetCount>> {
        val context = support.filterContext()
        return request.facets.take(Facets.MAX_FACETS).associateWith { key ->
            val others = Facets.paramsWithout(request.params, key)
            val condition = listOfNotNull(page.scope, page.search, d.filterSpec.fromParams(others, context), d.filterSpec.fromJson(request.filter, context)).allOf()
            when (key) {
                "status" -> Facets.count(joined(), condition, d.displayStatus) { v -> v?.let(InvoiceExpressions::label) }
                "locationId" -> Facets.count(joined(), condition, InvoicesTable.locationId) { v -> v?.let { Names.locations(listOf(UUID.fromString(it)))[UUID.fromString(it)] } }
                "customerType" -> Facets.count(joined(), condition, InvoicesTable.buyerType) { v -> v?.let { runCatching { CustomerType.valueOf(it).label }.getOrDefault(it) } ?: "Walk-in" }
                "sourceType" -> Facets.count(joined(), condition, InvoicesTable.sourceType) { v -> if (v == "PRO_FORMA") "Pro-forma" else "POS sale" }
                "invoiceKind" -> Facets.count(joined(), condition, InvoicesTable.invoiceKind)
                "delivery" -> Facets.count(joined(), condition, d.delivery) { v -> v?.let(InvoiceExpressions::deliveryLabel) }
                "ageingBucket" -> Facets.count(joined(), condition, d.ageingBucket) { v -> v?.let(InvoiceExpressions::ageingLabel) }
                else -> throw ApiErrors.invalidFilter(key, "Not a facet", FACETS)
            }
        }
    }

    /** Tenant, plus the caller's location when they are tied to one: "search never returns invoices outside the user's scope". */
    fun visibility(): Op<Boolean> = listOfNotNull(
        InvoicesTable.tenantId eq support.tenantId(),
        support.visibleLocations()?.let { InvoicesTable.locationId inList it },
    ).allOf()!!

    fun joined() = InvoicesTable.join(LocationsTable, JoinType.LEFT, InvoicesTable.locationId, LocationsTable.id)

    internal fun selection(d: InvoiceExpressions): List<Expression<*>> =
        InvoicesTable.columns + listOf(LocationsTable.name, d.balance, d.displayStatus, d.daysOverdue, d.ageingBucket, d.delivery)

    companion object {
        val FACETS = listOf("status", "locationId", "customerType", "sourceType", "invoiceKind", "delivery", "ageingBucket")
    }
}
