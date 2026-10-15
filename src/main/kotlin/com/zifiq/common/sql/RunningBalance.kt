package com.zifiq.common.sql

import com.zifiq.common.search.RawExpression
import java.math.BigDecimal
import org.jetbrains.exposed.sql.Expression

/**
 * Running balances for ledgers (cash, bank, stock): one window sum over the period's rows in
 * posting order, to which the caller adds the balance brought forward from before the period.
 * Never a correlated subquery per row — that re-sums the account's whole history for every row
 * shown, which grows without bound as the ledger does.
 */
object RunningBalance {
    /** Σ [signed] over the selected rows in [order] (a window; add the opening balance). */
    fun window(signed: Expression<*>, vararg order: Expression<*>): Expression<BigDecimal> = RawExpression({
        +"(sum("; +signed; +") over (order by "
        order.forEachIndexed { index, expression -> if (index > 0) +", "; +expression }
        +"))"
    })

    /** [amount] when [positive] holds, else −[amount]: a ledger row's effect on the balance. */
    fun signed(amount: Expression<*>, positive: Expression<Boolean>): Expression<BigDecimal> = RawExpression({
        +"(case when "; +positive; +" then "; +amount; +" else -"; +amount; +" end)"
    })
}
