package com.zifiq.common.paging

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Every paging, lookup, search and facet number in the API (docs/03 §5.1). Change them here and
 * nowhere else: `PageRequest`, `Paging`, `Facets`, every picker, every search provider and every
 * controller default read these constants.
 */
object PagingPolicy {
    /** Rows per page when `size` is not given. */
    const val DEFAULT_PAGE_SIZE = 50

    /** Largest `size` a client may ask for. */
    const val MAX_PAGE_SIZE = 200

    /**
     * Counting stops here, so a broad filter never spends its time counting millions of rows
     * (`totalIsEstimate` is then true). Pages can't start past it either: an offset that deep means
     * the database skips that many rows first, so the client is asked to narrow the filters instead.
     */
    const val COUNT_CAP = 10_000L

    /** Statement timeout for list, search and report queries; a cancelled query surfaces as SEARCH_TIMEOUT. */
    const val LIST_STATEMENT_TIMEOUT = "5s"

    /** Pickers / lookups (`…/lookup?q=`): default and largest number of rows. */
    const val LOOKUP_DEFAULT = 20
    const val LOOKUP_MAX = 50

    /** Header search: hits per type, default and largest. */
    const val GLOBAL_SEARCH_PER_TYPE_DEFAULT = 5
    const val GLOBAL_SEARCH_PER_TYPE_MAX = 20

    /** Header search: each provider's statement timeout, so one slow provider can't stall the rest. */
    const val GLOBAL_SEARCH_PROVIDER_TIMEOUT = "1500ms"

    /** Facets: values shown per facet (the rest fold into "Other"), and facets per request. */
    const val FACET_MAX_VALUES = 20
    const val FACET_MAX_FACETS = 6

    /** "Did you mean" suggestions on an empty search. */
    const val SUGGESTIONS = 3

    /** Ranked "top N" blocks inside summaries (e.g. outstanding by customer). */
    const val SUMMARY_TOP_N = 20

    fun lookupLimit(requested: Int?): Int = (requested ?: LOOKUP_DEFAULT).coerceIn(1, LOOKUP_MAX)

    fun globalSearchLimit(requested: Int?): Int = (requested ?: GLOBAL_SEARCH_PER_TYPE_DEFAULT).coerceIn(1, GLOBAL_SEARCH_PER_TYPE_MAX)
}

/**
 * `?page=&size=` for any paged endpoint. Bind it with `@ParameterObject paging: PageParams` and call
 * [toRequest]; validation and defaults come from [PagingPolicy].
 */
data class PageParams(
    @field:Schema(description = "0-based page number", example = "0")
    val page: Int? = null,
    @field:Schema(description = "Rows per page (1–${PagingPolicy.MAX_PAGE_SIZE}, default ${PagingPolicy.DEFAULT_PAGE_SIZE})", example = "${PagingPolicy.DEFAULT_PAGE_SIZE}")
    val size: Int? = null,
) {
    fun toRequest(): PageRequest = PageRequest.of(page, size)
}
