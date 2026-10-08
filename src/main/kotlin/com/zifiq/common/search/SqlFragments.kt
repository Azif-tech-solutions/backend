package com.zifiq.common.search

import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder

/**
 * Exposed 0.61 has no built-in operators for pg_trgm, unaccent or full-text search. These two
 * tiny wrappers let SearchSpec/FilterCompiler emit that SQL while still binding every value as a
 * parameter (`QueryBuilder.registerArgument`) — user input is never concatenated into SQL.
 */
internal class RawOp(private val build: QueryBuilder.() -> Unit) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder.build()
}

internal class RawExpression<T>(private val build: QueryBuilder.() -> Unit) : Expression<T>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder.build()
}

/** Escapes LIKE/ILIKE metacharacters so user input matches literally (PostgreSQL's default escape is `\`). */
internal fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

internal fun Iterable<Op<Boolean>>.anyOf(): Op<Boolean>? = reduceOrNull { a, b -> OrOp(a, b) }
internal fun Iterable<Op<Boolean>>.allOf(): Op<Boolean>? = reduceOrNull { a, b -> AndOp(a, b) }

private class OrOp(private val left: Op<Boolean>, private val right: Op<Boolean>) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        +"("; +left; +" OR "; +right; +")"
    }
}

private class AndOp(private val left: Op<Boolean>, private val right: Op<Boolean>) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        +"("; +left; +" AND "; +right; +")"
    }
}

/**
 * `NOT coalesce(x, false)`: a plain `NOT x` is NULL when x is NULL (e.g. `-word` against a row
 * whose column is empty), which would wrongly drop that row — an empty field doesn't match the
 * excluded word, so the row must stay.
 */
internal class NotOp(private val inner: Op<Boolean>) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        +"(NOT coalesce("; +inner; +", false))"
    }
}
