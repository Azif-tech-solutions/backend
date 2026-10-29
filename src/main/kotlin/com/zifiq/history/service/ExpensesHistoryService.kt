package com.zifiq.history.service

import com.zifiq.account.spi.ExpenseFilter
import com.zifiq.account.spi.ExpenseQueries
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.ExpenseHistoryRow
import com.zifiq.platform.directory.Ref
import java.time.LocalDate
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Expenses / Payment Reports (docs/10 §4.2): the account module's running costs and petty-cash
 * payments (through its SPI), with the category wrapped in a reference for the response.
 */
@Service
class ExpensesHistoryService(private val expenses: ExpenseQueries) {
    fun list(
        from: LocalDate,
        to: LocalDate,
        categoryId: UUID?,
        method: String?,
        locationId: UUID?,
        status: String?,
        q: String?,
        page: Int?,
        size: Int?,
    ): PageResult<ExpenseHistoryRow> {
        val result = expenses.expenses(ExpenseFilter(from, to, categoryId, method, locationId, status, q, page ?: 0, size ?: 20))
        return result.withItems(
            result.items.map {
                ExpenseHistoryRow(
                    date = it.date, ref = it.ref, purpose = it.purpose, category = Ref.expenseCategory(it.expenseCategoryId),
                    payee = it.payee, method = it.method, approvedBy = it.approvedBy, status = it.status,
                    amount = it.amount, inputVat = it.inputVat, source = it.source,
                )
            },
        )
    }
}
