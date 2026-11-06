package com.zifiq.reports.service

import com.zifiq.account.spi.AccountReports
import com.zifiq.account.spi.ExpenseLine
import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.search.RawExpression
import com.zifiq.common.sql.Aggregates
import com.zifiq.communication.spi.CommissionFilter
import com.zifiq.communication.spi.CommissionQueries
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import com.zifiq.invoicing.spi.InvoiceQueries
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.reports.dto.SciLine
import com.zifiq.reports.dto.SciPeriod
import com.zifiq.reports.dto.SciResult
import com.zifiq.reports.dto.SciSection
import com.zifiq.reports.spi.FinancialSnapshot
import com.zifiq.reports.spi.FinancialSummary
import com.zifiq.showroom.spi.ReturnFilter
import com.zifiq.showroom.spi.ShowroomQueries
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * The Statement of Comprehensive Income (docs/13 §4.2). Computed on demand from the owning modules'
 * own records — sales (cashier), returns (showroom), credit notes (invoicing), COGS and closing stock
 * (inventory ledger), operating expenses (account) and commissions (communication) — so the SCI holds
 * no data of its own. Company-wide or for one location, with a comparison period and cost masking.
 *
 * Cost of sales uses the inventory ledger's own unit costs for sale/void entries in the period; closing
 * stock values the net ledger quantity as at the period end at each item's current landing cost (a
 * documented approximation). Without `factory.costing.read` the cost-of-sales and closing-stock figures
 * are zeroed and gross/net profit are withheld (null), per doc 03 §14.
 */
