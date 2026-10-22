package com.zifiq.cashier.service

import com.zifiq.cashier.dto.PendingSupplyRow
import com.zifiq.cashier.model.CustomerWaybillsTable
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.allOf
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.ref
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/** Filters for the Pending Supply report (docs/09 §4.6). */
data class PendingSupplyFilter(
    val invoice: String? = null,
    val customerId: UUID? = null,
    val status: String? = null,
    val locationId: UUID? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/**
 * Pending Supply (docs/09 §4.6): sales with goods still owed — what was ordered, supplied and
 * outstanding, how many waybills have gone out, and when the last one did. Only completed sales with
 * a supply status (sales fully collected at the till never appear). Caller supplies the page; the
 * summary totals cover the whole filtered set.
 */
@Service
class PendingSupplyService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
) {
    fun report(filter: PendingSupplyFilter, paging: PageRequest): PageResult<PendingSupplyRow> = tx.execute {
        val statuses = when (filter.status?.uppercase()) {
            null -> listOf("NOT_SUPPLIED", "PARTIALLY_SUPPLIED")
            "FULLY_SUPPLIED" -> listOf("FULLY_SUPPLIED")
            else -> listOf(filter.status.uppercase())
        }
        val invoice = filter.invoice?.trim()?.uppercase()?.ifEmpty { null }
        val condition = listOfNotNull(
            SalesTable.tenantId eq support.tenantId(),
            SalesTable.status eq "COMPLETED",
            support.visibleLocations()?.let { SalesTable.locationId inList it },
            SalesTable.supplyStatus inList statuses,
            filter.locationId?.let { SalesTable.locationId eq it },
            filter.customerId?.let { SalesTable.customerId eq it },
            filter.from?.let { SalesTable.saleDate greaterEq it },
            filter.to?.let { SalesTable.saleDate lessEq it },
            invoice?.let { (SalesTable.invoiceNumber eq it) or (SalesTable.ref eq it) },
        ).allOf()!!
        val page = Paging.fetch(SalesTable.selectAll().where(condition)
            .orderBy(SalesTable.saleDate to SortOrder.ASC, SalesTable.id to SortOrder.ASC), paging) { it }
        val rows = page.items.map { sale ->
            val saleId = sale[SalesTable.id].value
            val qty = SaleLinesTable.quantity.sum()
            val supplied = SaleLinesTable.suppliedQty.sum()
            val lineCount = SaleLinesTable.id.count()
            val agg = SaleLinesTable.select(qty, supplied, lineCount).where { SaleLinesTable.saleId eq saleId }.single()
            val ordered = agg[qty] ?: BigDecimal.ZERO
            val done = agg[supplied] ?: BigDecimal.ZERO
            val rel = CustomerWaybillsTable.releaseDate.max()
            val wbCount = CustomerWaybillsTable.id.count()
            val wb = CustomerWaybillsTable.select(wbCount, rel).where { (CustomerWaybillsTable.saleId eq saleId) and (CustomerWaybillsTable.status neq "CANCELLED") }.single()
            PendingSupplyRow(
                sale = Ref.sale(saleId), saleRef = sale[SalesTable.ref] ?: "", invoice = ref(sale[SalesTable.invoiceId], Ref::invoice), invoiceNumber = sale[SalesTable.invoiceNumber],
                customer = ref(sale[SalesTable.customerId], Ref::customer), customerName = sale[SalesTable.customerName], date = sale[SalesTable.saleDate],
                lines = agg[lineCount].toInt(), ordered = ordered, supplied = done, outstanding = ordered - done, progressPct = pct(done, ordered),
                deliveries = wb[wbCount].toInt(), lastSupply = wb[rel], status = sale[SalesTable.supplyStatus],
            )
        }
        page.withItems(rows).copy(summary = stats(condition))
    }

    private fun stats(condition: org.jetbrains.exposed.sql.Op<Boolean>): Map<String, Any?> {
        val saleIds = SalesTable.select(SalesTable.id).where(condition).map { it[SalesTable.id].value }
        if (saleIds.isEmpty()) return statsMap(0, BigDecimal.ZERO, BigDecimal.ZERO)
        val qty = SaleLinesTable.quantity.sum()
        val supplied = SaleLinesTable.suppliedQty.sum()
        val agg = SaleLinesTable.select(qty, supplied).where { SaleLinesTable.saleId inList saleIds }.single()
        return statsMap(saleIds.size.toLong(), agg[qty] ?: BigDecimal.ZERO, agg[supplied] ?: BigDecimal.ZERO)
    }

    private fun statsMap(pending: Long, ordered: BigDecimal, supplied: BigDecimal): Map<String, Any?> =
        mapOf("invoicesPending" to pending, "unitsOrdered" to ordered, "unitsSupplied" to supplied,
            "unitsOutstanding" to ordered - supplied, "fulfilledPct" to pct(supplied, ordered))

    private fun pct(part: BigDecimal, whole: BigDecimal): Int =
        if (whole.signum() == 0) 100 else (part * BigDecimal(100) / whole).setScale(0, RoundingMode.HALF_UP).toInt()
}
