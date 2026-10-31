package com.zifiq.account.service

import com.zifiq.account.model.ExpenseCategoriesTable
import com.zifiq.account.model.OperatingCostsTable
import com.zifiq.account.model.PettyCashPaymentsTable
import com.zifiq.account.spi.ExpenseAuditRow
import com.zifiq.account.spi.ExpenseFilter
import com.zifiq.account.spi.ExpenseQueries
import com.zifiq.account.spi.InputVatLineRef
import com.zifiq.account.spi.VatInputQueries
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.directory.Names
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Expenses / Payment Reports source (docs/10 §4.2): running costs and petty-cash payments in a date
 * range, merged. A report, not an unbounded ledger — `from`/`to` are required and capped at 366 days,
 * so the merge and the totals are bounded. Only APPROVED entries count toward the totals and groups.
 */
@Service
class ExpenseAuditService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
) : ExpenseQueries, VatInputQueries {
    override fun runningCostInputVat(locationId: UUID, from: LocalDate, to: LocalDate): List<InputVatLineRef> = tx.execute {
        OperatingCostsTable.selectAll()
            .andWhere { OperatingCostsTable.tenantId eq support.tenantId() }
            .andWhere { OperatingCostsTable.locationId eq locationId }
            .andWhere { OperatingCostsTable.status eq "APPROVED" }
            .andWhere { OperatingCostsTable.paymentDate greaterEq from }
            .andWhere { OperatingCostsTable.paymentDate lessEq to }
            .andWhere { OperatingCostsTable.inputVatAmount greater java.math.BigDecimal.ZERO }
            .map {
                val vat = it[OperatingCostsTable.inputVatAmount]
                InputVatLineRef(
                    date = it[OperatingCostsTable.paymentDate], ref = it[OperatingCostsTable.ref],
                    counterparty = it[OperatingCostsTable.payee], counterpartyTin = it[OperatingCostsTable.supplierTin],
                    taxableAmount = it[OperatingCostsTable.amount] - vat, vat = vat,
                )
            }
    }

    override fun expenses(filter: ExpenseFilter): PageResult<ExpenseAuditRow> = tx.execute {
        if (ChronoUnit.DAYS.between(filter.from, filter.to) > 366) throw ApiErrors.rangeTooLarge(366)
        val tenant = support.tenantId()
        val visible = support.visibleLocations()
        val q = filter.q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val running = OperatingCostsTable.selectAll()
            .andWhere { OperatingCostsTable.tenantId eq tenant }
            .andWhere { OperatingCostsTable.paymentDate greaterEq filter.from }
            .andWhere { OperatingCostsTable.paymentDate lessEq filter.to }
            .also { query ->
                if (visible != null) query.andWhere { OperatingCostsTable.locationId inList visible }
                filter.categoryId?.let { c -> query.andWhere { OperatingCostsTable.expenseCategoryId eq c } }
                filter.locationId?.let { l -> query.andWhere { OperatingCostsTable.locationId eq l } }
                filter.method?.let { m -> query.andWhere { OperatingCostsTable.paymentMethod eq m } }
                filter.status?.let { s -> query.andWhere { OperatingCostsTable.status eq s } }
                q?.let { query.andWhere { (OperatingCostsTable.payee.lowerCase() like "%$q%") or (OperatingCostsTable.ref.lowerCase() like "%$q%") } }
            }
            .map {
                ExpenseAuditRow(
                    id = it[OperatingCostsTable.id].value, source = "RUNNING_COST", date = it[OperatingCostsTable.paymentDate],
                    ref = it[OperatingCostsTable.ref], purpose = it[OperatingCostsTable.description], expenseCategoryId = it[OperatingCostsTable.expenseCategoryId],
                    payee = it[OperatingCostsTable.payee], method = it[OperatingCostsTable.paymentMethod], approvedBy = it[OperatingCostsTable.approvedByUserId]?.toString(),
                    status = it[OperatingCostsTable.status], amount = it[OperatingCostsTable.amount], inputVat = it[OperatingCostsTable.inputVatAmount],
                    locationId = it[OperatingCostsTable.locationId],
                )
            }

        // Petty-cash payments have no location column, so they are left out when the view is scoped to a
        // location, or when the method filter asks for a non-petty-cash method.
        val includePetty = visible == null && filter.locationId == null && (filter.method == null || filter.method == "PETTY_CASH")
        val petty = if (!includePetty) emptyList() else PettyCashPaymentsTable.selectAll()
            .andWhere { PettyCashPaymentsTable.tenantId eq tenant }
            .andWhere { PettyCashPaymentsTable.paymentDate greaterEq filter.from }
            .andWhere { PettyCashPaymentsTable.paymentDate lessEq filter.to }
            .also { query ->
                filter.categoryId?.let { c -> query.andWhere { PettyCashPaymentsTable.expenseCategoryId eq c } }
                filter.status?.let { s -> query.andWhere { PettyCashPaymentsTable.status eq s } }
                q?.let { query.andWhere { (PettyCashPaymentsTable.paidTo.lowerCase() like "%$q%") or (PettyCashPaymentsTable.ref.lowerCase() like "%$q%") } }
            }
            .map {
                ExpenseAuditRow(
                    id = it[PettyCashPaymentsTable.id].value, source = "PETTY_CASH", date = it[PettyCashPaymentsTable.paymentDate],
                    ref = it[PettyCashPaymentsTable.ref], purpose = it[PettyCashPaymentsTable.description], expenseCategoryId = it[PettyCashPaymentsTable.expenseCategoryId],
                    payee = it[PettyCashPaymentsTable.paidTo], method = "PETTY_CASH", approvedBy = null,
                    status = it[PettyCashPaymentsTable.status], amount = it[PettyCashPaymentsTable.amount], inputVat = BigDecimal.ZERO, locationId = null,
                )
            }

        // Running-cost approvers are user ids; resolve to names (petty-cash approvers are already names).
        val approverNames = Names.users(running.mapNotNull { it.approvedBy }.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() })
        val merged = (running.map { it.copy(approvedBy = it.approvedBy?.let { id -> approverNames[runCatching { UUID.fromString(id) }.getOrNull()] ?: id }) } + petty)
            .sortedWith(compareByDescending<ExpenseAuditRow> { it.date }.thenBy { it.ref ?: "" })

        fun total(rows: List<ExpenseAuditRow>): BigDecimal = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.amount }
        val categoryNames = categoryNames(merged.map { it.expenseCategoryId }.toSet())
        val approved = merged.filter { it.status == "APPROVED" }
        val byCategory = approved.groupBy { it.expenseCategoryId }
            .map { (id, rows) -> mapOf("category" to (categoryNames[id] ?: id.toString()), "count" to rows.size, "total" to total(rows)) }
            .sortedByDescending { it["total"] as BigDecimal }
        val byMethod = approved.groupBy { it.method }
            .map { (m, rows) -> mapOf("method" to m, "count" to rows.size, "total" to total(rows)) }
        val pending = merged.filter { it.status == "PENDING_APPROVAL" }
        val summary = mapOf(
            "totalExpenditure" to total(approved),
            "approvedEntries" to approved.size,
            "categoriesUsed" to approved.map { it.expenseCategoryId }.distinct().size,
            "largestCategory" to byCategory.firstOrNull()?.let { mapOf("name" to it["category"], "total" to it["total"]) },
            "pendingApproval" to mapOf("count" to pending.size, "amount" to total(pending)),
            "byCategory" to byCategory,
            "byMethod" to byMethod,
        )

        val request = PageRequest.of(filter.page, filter.size)
        val total = merged.size.toLong()
        val items = merged.drop(request.offset.toInt()).take(request.size)
        PageResult(
            items = items, page = request.page, size = request.size, totalItems = total,
            totalPages = if (total == 0L) 0 else ((total + request.size - 1) / request.size).toInt(),
            totalIsEstimate = false, summary = summary,
        )
    }

    private fun categoryNames(ids: Set<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap() else ExpenseCategoriesTable.select(ExpenseCategoriesTable.id, ExpenseCategoriesTable.name)
            .where { ExpenseCategoriesTable.id inList ids }
            .associate { it[ExpenseCategoriesTable.id].value to it[ExpenseCategoriesTable.name] }
}
