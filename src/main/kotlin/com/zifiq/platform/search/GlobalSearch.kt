package com.zifiq.platform.search

import com.zifiq.common.paging.Paging
import com.zifiq.common.paging.PagingPolicy
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.tenancy.TenantScopedTransaction
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * One searchable entity type for the header search box — docs/03 §4.7. Implemented by each module
 * for its main entities (dependency inversion: platform never depends on business modules). A
 * provider applies its own `SearchSpec`, read scope and permissions.
 */
interface SearchProvider {
    /** 'customer', 'stock-item', … */
    val type: String

    /** 'Customers' */
    val label: String

    /** Where "See all" goes, with `q` appended. */
    val listRoute: String

    fun canSearch(): Boolean

    /** Best matches first. Runs inside a tenant-scoped transaction opened by the caller. */
    fun search(q: String, limit: Int): List<SearchHit>
}

data class SearchHit(
    val type: String,
    val id: UUID,
    val title: String,
    val subtitle: String?,
    val ref: String?,
    val route: String,
)

data class SearchGroup(val type: String, val label: String, val hits: List<SearchHit>, val more: Boolean, val moreRoute: String)

data class GlobalSearchResult(val groups: List<SearchGroup>, val timedOut: List<String>)

@Tag(name = "Search", description = "Global search across every module the caller can access (docs/03 §4.7)")
@SecurityRequirement(name = "bearerAuth")
interface GlobalSearchApi {
    @Operation(summary = "Grouped hits per entity type; an exact reference jumps straight to that record")
    fun search(q: String, types: Set<String>?, limit: Int): ApiEnvelope<GlobalSearchResult>
}

@RestController
@RequestMapping("/api/v1/search")
class GlobalSearchController(
    private val providers: List<SearchProvider>,
    private val tx: TenantScopedTransaction,
) : GlobalSearchApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping
    override fun search(
        @RequestParam q: String,
        @RequestParam(required = false) types: Set<String>?,
        @RequestParam(defaultValue = "${PagingPolicy.GLOBAL_SEARCH_PER_TYPE_DEFAULT}") limit: Int,
    ): ApiEnvelope<GlobalSearchResult> {
        val text = q.trim()
        if (text.length < 2) throw ApiErrors.validationFailed(mapOf("q" to "must be at least 2 characters"))
        val perType = PagingPolicy.globalSearchLimit(limit)
        val timedOut = mutableListOf<String>()
        val groups = providers
            .filter { types.isNullOrEmpty() || it.type in types }
            .filter { tx.execute { it.canSearch() } }
            .mapNotNull { provider ->
                // Each provider gets its own short transaction and time budget, so one slow
                // module can't hold up (or abort) the others; it is reported in `timedOut`.
                val hits = runCatching {
                    tx.execute {
                        Paging.limitStatementTime(PROVIDER_TIMEOUT)
                        provider.search(text, perType + 1)
                    }
                }.getOrElse { error ->
                    logger.warn("Global search provider {} failed: {}", provider.type, error.message)
                    timedOut += provider.type
                    return@mapNotNull null
                }
                if (hits.isEmpty()) return@mapNotNull null
                SearchGroup(provider.type, provider.label, hits.take(perType), hits.size > perType, "${provider.listRoute}?q=${java.net.URLEncoder.encode(text, Charsets.UTF_8)}")
            }

        // An exact reference ("DLR-42") is the single top hit.
        val exact = groups.flatMap { it.hits }.firstOrNull { it.ref != null && it.ref.equals(text, ignoreCase = true) }
        val result = if (exact != null) {
            GlobalSearchResult(listOf(SearchGroup(exact.type, groups.first { it.type == exact.type }.label, listOf(exact), false, exact.route)), timedOut)
        } else {
            GlobalSearchResult(groups, timedOut)
        }
        return ApiEnvelope(result)
    }

    private companion object {
        const val PROVIDER_TIMEOUT = PagingPolicy.GLOBAL_SEARCH_PROVIDER_TIMEOUT
    }
}
