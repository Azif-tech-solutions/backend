package com.zifiq.account.service

import com.zifiq.account.model.ExpenseCategoriesTable
import com.zifiq.account.model.OperatingCostsTable
import com.zifiq.account.model.PettyCashPaymentsTable
import com.zifiq.account.spi.AccountReports
import com.zifiq.account.spi.ExpenseLine
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/** Reporting reads for the SCI (docs/13 §4.2): operating expenses by category. Read-only. */
@Service
class AccountReportsService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
) : AccountReports {
    override fun operatingExpenses(from: LocalDate, to: LocalDate, locationIds: Set<UUID>?): List<ExpenseLine> = tx.execute {
        val totals = HashMap<UUID, BigDecimal>()
        val opAmt = OperatingCostsTable.amount.sum()
        OperatingCostsTable.select(OperatingCostsTable.expenseCategoryId, opAmt)
            .where {
                var c = (OperatingCostsTable.tenantId eq support.tenantId()) and (OperatingCostsTable.status eq "APPROVED") and
                    (OperatingCostsTable.paymentDate greaterEq from) and (OperatingCostsTable.paymentDate lessEq to)
                if (locationIds != null) c = c and (OperatingCostsTable.locationId inList locationIds)
                c
            }
            .groupBy(OperatingCostsTable.expenseCategoryId)
            .forEach { it[opAmt]?.let { amt -> totals.merge(it[OperatingCostsTable.expenseCategoryId], amt, BigDecimal::add) } }

        // Petty cash carries no location, so it only contributes to a company-wide total.
        if (locationIds == null) {
            val pcAmt = PettyCashPaymentsTable.amount.sum()
            PettyCashPaymentsTable.select(PettyCashPaymentsTable.expenseCategoryId, pcAmt)
                .where {
                    (PettyCashPaymentsTable.tenantId eq support.tenantId()) and (PettyCashPaymentsTable.status eq "POSTED") and
                        (PettyCashPaymentsTable.paymentDate greaterEq from) and (PettyCashPaymentsTable.paymentDate lessEq to)
                }
                .groupBy(PettyCashPaymentsTable.expenseCategoryId)
                .forEach { r -> r[pcAmt]?.let { amt -> totals.merge(r[PettyCashPaymentsTable.expenseCategoryId], amt, BigDecimal::add) } }
        }
        if (totals.isEmpty()) return@execute emptyList()
        val categories = ExpenseCategoriesTable.selectAll().where { ExpenseCategoriesTable.id inList totals.keys }
            .associate { it[ExpenseCategoriesTable.id].value to (it[ExpenseCategoriesTable.code] to it[ExpenseCategoriesTable.name]) }
        totals.entries
            .map { (id, amt) -> ExpenseLine(id, categories[id]?.first ?: "", categories[id]?.second ?: "Uncategorised", amt) }
            .sortedByDescending { it.amount }
    }
}
