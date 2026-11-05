package com.zifiq.communication.service

import com.zifiq.common.money.Money
import com.zifiq.communication.model.CommissionPaymentsTable
import com.zifiq.communication.model.CommissionRunsTable
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/** Global search: an allocation run by its CA-n reference (docs/12 §9). */
@Component
class CommissionRunSearchProvider(private val support: CommunicationSupport) : SearchProvider {
    override val type = "commission-run"
    override val label = "Commission runs"
    override val listRoute = "/communication/runs"
    override fun canSearch() = support.can("communication.allocation.read")
    override fun search(q: String, limit: Int): List<SearchHit> {
        val t = q.trim().lowercase()
        return CommissionRunsTable.selectAll()
            .where { (CommissionRunsTable.tenantId eq support.tenantId()) and (CommissionRunsTable.ref.lowerCase() like "$t%") }
            .orderBy(CommissionRunsTable.periodMonth to SortOrder.DESC).limit(limit)
            .map { r ->
                val id = r[CommissionRunsTable.id].value
                SearchHit(
                    type = type, id = id, title = r[CommissionRunsTable.ref],
                    subtitle = listOf(periodLabel(r[CommissionRunsTable.periodMonth]), r[CommissionRunsTable.beneficiaryType].lowercase(), Money.naira(r[CommissionRunsTable.totalCommission]), r[CommissionRunsTable.status].lowercase()).joinToString(" · "),
                    ref = r[CommissionRunsTable.ref], route = "/communication/runs/$id",
                )
            }
    }
    private fun periodLabel(m: LocalDate) = "${m.month.getDisplayName(TextStyle.FULL, Locale.UK)} ${m.year}"
}

/** Global search: a commission payment by its CP-n reference (docs/12 §9). */
@Component
class CommissionPaymentSearchProvider(private val support: CommunicationSupport) : SearchProvider {
    override val type = "commission-payment"
    override val label = "Commission payments"
    override val listRoute = "/communication/payments"
    override fun canSearch() = support.can("communication.allocation.read")
    override fun search(q: String, limit: Int): List<SearchHit> {
        val t = q.trim().lowercase()
        return CommissionPaymentsTable.selectAll()
            .where { (CommissionPaymentsTable.tenantId eq support.tenantId()) and (CommissionPaymentsTable.ref.lowerCase() like "$t%") }
            .orderBy(CommissionPaymentsTable.paidAt to SortOrder.DESC).limit(limit)
            .map { r ->
                SearchHit(
                    type = type, id = r[CommissionPaymentsTable.id].value, title = r[CommissionPaymentsTable.ref],
                    subtitle = listOf(Money.naira(r[CommissionPaymentsTable.amount]), r[CommissionPaymentsTable.method].lowercase(), r[CommissionPaymentsTable.beneficiaryName], r[CommissionPaymentsTable.status].lowercase()).joinToString(" · "),
                    ref = r[CommissionPaymentsTable.ref], route = "/communication/payments?ref=${r[CommissionPaymentsTable.ref]}",
                )
            }
    }
}
