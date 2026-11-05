package com.zifiq.communication.service

import com.zifiq.communication.model.CommissionPaymentsTable
import com.zifiq.communication.model.CommissionRunBeneficiariesTable
import com.zifiq.communication.model.CommissionRunsTable
import com.zifiq.communication.spi.CommissionFilter
import com.zifiq.communication.spi.CommissionQueries
import com.zifiq.communication.spi.CommissionTotal
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/**
 * Commission allocated per approved-run period (docs/12 §5), for Reports / Dashboard / AI. Earned is
 * the run's per-beneficiary total; paid is the run's PAID payments. Read-only.
 */
@Service
class CommissionQueryService(
    private val support: CommunicationSupport,
    private val tx: TenantScopedTransaction,
) : CommissionQueries {
    override fun allocatedFor(filter: CommissionFilter): List<CommissionTotal> = tx.execute {
        val runs = CommissionRunsTable.selectAll().where {
            var c = (CommissionRunsTable.tenantId eq support.tenantId()) and (CommissionRunsTable.status eq "APPROVED")
            filter.beneficiaryType?.let { c = c and (CommissionRunsTable.beneficiaryType eq it) }
            filter.locationId?.let { c = c and (CommissionRunsTable.locationId eq it) }
            filter.from?.let { c = c and (CommissionRunsTable.periodMonth greaterEq it.withDayOfMonth(1)) }
            filter.to?.let { c = c and (CommissionRunsTable.periodMonth lessEq it.withDayOfMonth(1)) }
            c
        }.toList()
        if (runs.isEmpty()) return@execute emptyList()
        val runIds = runs.map { it[CommissionRunsTable.id].value }
        val paidExpr = CommissionPaymentsTable.amount.sum()
        val paidByRun = CommissionPaymentsTable.select(CommissionPaymentsTable.runId, paidExpr)
            .where { (CommissionPaymentsTable.runId inList runIds) and (CommissionPaymentsTable.status eq "PAID") }
            .groupBy(CommissionPaymentsTable.runId)
            .associate { it[CommissionPaymentsTable.runId] to (it[paidExpr] ?: BigDecimal.ZERO) }
        runs.map { r ->
            val id = r[CommissionRunsTable.id].value
            CommissionTotal(
                periodMonth = r[CommissionRunsTable.periodMonth], beneficiaryType = r[CommissionRunsTable.beneficiaryType],
                locationId = r[CommissionRunsTable.locationId], earned = r[CommissionRunsTable.totalCommission], paid = paidByRun[id] ?: BigDecimal.ZERO,
            )
        }
    }
}
