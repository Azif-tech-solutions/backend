package com.zifiq.common.filter

import com.zifiq.common.paging.FacetCount
import org.jetbrains.exposed.sql.ColumnSet
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Count

/**
 * Facet counts for filter chips — docs/03 §5.5. The caller passes each facet's condition with that
 * facet's own filter removed, so a chip shows how many rows choosing it would give.
 */
object Facets {
    const val MAX_VALUES = com.zifiq.common.paging.PagingPolicy.FACET_MAX_VALUES
    const val MAX_FACETS = com.zifiq.common.paging.PagingPolicy.FACET_MAX_FACETS

    /**
     * The URL filters minus the facet's own (`key`, `key.op`, `key!`, `keyMin`, `keyMax`), so a facet
     * counts what choosing each of its values would give.
     */
    fun paramsWithout(params: Map<String, List<String>>, key: String): Map<String, List<String>> =
        params.filterKeys { it.substringBefore('.').removeSuffix("!").removeSuffix("Min").removeSuffix("Max") != key }

    fun count(
        base: ColumnSet,
        condition: Op<Boolean>?,
        expression: Expression<*>,
        label: (String?) -> String? = { it },
    ): List<FacetCount> {
        val total = Count(expression)
        val query = base.select(expression, total)
        if (condition != null) query.where(condition)
        val rows = query.groupBy(expression).orderBy(total to SortOrder.DESC).limit(MAX_VALUES + 1).toList()
        val counts = rows.take(MAX_VALUES).map { row ->
            val value = row[expression]?.toString()
            FacetCount(value, label(value), row[total])
        }
        return if (rows.size > MAX_VALUES) counts + FacetCount("other", "Other", -1) else counts
    }
}
