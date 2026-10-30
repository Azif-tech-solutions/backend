package com.zifiq.history.dto

import com.zifiq.platform.directory.Ref
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One expenditure in the Expenses / Payment Reports (docs/10 §4.2): a running cost or petty-cash
 * payment. `category` is a reference; `source` says which table it came from. Only APPROVED entries
 * count toward the report's totals.
 */
data class ExpenseHistoryRow(
    val date: LocalDate,
    val ref: String?,
    val purpose: String?,
    val category: Ref,
    val payee: String,
    val method: String,
    val approvedBy: String?,
    val status: String,
    val amount: BigDecimal,
    val inputVat: BigDecimal,
    /** RUNNING_COST or PETTY_CASH. */
    val source: String,
)
