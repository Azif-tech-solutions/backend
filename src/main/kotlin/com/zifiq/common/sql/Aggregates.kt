package com.zifiq.common.sql

import com.zifiq.common.search.RawExpression
import java.math.BigDecimal
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow

/**
 * Conditional aggregates for list summaries: `count(*) filter (where …)` and
 * `coalesce(sum(…) filter (where …), 0)`, so a header with several totals is one SQL pass over the
 * list's own condition — never rows loaded into memory and counted there. Keep each expression in a
 * `val`, select them together, and read them back with [long] / [money].
 */
object Aggregates {
    fun count(where: Op<Boolean>? = null): Expression<Long> = RawExpression({
        +"count(*)"
        if (where != null) { +" filter (where "; +where; +")" }
    })

    fun sum(value: Expression<*>, where: Op<Boolean>? = null): Expression<BigDecimal> = RawExpression({
        +"coalesce(sum("; +value; +")"
        if (where != null) { +" filter (where "; +where; +")" }
        +", 0)"
    })

    fun long(row: ResultRow, expression: Expression<Long>): Long = (row[expression] as Number).toLong()

    fun money(row: ResultRow, expression: Expression<BigDecimal>): BigDecimal = com.zifiq.common.money.Money.round(row[expression])
}
