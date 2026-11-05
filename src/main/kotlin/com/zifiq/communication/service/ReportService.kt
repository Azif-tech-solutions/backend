package com.zifiq.communication.service

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.communication.dto.CommsReportRow
import com.zifiq.communication.model.CommissionPaymentsTable
import com.zifiq.communication.model.CommissionRunBeneficiariesTable
import com.zifiq.communication.model.CommissionRunLinesTable
import com.zifiq.communication.model.CommissionRunsTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
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
) {
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
}
