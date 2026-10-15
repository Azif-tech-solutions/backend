package com.zifiq.customers.service

import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageRequest
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import org.springframework.stereotype.Component

/** Global search: customers (docs/04 §9), within the caller's read scope. */
@Component
class CustomerSearchProvider(
    private val support: CustomerSupport,
    private val listService: CustomerListService,
) : SearchProvider {
    override val type = "customer"
    override val label = "Customers"
    override val listRoute = "/customers"

    override fun canSearch() = support.can("customers.read") || support.can("customers.read.own")

    override fun search(q: String, limit: Int): List<SearchHit> =
        listService.list(ListSearchRequest(q = q, size = limit, summary = false)).items.map { customer ->
            SearchHit(
                type = type,
                id = customer.id,
                title = customer.name,
                subtitle = listOfNotNull(customer.code, customer.dealerCategoryName ?: customer.type.label, customer.phone?.let { "…" + it.filter(Char::isDigit).takeLast(4) })
                    .joinToString(" · "),
                ref = customer.code,
                route = "/customers/${customer.id}",
            )
        }
}

@Component
class CorrespondentSearchProvider(
    private val support: CustomerSupport,
    private val correspondentService: CorrespondentService,
) : SearchProvider {
    override val type = "correspondent"
    override val label = "Correspondents"
    override val listRoute = "/customers/correspondents"

    override fun canSearch() = support.can("customers.read")

    override fun search(q: String, limit: Int): List<SearchHit> =
        correspondentService.list(q, emptySet(), emptySet(), null, null, null, null, PageRequest.of(0, limit)).items.map {
            SearchHit(
                type = type,
                id = it.id,
                title = it.name,
                subtitle = listOfNotNull(it.code, it.region, it.status.lowercase().replace('_', '-')).joinToString(" · "),
                ref = it.code,
                route = "/customers/correspondents/${it.id}",
            )
        }
}
