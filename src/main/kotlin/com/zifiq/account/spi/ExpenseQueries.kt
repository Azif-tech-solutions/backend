package com.zifiq.account.spi

import com.zifiq.common.paging.PageResult
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One expenditure — a running cost or a petty-cash payment — for Audit's Expenses / Payment Reports
 * (docs/10 §4.2). The two sources share this shape; `source` says which. Ids are plain; the audit
 * module wraps the category in a reference for its response.
 */
data class ExpenseAuditRow(
    val id: UUID,
    /** RUNNING_COST or PETTY_CASH. */
    val source: String,
    val date: LocalDate,
    val ref: String?,
    val purpose: String?,
    val expenseCategoryId: UUID,
    val payee: String,
    /** PETTY_CASH, CASH, BANK_TRANSFER or CHEQUE. */
    val method: String,
    val approvedBy: String?,
    /** APPROVED, PENDING_APPROVAL, REJECTED or REVERSED. */
    val status: String,
    val amount: BigDecimal,
    val inputVat: BigDecimal,
    val locationId: UUID?,
)

/** A date-ranged expenses report (required `from`/`to`, ≤ 366 days). */
data class ExpenseFilter(
    val from: LocalDate,
    val to: LocalDate,
    val categoryId: UUID? = null,
    val method: String? = null,
    val locationId: UUID? = null,
    val status: String? = null,
    val q: String? = null,
    val page: Int = 0,
    val size: Int = 20,
)

interface ExpenseQueries {
    /**
     * Running costs and petty-cash payments in range, merged. The page's `summary` carries
     * `totalExpenditure` (APPROVED only), `approvedEntries`, `categoriesUsed`, `largestCategory`,
     * `pendingApproval`, and the `byCategory` / `byMethod` groups.
     */
    fun expenses(filter: ExpenseFilter): PageResult<ExpenseAuditRow>
}
