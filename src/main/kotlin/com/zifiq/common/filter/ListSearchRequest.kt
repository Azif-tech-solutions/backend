package com.zifiq.common.filter

import com.fasterxml.jackson.databind.JsonNode

data class SortField(val field: String, val dir: String = "asc")

/**
 * Body of `POST <list>/search` — the same for every list endpoint (docs/03 §5.3). `params` carries
 * simple filters (the same names as the GET query parameters) alongside the advanced `filter` tree.
 */
data class ListSearchRequest(
    val q: String? = null,
    val filter: JsonNode? = null,
    val params: Map<String, List<String>> = emptyMap(),
    val sort: List<SortField> = emptyList(),
    val page: Int? = null,
    val size: Int? = null,
    val facets: List<String> = emptyList(),
    val highlight: Boolean = false,
    val summary: Boolean = true,
) {
    val sortParams: List<String> get() = sort.map { "${it.field},${it.dir}" }

    companion object {
        /** Splits GET query parameters into a request: reserved names become fields, the rest are filters. */
        fun fromQuery(query: Map<String, List<String>>): ListSearchRequest = ListSearchRequest(
            q = query["q"]?.firstOrNull(),
            params = query.filterKeys { it !in FilterSpec.RESERVED_PARAMS },
            sort = query["sort"].orEmpty().map { SortField(it.substringBefore(','), it.substringAfter(',', "asc")) },
            page = query["page"]?.firstOrNull()?.toIntOrNull(),
            size = query["size"]?.firstOrNull()?.toIntOrNull(),
            facets = query["facets"].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() },
            highlight = query["highlight"]?.firstOrNull() == "true",
            summary = query["summary"]?.firstOrNull() != "false",
        )
    }
}
