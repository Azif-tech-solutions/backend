package com.zifiq.communication.service

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesLineRef
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.communication.dto.CommsReportRow
import com.zifiq.communication.dto.CorrespondentCustomerRow
import com.zifiq.communication.dto.CorrespondentRepRow
import com.zifiq.communication.dto.CorrespondentsReport
import com.zifiq.customers.spi.Correspondents
import com.zifiq.communication.model.CommissionPaymentsTable
import com.zifiq.communication.model.CommissionRunBeneficiariesTable
import com.zifiq.communication.model.CommissionRunLinesTable
import com.zifiq.communication.model.CommissionRunsTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/**
 * The Comms. Report (docs/12 §4.4): unpaid commission by beneficiary and period, over approved runs
 * only. Earned comes from the run's per-beneficiary total; paid is the sum of its PAID payments;
 * outstanding = earned − paid. The Correspondents Report (§4.5) arrives in the next slice.
 */
@Service
class ReportService(
    private val support: CommunicationSupport,
    private val tx: TenantScopedTransaction,
    private val sales: SalesQueries,
    private val correspondents: Correspondents,
) {
    /**
     * The Correspondents Report (docs/12 §4.5): rep activity by customer, independent of receipt
     * confirmation (with a pending-confirmation column to reconcile against commission). "Brought in"
     * is the customer's first-ever completed sale falling inside the range.
     */
    fun correspondents(locationId: UUID?, from: LocalDate, to: LocalDate, correspondentId: UUID?, view: String): CorrespondentsReport = tx.execute {
        val lines = sales.linesFor(SalesLineFilter(completedTo = to, locationId = locationId))
            .filter { !it.saleDate.isBefore(from) && it.correspondentId != null && (correspondentId == null || it.correspondentId == correspondentId) }
        val firstSale = sales.firstCompletedSaleDates(lines.mapNotNull { it.customerId }.distinct())
        fun broughtIn(customerId: UUID?) = customerId != null && firstSale[customerId]?.let { !it.isBefore(from) && !it.isAfter(to) } == true
        val repInfo = lines.mapNotNull { it.correspondentId }.distinct().associateWith { runCatching { correspondents.get(it) }.getOrNull() }
        val totalRevenue = lines.fold(BigDecimal.ZERO) { a, l -> a + l.lineNet }

        if (view == "customer") {
            val rows = lines.groupBy { it.correspondentId!! to (it.customerId ?: NO_CUSTOMER) }.map { (key, ls) ->
                val rep = repInfo[key.first]
                CorrespondentCustomerRow(
                    rep = rep?.name ?: "", repCode = rep?.code ?: "", customer = ls.first().customerName, customerCode = ls.first().customerCode,
                    newCustomer = broughtIn(ls.first().customerId), transactions = ls.map { it.saleId }.distinct().size,
                    units = ls.fold(BigDecimal.ZERO) { a, l -> a + l.quantity }, revenue = ls.fold(BigDecimal.ZERO) { a, l -> a + l.lineNet },
                    pendingConfirmationValue = ls.filter { !it.receiptsConfirmed }.fold(BigDecimal.ZERO) { a, l -> a + l.lineNet },
                )
            }.sortedByDescending { it.revenue }
            CorrespondentsReport("customer", null, rows, correspondentStats(lines, firstSale, from, to, totalRevenue, repInfo.size))
        } else {
            val rows = lines.groupBy { it.correspondentId!! }.map { (repId, ls) ->
                val rep = repInfo[repId]
                val revenue = ls.fold(BigDecimal.ZERO) { a, l -> a + l.lineNet }
                CorrespondentRepRow(
                    rep = rep?.name ?: "", repCode = rep?.code ?: "", region = rep?.region, status = rep?.status ?: "",
                    customers = ls.mapNotNull { it.customerId }.distinct().size, newCustomers = ls.mapNotNull { it.customerId }.distinct().count { broughtIn(it) },
                    transactions = ls.map { it.saleId }.distinct().size, units = ls.fold(BigDecimal.ZERO) { a, l -> a + l.quantity }, revenue = revenue,
                    sharePct = if (totalRevenue.signum() == 0) BigDecimal.ZERO else revenue.multiply(BigDecimal(100)).divide(totalRevenue, 1, RoundingMode.HALF_UP),
                    pendingConfirmationValue = ls.filter { !it.receiptsConfirmed }.fold(BigDecimal.ZERO) { a, l -> a + l.lineNet },
                )
            }.sortedByDescending { it.revenue }
            CorrespondentsReport("rep", rows, null, correspondentStats(lines, firstSale, from, to, totalRevenue, repInfo.size))
        }
    }

    private fun correspondentStats(lines: List<SalesLineRef>, firstSale: Map<UUID, LocalDate>, from: LocalDate, to: LocalDate, totalRevenue: BigDecimal, reps: Int): Map<String, Any?> {
        val customers = lines.mapNotNull { it.customerId }.distinct()
        val newCustomers = customers.count { firstSale[it]?.let { d -> !d.isBefore(from) && !d.isAfter(to) } == true }
        val top = lines.groupBy { it.correspondentId!! }.mapValues { e -> e.value.fold(BigDecimal.ZERO) { a, l -> a + l.lineNet } }.maxByOrNull { it.value }
        return mapOf(
            "reps" to reps, "customersServed" to customers.size, "newCustomers" to newCustomers, "revenue" to totalRevenue,
            "topRep" to top?.let { mapOf("revenue" to it.value) },
        )
    }
    fun commissions(
        beneficiaryType: String, from: LocalDate?, to: LocalDate?, status: String?, beneficiaryId: UUID?, paging: PageRequest,
    ): PageResult<CommsReportRow> = tx.execute {
        val bt = beneficiaryType.trim().uppercase()
        val rows = CommissionRunBeneficiariesTable
            .join(CommissionRunsTable, JoinType.INNER, CommissionRunBeneficiariesTable.runId, CommissionRunsTable.id)
            .selectAll()
            .where {
                var c = (CommissionRunsTable.tenantId eq support.tenantId()) and (CommissionRunsTable.status eq "APPROVED") and (CommissionRunsTable.beneficiaryType eq bt)
                from?.let { c = c and (CommissionRunsTable.periodMonth greaterEq it.withDayOfMonth(1)) }
                to?.let { c = c and (CommissionRunsTable.periodMonth lessEq it.withDayOfMonth(1)) }
                beneficiaryId?.let { c = c and (CommissionRunBeneficiariesTable.beneficiaryId eq it) }
                c
            }
            .orderBy(CommissionRunsTable.periodMonth to SortOrder.DESC)
            .toList()
        val runIds = rows.map { it[CommissionRunsTable.id].value }.distinct()
        val paid = paidByRunBeneficiary(runIds)
        val sales = salesByRunBeneficiary(runIds)
        val built = rows.mapNotNull { r ->
            val runId = r[CommissionRunsTable.id].value
            val bid = r[CommissionRunBeneficiariesTable.beneficiaryId]
            val earned = r[CommissionRunBeneficiariesTable.earned]
            val paidAmt = paid[runId to bid] ?: BigDecimal.ZERO
            val outstanding = earned - paidAmt
            val statusLabel = when {
                outstanding.signum() <= 0 -> "Settled"
                paidAmt.signum() > 0 -> "Part paid"
                else -> "Unpaid"
            }
            if (status != null && !statusLabel.equals(status.replace('_', ' '), ignoreCase = true)) return@mapNotNull null
            CommsReportRow(
                agent = r[CommissionRunBeneficiariesTable.beneficiaryName], code = r[CommissionRunBeneficiariesTable.beneficiaryCode], region = r[CommissionRunBeneficiariesTable.region],
                period = periodLabel(r[CommissionRunsTable.periodMonth]), sales = sales[runId to bid] ?: BigDecimal.ZERO,
                earned = earned, paid = paidAmt, outstanding = outstanding, status = statusLabel,
            )
        }
        val sorted = built.sortedByDescending { it.outstanding }
        val total = sorted.size.toLong()
        val pageItems = sorted.drop(paging.offset.toInt()).take(paging.size)
        val totalPages = if (paging.size == 0) 0 else ((total + paging.size - 1) / paging.size).toInt()
        PageResult(pageItems, paging.page, paging.size, total, totalPages, totalIsEstimate = false, summary = summary(built))
    }

    private fun summary(rows: List<CommsReportRow>): Map<String, Any?> = mapOf(
        "agents" to rows.map { it.code }.distinct().size,
        "earned" to rows.fold(BigDecimal.ZERO) { a, r -> a + r.earned },
        "paid" to rows.fold(BigDecimal.ZERO) { a, r -> a + r.paid },
        "outstanding" to rows.fold(BigDecimal.ZERO) { a, r -> a + r.outstanding },
        "unpaidAgents" to rows.filter { it.outstanding.signum() > 0 }.map { it.code }.distinct().size,
    )

    private fun paidByRunBeneficiary(runIds: List<UUID>): Map<Pair<UUID, UUID>, BigDecimal> {
        if (runIds.isEmpty()) return emptyMap()
        val amt = CommissionPaymentsTable.amount.sum()
        return CommissionPaymentsTable.select(CommissionPaymentsTable.runId, CommissionPaymentsTable.beneficiaryId, amt)
            .where { (CommissionPaymentsTable.runId inList runIds) and (CommissionPaymentsTable.status eq "PAID") }
            .groupBy(CommissionPaymentsTable.runId, CommissionPaymentsTable.beneficiaryId)
            .associate { (it[CommissionPaymentsTable.runId] to it[CommissionPaymentsTable.beneficiaryId]) to (it[amt] ?: BigDecimal.ZERO) }
    }

    private fun salesByRunBeneficiary(runIds: List<UUID>): Map<Pair<UUID, UUID>, BigDecimal> {
        if (runIds.isEmpty()) return emptyMap()
        val sv = CommissionRunLinesTable.salesValue.sum()
        return CommissionRunLinesTable.select(CommissionRunLinesTable.runId, CommissionRunLinesTable.beneficiaryId, sv)
            .where { CommissionRunLinesTable.runId inList runIds }
            .groupBy(CommissionRunLinesTable.runId, CommissionRunLinesTable.beneficiaryId)
            .associate { (it[CommissionRunLinesTable.runId] to it[CommissionRunLinesTable.beneficiaryId]) to (it[sv] ?: BigDecimal.ZERO) }
    }

    private fun periodLabel(month: LocalDate): String = "${month.month.getDisplayName(TextStyle.FULL, Locale.UK)} ${month.year}"

    private companion object {
        val NO_CUSTOMER: UUID = UUID(0, 0)
    }
}
