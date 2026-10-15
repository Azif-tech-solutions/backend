package com.zifiq.common.paging

import com.zifiq.common.errors.ApiErrors
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.transactions.TransactionManager

/** `page` (0-based) and `size`, validated against [PagingPolicy] — docs/implemented/03-cross-module-conventions.md §5.1. */
data class PageRequest(val page: Int, val size: Int) {
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = PagingPolicy.DEFAULT_PAGE_SIZE
        const val MAX_SIZE = PagingPolicy.MAX_PAGE_SIZE

        fun of(page: Int?, size: Int?): PageRequest {
            val resolvedSize = size ?: DEFAULT_SIZE
            if (resolvedSize !in 1..MAX_SIZE) throw ApiErrors.pageSizeTooLarge(MAX_SIZE)
            val resolvedPage = page ?: 0
            if (resolvedPage < 0) throw ApiErrors.validationFailed(mapOf("page" to "Page must be 0 or greater"))
            // A page that starts past the count cap would make the database skip that many rows first.
            if (resolvedPage.toLong() * resolvedSize >= PagingPolicy.COUNT_CAP) throw ApiErrors.pageTooDeep(PagingPolicy.COUNT_CAP)
            return PageRequest(resolvedPage, resolvedSize)
        }
    }
}

data class FacetCount(val value: String?, val label: String?, val count: Long)

/** Search/filter feedback shown next to the results. */
data class PageMeta(
    val appliedFilters: Map<String, String> = emptyMap(),
    val ignoredQualifiers: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /** With `highlight=true`: row id -> matched fields with `<mark>`ed, HTML-escaped snippets. */
    val matches: Map<String, List<com.zifiq.common.search.Match>> = emptyMap(),
)

data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    /** True when the result set exceeded [Paging.COUNT_CAP]: totalItems is then "at least" this many. */
    val totalIsEstimate: Boolean,
    val facets: Map<String, List<FacetCount>>? = null,
    val summary: Map<String, Any?>? = null,
    val meta: PageMeta = PageMeta(),
) {
    /** The same page with its rows replaced, e.g. raw rows mapped after a batch lookup. */
    fun <R> withItems(items: List<R>): PageResult<R> = PageResult(items, page, size, totalItems, totalPages, totalIsEstimate, facets, summary, meta)
}

object Paging {
    /** Counts stop here so a broad search never spends its time counting millions of rows. */
    const val COUNT_CAP = PagingPolicy.COUNT_CAP

    /** Statement timeout applied to search/list queries (docs/03 §5.6); a cancelled query surfaces as SEARCH_TIMEOUT. */
    const val SEARCH_TIMEOUT = PagingPolicy.LIST_STATEMENT_TIMEOUT

    /** Fetches one page of [query] (already filtered and ordered) and a capped total count. */
    fun <T> fetch(query: Query, request: PageRequest, map: (ResultRow) -> T): PageResult<T> {
        limitStatementTime()
        val counted = query.copy().limit(COUNT_CAP.toInt() + 1).count()
        val capped = counted > COUNT_CAP
        val total = if (capped) COUNT_CAP else counted
        val items = query.copy().limit(request.size).offset(request.offset).map(map)
        return PageResult(
            items = items,
            page = request.page,
            size = request.size,
            totalItems = total,
            totalPages = if (total == 0L) 0 else ((total + request.size - 1) / request.size).toInt(),
            totalIsEstimate = capped,
        )
    }

    /**
     * As [fetch], for numbered reports: [map] gets each row's 1-based position in the whole result
     * (page offset included), so "S/N" continues across pages.
     */
    fun <T> fetchNumbered(query: Query, request: PageRequest, map: (sn: Int, row: ResultRow) -> T): PageResult<T> {
        val page = fetch(query, request) { it }
        return page.withItems(page.items.mapIndexed { index, row -> map((request.offset + index + 1).toInt(), row) })
    }

    /** Transaction-local, so it never leaks to other work on the pooled connection. */
    fun limitStatementTime(timeout: String = SEARCH_TIMEOUT) {
        TransactionManager.current().exec("select set_config('statement_timeout', '$timeout', true)")
    }
}