@Service
class SciService(
    private val support: ReportsSupport,
    private val tx: TenantScopedTransaction,
    private val sales: SalesQueries,
    private val returns: ShowroomQueries,
    private val creditNotes: InvoiceQueries,
    private val expenses: AccountReports,
    private val commissions: CommissionQueries,
) : FinancialSummary {

    fun sci(scope: String, locationId: UUID?, from: LocalDate, to: LocalDate, compare: String): SciResult = tx.execute {
        val sc = scope.trim().uppercase().ifEmpty { "COMPANY" }
        if (sc != "COMPANY" && sc != "LOCATION") throw ApiErrors.validationFailed(mapOf("scope" to "Must be COMPANY or LOCATION"))
        val cmp = compare.trim().uppercase().ifEmpty { "PRIOR_PERIOD" }
        if (cmp !in setOf("PRIOR_PERIOD", "SAME_PERIOD_LAST_YEAR", "NONE")) throw ApiErrors.validationFailed(mapOf("compare" to "Must be PRIOR_PERIOD, SAME_PERIOD_LAST_YEAR or NONE"))
        if (from.isAfter(to)) throw ApiErrors.validationFailed(mapOf("from" to "Must not be after 'to'"))
        if (ChronoUnit.DAYS.between(from, to) + 1 > 366) throw ApiErrors.validationFailed(mapOf("to" to "The period cannot exceed 366 days"))
        val loc = if (sc == "LOCATION") (locationId ?: throw ApiErrors.validationFailed(mapOf("locationId" to "Required for a per-location SCI"))).also(::requireLocation) else null

        val locTypes = locationTypes()
        val cur = figures(from, to, loc, locTypes)
        val cmpPeriod: SciPeriod?
        val prev: Figures?
        val cmpLabel: String?
        when (cmp) {
            "NONE" -> { cmpPeriod = null; prev = null; cmpLabel = null }
            "SAME_PERIOD_LAST_YEAR" -> {
                val pf = from.minusYears(1); val pt = to.minusYears(1)
                cmpPeriod = SciPeriod(pf, pt, "SAME_PERIOD_LAST_YEAR"); prev = figures(pf, pt, loc, locTypes); cmpLabel = "Prior year"
            }
            else -> {
                val days = ChronoUnit.DAYS.between(from, to) + 1
                val pt = from.minusDays(1); val pf = pt.minusDays(days - 1)
                cmpPeriod = SciPeriod(pf, pt, "PRIOR_PERIOD"); prev = figures(pf, pt, loc, locTypes); cmpLabel = "Prior period"
            }
        }
        build(sc, from, to, cmpPeriod, cmpLabel, cur, prev, support.costVisible())
    }

    override fun snapshot(from: LocalDate, to: LocalDate, locationId: UUID?): FinancialSnapshot = tx.execute {
        val f = figures(from, to, locationId, locationTypes())
        val visible = support.costVisible()
        FinancialSnapshot(
            from = from, to = to, revenue = Money.round(f.totalRevenue),
            costOfSales = if (visible) Money.round(f.cogs) else null,
            grossProfit = if (visible) Money.round(f.grossProfit) else null,
            operatingExpenses = Money.round(f.totalExpenses),
            netProfit = if (visible) Money.round(f.netProfit) else null,
            closingStockValue = if (visible) Money.round(f.closingStock) else null,
            costVisible = visible,
        )
    }

    // ---- computation ----

    private data class Figures(
        val showroom: BigDecimal,
        val dealer: BigDecimal,
        val factory: BigDecimal,
        val returnsTotal: BigDecimal,
        val creditNotes: BigDecimal,
        val cogs: BigDecimal,
        val expenses: List<ExpenseLine>,
        val commissions: BigDecimal,
        val closingStock: BigDecimal,
    ) {
        val totalRevenue: BigDecimal get() = showroom + dealer + factory - returnsTotal - creditNotes
        val grossProfit: BigDecimal get() = totalRevenue - cogs
        val totalExpenses: BigDecimal get() = expenses.sumOf { it.amount } + commissions
        val netProfit: BigDecimal get() = grossProfit - totalExpenses
    }

    private fun figures(from: LocalDate, to: LocalDate, locationId: UUID?, locTypes: Map<UUID, String>): Figures {
        var showroom = BigDecimal.ZERO; var dealer = BigDecimal.ZERO; var factory = BigDecimal.ZERO
        sales.linesFor(SalesLineFilter(completedTo = to, locationId = locationId)).forEach { line ->
            if (line.saleDate.isBefore(from)) return@forEach
            when {
                line.customerType == DEALER -> dealer += line.lineNet
                line.locationId != null && locTypes[line.locationId] == "FACTORY" -> factory += line.lineNet
                else -> showroom += line.lineNet
            }
        }
        val locSet = locationId?.let { setOf(it) }
        val returnsTotal = returns.returns(ReturnFilter(locationIds = locSet, from = from, to = to, status = "POSTED")).sumOf { it.lineValue }
        val creditTotal = creditNotes.creditNoteLines(locationId, from, to).sumOf { it.lineNet }
        val expenseLines = expenses.operatingExpenses(from, to, locSet)
        val commissionTotal = commissions.allocatedFor(CommissionFilter(from = from, to = to, locationId = locationId)).sumOf { it.earned }
        return Figures(showroom, dealer, factory, returnsTotal, creditTotal, cogs(from, to, locationId), expenseLines, commissionTotal, closingStock(to, locationId))
    }

    private fun cogs(from: LocalDate, to: LocalDate, locationId: UUID?): BigDecimal {
        val product = RawExpression<BigDecimal>({
            +"("; +StockLedgerEntriesTable.qtyOut; +" - "; +StockLedgerEntriesTable.qtyIn; +") * coalesce("; +StockLedgerEntriesTable.unitCost; +", 0)"
        })
        val total = Aggregates.sum(product)
        val row = StockLedgerEntriesTable.select(total).where {
            var c = (StockLedgerEntriesTable.tenantId eq support.tenantId()) and
                (StockLedgerEntriesTable.sourceType inList COGS_SOURCE_TYPES) and
                (StockLedgerEntriesTable.entryDate greaterEq from) and (StockLedgerEntriesTable.entryDate lessEq to)
            if (locationId != null) c = c and (StockLedgerEntriesTable.locationId eq locationId)
            c
        }.single()
        return Aggregates.money(row, total)
    }

    private fun closingStock(to: LocalDate, locationId: UUID?): BigDecimal {
        val product = RawExpression<BigDecimal>({
            +"("; +StockLedgerEntriesTable.qtyIn; +" - "; +StockLedgerEntriesTable.qtyOut; +") * coalesce("; +StockItemsTable.landingCost; +", 0)"
        })
        val total = Aggregates.sum(product)
        val row = (StockLedgerEntriesTable innerJoin StockItemsTable).select(total).where {
            var c = (StockLedgerEntriesTable.tenantId eq support.tenantId()) and (StockLedgerEntriesTable.entryDate lessEq to)
            if (locationId != null) c = c and (StockLedgerEntriesTable.locationId eq locationId)
            c
        }.single()
        return Aggregates.money(row, total)
    }

    private fun locationTypes(): Map<UUID, String> = LocationsTable.select(LocationsTable.id, LocationsTable.type)
        .where { LocationsTable.tenantId eq support.tenantId() }
        .associate { it[LocationsTable.id].value to it[LocationsTable.type] }

    private fun requireLocation(id: UUID) {
        val ok = LocationsTable.selectAll().where { (LocationsTable.tenantId eq support.tenantId()) and (LocationsTable.id eq id) }.limit(1).any()
        if (!ok) throw ApiErrors.validationFailed(mapOf("locationId" to "Unknown location"))
    }

    // ---- assembly ----

    private fun build(scope: String, from: LocalDate, to: LocalDate, cmpPeriod: SciPeriod?, cmpLabel: String?, cur: Figures, prev: Figures?, costVisible: Boolean): SciResult {
        val withCmp = cmpPeriod != null
        val columns = buildList {
            add("This period")
            if (withCmp) add(cmpLabel!!)
        }

        val revenue = SciSection(
            title = "Revenue",
            lines = listOf(
                line("Showroom sales", cur.showroom, prev?.showroom, withCmp),
                line("Dealer / distributor sales", cur.dealer, prev?.dealer, withCmp),
                line("Factory direct sales", cur.factory, prev?.factory, withCmp),
                line("Less: sales returns", cur.returnsTotal.negate(), prev?.returnsTotal?.negate(), withCmp),
                line("Less: credit notes", cur.creditNotes.negate(), prev?.creditNotes?.negate(), withCmp),
            ),
            subtotal = line("Total revenue", cur.totalRevenue, prev?.totalRevenue, withCmp),
        )

        val costNote = if (costVisible) null else "Hidden — requires cost visibility"
        val costOfSales = SciSection(
            title = "Cost of sales",
            cost = true,
            lines = listOf(
                line("Cost of goods sold", if (costVisible) cur.cogs else BigDecimal.ZERO, if (costVisible) prev?.cogs else prev?.let { BigDecimal.ZERO }, withCmp, costNote),
            ),
            subtotal = line("Gross profit", if (costVisible) cur.grossProfit else null, if (costVisible) prev?.grossProfit else null, withCmp, costNote),
        )

        val expenseLines = buildExpenseLines(cur, prev, withCmp) +
            line("Commissions", cur.commissions, prev?.commissions, withCmp)
        val operating = SciSection(
            title = "Operating expenses",
            lines = expenseLines,
            subtotal = line("Total operating expenses", cur.totalExpenses, prev?.totalExpenses, withCmp),
        )

        val memo = SciSection(
            title = "Memorandum",
            cost = true,
            lines = listOf(
                line("Stock value at period end", if (costVisible) cur.closingStock else BigDecimal.ZERO, if (costVisible) prev?.closingStock else prev?.let { BigDecimal.ZERO }, withCmp, costNote),
            ),
        )

        val result = line("Net profit for the period", if (costVisible) cur.netProfit else null, if (costVisible) prev?.netProfit else null, withCmp, costNote)

        val stats = mapOf(
            "totalRevenue" to Money.round(cur.totalRevenue),
            "grossProfit" to if (costVisible) Money.round(cur.grossProfit) else null,
            "grossMarginPct" to if (costVisible) marginPct(cur.grossProfit, cur.totalRevenue) else null,
            "operatingExpenses" to Money.round(cur.totalExpenses),
            "netProfit" to if (costVisible) Money.round(cur.netProfit) else null,
            "netMarginPct" to if (costVisible) marginPct(cur.netProfit, cur.totalRevenue) else null,
            "closingStockValue" to if (costVisible) Money.round(cur.closingStock) else null,
            "costVisible" to costVisible,
        )

        return SciResult(
            scope = scope,
            period = SciPeriod(from, to, "Accrual basis"),
            comparison = cmpPeriod,
            columns = columns,
            sections = listOf(revenue, costOfSales, operating, memo),
            result = result,
            stats = stats,
            basisOfPreparation = BASIS,
            costVisible = costVisible,
        )
    }

    private fun marginPct(part: BigDecimal, whole: BigDecimal): BigDecimal? =
        if (whole.signum() == 0) null else part.divide(whole, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)

    /** Operating-expense lines, unioned over both periods' categories and ordered by current amount. */
    private fun buildExpenseLines(cur: Figures, prev: Figures?, withCmp: Boolean): List<SciLine> {
        val curBy = cur.expenses.associateBy { it.categoryId }
        val prevBy = prev?.expenses?.associateBy { it.categoryId } ?: emptyMap()
        val names = HashMap<UUID, String>()
        (cur.expenses + (prev?.expenses ?: emptyList())).forEach { names.putIfAbsent(it.categoryId, it.categoryName) }
        return names.keys
            .map { id -> Triple(id, curBy[id]?.amount ?: BigDecimal.ZERO, prevBy[id]?.amount ?: BigDecimal.ZERO) }
            .sortedByDescending { it.second }
            .map { (id, c, p) -> line(names[id] ?: "Uncategorised", c, if (withCmp) p else null, withCmp) }
    }

    private fun line(label: String, cur: BigDecimal?, prev: BigDecimal?, withCmp: Boolean, note: String? = null): SciLine {
        val c = cur?.let { Money.round(it) }
        val p = prev?.let { Money.round(it) }
        val values = if (withCmp) listOf(c, p) else listOf(c)
        val pct = if (withCmp && c != null && p != null && p.signum() != 0)
            (c - p).divide(p.abs(), 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
        else null
        return SciLine(label, values, pct, note)
    }

    companion object {
        private const val DEALER = "AUTHORISED_DEALER"
        private val COGS_SOURCE_TYPES = listOf("POS_SALE", "CUSTOMER_WAYBILL", "SALE_VOID", "CUSTOMER_WAYBILL_CANCEL")
        private const val BASIS =
            "Prepared on the accrual basis from each module's own records. Revenue is completed sales net of " +
                "sales returns and credit notes, split by selling-location type and dealer status. Cost of sales is " +
                "the inventory ledger's unit cost for sale and void movements in the period. Closing stock values the " +
                "net ledger quantity as at the period end at each item's current landing cost. Deferred to later " +
                "slices: the materials/labour/overhead cost split, special-production revenue, by-product and other " +
                "income, and the factory-versus-showroom stock split."
    }
}
