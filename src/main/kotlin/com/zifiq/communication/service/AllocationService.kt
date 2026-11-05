package com.zifiq.communication.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.communication.dto.ApproveRunRequest
import com.zifiq.communication.dto.BeneficiaryRef
import com.zifiq.communication.dto.CancelRunRequest
import com.zifiq.communication.dto.CreateRunRequest
import com.zifiq.communication.dto.IneligibleInfo
import com.zifiq.communication.dto.PendingInfo
import com.zifiq.communication.dto.RunBeneficiaryView
import com.zifiq.communication.dto.RunDetail
import com.zifiq.communication.dto.RunHeader
import com.zifiq.communication.dto.RunLineView
import com.zifiq.communication.dto.RunRow
import com.zifiq.communication.dto.TopEarner
import com.zifiq.communication.model.CommissionAllocatedLinesTable
import com.zifiq.communication.model.CommissionRatesTable
import com.zifiq.communication.model.CommissionRunBeneficiariesTable
import com.zifiq.communication.model.CommissionRunLinesTable
import com.zifiq.communication.model.CommissionRunsTable
import com.zifiq.customers.spi.CorrespondentRef
import com.zifiq.customers.spi.Correspondents
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesLineRef
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.showroom.spi.ReturnFilter
import com.zifiq.showroom.spi.ShowroomQueries
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Commission allocation runs (docs/12 §4.2): a monthly run turns qualifying, not-yet-allocated sale
 * lines into per-beneficiary commission at the rate in force on each sale date, nets off dealer
 * returns, carries a negative balance forward, and locks on approval (approver ≠ runner). The
 * once-only guard (`commission_allocated_lines`) is written at approval, so a draft claims nothing.
 */
@Service
class AllocationService(
    private val support: CommunicationSupport,
    private val tx: TenantScopedTransaction,
    private val sequences: DocumentSequenceService,
    private val sales: SalesQueries,
    private val showroom: ShowroomQueries,
    private val customers: CustomerDirectory,
    private val correspondents: Correspondents,
) {
    private data class Group(
        val beneficiaryId: UUID, val code: String, val name: String, val region: String?, val stockItemId: UUID, val itemCode: String, val itemName: String,
        var qtySold: BigDecimal = BigDecimal.ZERO, var salesValue: BigDecimal = BigDecimal.ZERO, var salesCommission: BigDecimal = BigDecimal.ZERO,
        var qtyReturned: BigDecimal = BigDecimal.ZERO, var rateId: UUID? = null, var rateType: String? = null, var rate: BigDecimal? = null,
        var topQty: BigDecimal = BigDecimal.ZERO, var sawActive: Boolean = false, var sawInactive: Boolean = false, var notAssigned: Boolean = false,
        var lateFrom: LocalDate? = null,
    )
    private class Calc {
        val groups = LinkedHashMap<Pair<UUID, UUID>, Group>()
        val consumedSales = mutableSetOf<UUID>()
        val consumedReturns = mutableSetOf<UUID>()
        var pending = 0
    }

    fun createRun(request: CreateRunRequest): RunDetail = tx.execute {
        val bt = request.beneficiaryType.uppercase()
        val month = runCatching { YearMonth.parse(request.periodMonth!!.trim()) }.getOrNull() ?: throw ApiErrors.validationFailed(mapOf("periodMonth" to "use YYYY-MM"))
        val start = month.atDay(1)
        val end = month.atEndOfMonth()
        if (!end.isBefore(support.today())) throw ApiErrors.periodNotClosed()
        val location = request.locationId
        if (location != null && !support.settings().boolean(CommunicationSettings.ALLOW_LOCATION_RUNS)) throw ApiErrors.validationFailed(mapOf("locationId" to "location runs are not enabled"))
        location?.let { support.assertAccess(it) }
        if (liveRun(bt, start, location) != null) throw ApiErrors.runExists()
        if (hasEarlierDraft(bt, start)) throw ApiErrors.earlierRunNotApproved()

        val runRef = sequences.next("CA")
        val runId = CommissionRunsTable.insertAndGetId {
            it[tenantId] = support.tenantId(); it[ref] = runRef; it[beneficiaryType] = bt; it[periodMonth] = start
            it[locationId] = location; it[status] = "DRAFT"; it[totalCommission] = BigDecimal.ZERO; it[beneficiaryCount] = 0
            it[eligibleLines] = 0; it[ineligibleLines] = 0; it[pendingConfirmationLines] = 0; it[calculatedAt] = Instant.now()
            it[runByUserId] = support.userId(); it[createdAt] = Instant.now()
        }.value
        recalcInto(runId, bt, start, end, location)
        detail(runId, "beneficiary", null)
    }

    fun recalculate(runId: UUID): RunDetail = tx.execute {
        val run = runForUpdate(runId)
        if (run[CommissionRunsTable.status] != "DRAFT") throw ApiErrors.runNotDraft()
        recalcInto(runId, run[CommissionRunsTable.beneficiaryType], run[CommissionRunsTable.periodMonth], run[CommissionRunsTable.periodMonth].plusMonths(1).minusDays(1), run[CommissionRunsTable.locationId])
        detail(runId, "beneficiary", null)
    }

    fun approve(runId: UUID, request: ApproveRunRequest): RunDetail = tx.execute {
        val run = runForUpdate(runId)
        if (run[CommissionRunsTable.status] != "DRAFT") throw ApiErrors.runNotDraft()
        if (run[CommissionRunsTable.runByUserId].value == support.userId()) throw ApiErrors.approverMustDiffer()
        val calc = recalcInto(runId, run[CommissionRunsTable.beneficiaryType], run[CommissionRunsTable.periodMonth], run[CommissionRunsTable.periodMonth].plusMonths(1).minusDays(1), run[CommissionRunsTable.locationId])
        val total = CommissionRunsTable.select(CommissionRunsTable.totalCommission).where { CommissionRunsTable.id eq runId }.single()[CommissionRunsTable.totalCommission]
        if (request.expectedTotal != null && request.expectedTotal.compareTo(total) != 0) throw ApiErrors.runChanged(total)
        val bt = run[CommissionRunsTable.beneficiaryType]
        calc.consumedSales.forEach { lineId -> allocate(bt, "SALE", lineId, runId) }
        calc.consumedReturns.forEach { lineId -> allocate(bt, "RETURN", lineId, runId) }
        CommissionRunsTable.update({ CommissionRunsTable.id eq runId }) {
            it[status] = "APPROVED"; it[approvedByUserId] = support.userId(); it[approvedAt] = Instant.now()
        }
        support.record("communication.run.approved", "commission_run", runId, run[CommissionRunsTable.ref], "Commission run ${run[CommissionRunsTable.ref]} approved", mapOf("total" to total))
        detail(runId, "beneficiary", null)
    }

    fun cancel(runId: UUID, request: CancelRunRequest): RunDetail = tx.execute {
        val run = runForUpdate(runId)
        when (run[CommissionRunsTable.status]) {
            "DRAFT" -> {}
            "APPROVED" -> CommissionAllocatedLinesTable.deleteWhere { (CommissionAllocatedLinesTable.tenantId eq support.tenantId()) and (CommissionAllocatedLinesTable.runId eq runId) }
            else -> throw ApiErrors.runNotDraft()
        }
        CommissionRunsTable.update({ CommissionRunsTable.id eq runId }) { it[status] = "CANCELLED"; it[cancelledReason] = request.reason!!.trim() }
        support.record("communication.run.cancelled", "commission_run", runId, run[CommissionRunsTable.ref], "Commission run ${run[CommissionRunsTable.ref]} cancelled", emptyMap())
        detail(runId, "beneficiary", null)
    }

    fun list(beneficiaryType: String?, status: String?, paging: PageRequest): PageResult<RunRow> = tx.execute {
        var cond = CommissionRunsTable.tenantId eq support.tenantId()
        beneficiaryType?.trim()?.uppercase()?.let { cond = cond and (CommissionRunsTable.beneficiaryType eq it) }
        status?.trim()?.uppercase()?.let { cond = cond and (CommissionRunsTable.status eq it) }
        val page = Paging.fetch(CommissionRunsTable.selectAll().where(cond).orderBy(CommissionRunsTable.periodMonth to SortOrder.DESC, CommissionRunsTable.createdAt to SortOrder.DESC), paging) { it }
        page.withItems(page.items.map { toRunRow(it) })
    }

    fun detail(runId: UUID, view: String, beneficiaryId: UUID?): RunDetail = tx.execute {
        val run = CommissionRunsTable.selectAll().where { (CommissionRunsTable.id eq runId) and (CommissionRunsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("id" to "run not found"))
        val beneficiaries = CommissionRunBeneficiariesTable.selectAll().where { CommissionRunBeneficiariesTable.runId eq runId }.toList()
        val lines = CommissionRunLinesTable.selectAll().where { CommissionRunLinesTable.runId eq runId }.orderBy(CommissionRunLinesTable.commission to SortOrder.DESC).toList()
        val total = beneficiaries.fold(BigDecimal.ZERO) { a, b -> a + b[CommissionRunBeneficiariesTable.earned] }
        val bt = run[CommissionRunsTable.beneficiaryType]
        val header = RunHeader(
            id = runId, ref = run[CommissionRunsTable.ref], period = periodLabel(run[CommissionRunsTable.periodMonth]), beneficiaryType = bt,
            status = statusLabel(run), beneficiaries = beneficiaries.size, units = lines.filter { it[CommissionRunLinesTable.eligible] }.fold(BigDecimal.ZERO) { a, l -> a + l[CommissionRunLinesTable.netQty] },
            commissionPayable = total,
            topEarner = beneficiaries.maxByOrNull { it[CommissionRunBeneficiariesTable.earned] }?.takeIf { it[CommissionRunBeneficiariesTable.earned].signum() > 0 }
                ?.let { TopEarner(it[CommissionRunBeneficiariesTable.beneficiaryName], it[CommissionRunBeneficiariesTable.earned]) },
            ineligible = IneligibleInfo(run[CommissionRunsTable.ineligibleLines], lines.filter { !it[CommissionRunLinesTable.eligible] }.map { it[CommissionRunLinesTable.itemName] }.distinct()),
            pendingConfirmation = PendingInfo(run[CommissionRunsTable.pendingConfirmationLines], BigDecimal.ZERO),
        )
        if (view == "line") {
            RunDetail(header, null, lines.filter { beneficiaryId == null || it[CommissionRunLinesTable.beneficiaryId] == beneficiaryId }.map { toLineView(it) })
        } else {
            RunDetail(header, beneficiaries.filter { beneficiaryId == null || it[CommissionRunBeneficiariesTable.beneficiaryId] == beneficiaryId }.map { toBeneficiaryView(it, total) }, null)
        }
    }

    // ---- engine ----

    private fun recalcInto(runId: UUID, bt: String, start: LocalDate, end: LocalDate, location: UUID?): Calc {
        CommissionRunLinesTable.deleteWhere { CommissionRunLinesTable.runId eq runId }
        CommissionRunBeneficiariesTable.deleteWhere { CommissionRunBeneficiariesTable.runId eq runId }
        val calc = calculate(bt, start, end, location)
        var eligible = 0
        var ineligible = 0
        val carriedIn = previousCarryOut(bt, start, location)
        val perBeneficiaryGross = HashMap<UUID, BigDecimal>()
        calc.groups.values.forEach { g ->
            val unitCommission = if (g.qtySold.signum() > 0) g.salesCommission.divide(g.qtySold, 4, RoundingMode.HALF_UP) else BigDecimal.ZERO
            val netQty = g.qtySold - g.qtyReturned
            val eligibleGroup = g.sawActive && !g.notAssigned
            val commission = if (!eligibleGroup) BigDecimal.ZERO else (g.salesCommission - g.qtyReturned.multiply(unitCommission)).setScale(2, RoundingMode.HALF_UP)
            if (eligibleGroup) eligible++ else ineligible++
            val reason = when {
                eligibleGroup -> null
                g.notAssigned -> "CORRESPONDENT_NOT_ASSIGNED"
                g.sawInactive -> "RATE_INACTIVE"
                else -> "NO_RATE"
            }
            CommissionRunLinesTable.insert {
                it[tenantId] = support.tenantId(); it[CommissionRunLinesTable.runId] = runId; it[beneficiaryId] = g.beneficiaryId
                it[beneficiaryCode] = g.code; it[beneficiaryName] = g.name; it[region] = g.region; it[stockItemId] = g.stockItemId
                it[itemCode] = g.itemCode; it[itemName] = g.itemName; it[qtySold] = g.qtySold; it[qtyReturned] = g.qtyReturned; it[CommissionRunLinesTable.netQty] = netQty
                it[salesValue] = g.salesValue; it[rateId] = g.rateId; it[rateType] = g.rateType; it[rate] = g.rate
                it[CommissionRunLinesTable.unitCommission] = if (eligibleGroup) unitCommission else BigDecimal.ZERO; it[CommissionRunLinesTable.commission] = commission
                it[CommissionRunLinesTable.eligible] = eligibleGroup; it[ineligibleReason] = reason; it[lateFromPeriod] = g.lateFrom
            }
            if (eligibleGroup) perBeneficiaryGross.merge(g.beneficiaryId, commission, BigDecimal::add)
        }
        // Per-beneficiary totals (include those carried in even with no eligible line this run).
        val beneficiaryInfo = calc.groups.values.associate { it.beneficiaryId to Triple(it.code, it.name, it.region) }
        (perBeneficiaryGross.keys + carriedIn.keys).toSet().forEach { bid ->
            val gross = perBeneficiaryGross[bid] ?: BigDecimal.ZERO
            val cin = carriedIn[bid] ?: BigDecimal.ZERO
            val net = gross + cin
            val earned = net.max(BigDecimal.ZERO)
            val carriedOut = net.min(BigDecimal.ZERO)
            val info = beneficiaryInfo[bid]
            CommissionRunBeneficiariesTable.insert {
                it[tenantId] = support.tenantId(); it[CommissionRunBeneficiariesTable.runId] = runId; it[beneficiaryId] = bid
                it[beneficiaryCode] = info?.first ?: ""; it[beneficiaryName] = info?.second ?: ""; it[region] = info?.third
                it[grossCommission] = gross; it[CommissionRunBeneficiariesTable.carriedIn] = cin; it[CommissionRunBeneficiariesTable.earned] = earned; it[CommissionRunBeneficiariesTable.carriedOut] = carriedOut
            }
        }
        val beneficiaryCount = (perBeneficiaryGross.keys + carriedIn.keys).size
        val total = CommissionRunBeneficiariesTable.selectAll().where { CommissionRunBeneficiariesTable.runId eq runId }
            .fold(BigDecimal.ZERO) { a, b -> a + b[CommissionRunBeneficiariesTable.earned] }
        CommissionRunsTable.update({ CommissionRunsTable.id eq runId }) {
            it[totalCommission] = total; it[CommissionRunsTable.beneficiaryCount] = beneficiaryCount; it[eligibleLines] = eligible
            it[ineligibleLines] = ineligible; it[pendingConfirmationLines] = calc.pending; it[calculatedAt] = Instant.now()
        }
        return calc
    }

    private fun calculate(bt: String, start: LocalDate, end: LocalDate, location: UUID?): Calc {
        val calc = Calc()
        val allocatedSales = allocatedLineIds(bt, "SALE")
        val lines = sales.linesFor(SalesLineFilter(completedTo = end, locationId = location))
        val rateCache = HashMap<UUID, List<ResultRow>>()
        val benCache = HashMap<UUID, BeneficiaryInfo?>()
        val requireInvoicePaid = support.settings().boolean(CommunicationSettings.REQUIRE_INVOICE_PAID)
        lines.forEach { line ->
            if (line.saleLineId in allocatedSales) return@forEach
            val ben = beneficiary(bt, line, benCache) ?: return@forEach
            val qualifying = line.receiptsConfirmed && (!requireInvoicePaid || line.invoicePaid)
            if (!qualifying) {
                if (!line.saleDate.isBefore(start) && !line.saleDate.isAfter(end)) calc.pending++
                return@forEach
            }
            calc.consumedSales += line.saleLineId
            val g = calc.groups.getOrPut(ben.id to line.stockItemId) {
                Group(ben.id, ben.code, ben.name, ben.region, line.stockItemId, line.itemCode, line.itemName)
            }
            g.qtySold += line.quantity
            g.salesValue += line.lineNet
            if (ben.notAssigned) { g.notAssigned = true; return@forEach }
            val rate = inForceRate(bt, line.stockItemId, line.saleDate, rateCache)
            when {
                rate == null -> {}
                rate.status != "ACTIVE" -> g.sawInactive = true
                else -> {
                    g.sawActive = true
                    val lineCommission = if (rate.rateType == "PER_UNIT") line.quantity.multiply(rate.rate)
                    else line.lineNet.multiply(rate.rate).divide(BigDecimal(100), 4, RoundingMode.HALF_UP)
                    g.salesCommission += lineCommission
                    if (line.quantity > g.topQty) { g.topQty = line.quantity; g.rateId = rate.id; g.rateType = rate.rateType; g.rate = rate.rate }
                }
            }
            if (line.saleDate.isBefore(start)) {
                val m = line.saleDate.withDayOfMonth(1)
                if (g.lateFrom == null || m.isBefore(g.lateFrom)) g.lateFrom = m
            }
        }
        if (bt == "DEALER") applyReturns(calc, start, end, location, allocatedLineIds(bt, "RETURN"))
        return calc
    }

    private fun applyReturns(calc: Calc, start: LocalDate, end: LocalDate, location: UUID?, allocatedReturns: Set<UUID>) {
        showroom.returns(ReturnFilter(locationIds = location?.let { setOf(it) }, from = null, to = end, status = "POSTED"))
            .filter { it.customerType == "AUTHORISED_DEALER" && it.lineId !in allocatedReturns }
            .forEach { r ->
                val key = r.customerId to r.stockItemId
                val g = calc.groups[key] ?: return@forEach   // a return only nets a dealer+product with sales in the window
                g.qtyReturned += r.quantity
                calc.consumedReturns += r.lineId
            }
    }

    private data class BeneficiaryInfo(val id: UUID, val code: String, val name: String, val region: String?, val notAssigned: Boolean)

    private fun beneficiary(bt: String, line: SalesLineRef, cache: HashMap<UUID, BeneficiaryInfo?>): BeneficiaryInfo? {
        return if (bt == "DEALER") {
            val cid = line.customerId
            if (cid == null || line.customerType != "AUTHORISED_DEALER") return null
            cache.getOrPut(cid) { customers.get(cid).let { BeneficiaryInfo(it.id, it.code, it.name, null, false) } }
        } else {
            val id = line.correspondentId ?: return null
            cache.getOrPut(id) {
                val c: CorrespondentRef = correspondents.get(id)
                BeneficiaryInfo(c.id, c.code, c.name, c.region, c.status != "ASSIGNED")
            }
        }
    }

    private data class InForce(val id: UUID, val rateType: String, val rate: BigDecimal, val status: String)

    private fun inForceRate(bt: String, stockItemId: UUID, on: LocalDate, cache: HashMap<UUID, List<ResultRow>>): InForce? {
        val rows = cache.getOrPut(stockItemId) {
            CommissionRatesTable.selectAll()
                .where { (CommissionRatesTable.tenantId eq support.tenantId()) and (CommissionRatesTable.stockItemId eq stockItemId) and (CommissionRatesTable.beneficiaryType eq bt) }
                .orderBy(CommissionRatesTable.effectiveFrom to SortOrder.DESC).toList()
        }
        val r = rows.firstOrNull { !it[CommissionRatesTable.effectiveFrom].isAfter(on) } ?: return null
        return InForce(r[CommissionRatesTable.id].value, r[CommissionRatesTable.rateType], r[CommissionRatesTable.rate], r[CommissionRatesTable.status])
    }

    private fun previousCarryOut(bt: String, start: LocalDate, location: UUID?): Map<UUID, BigDecimal> {
        val prev = CommissionRunsTable.selectAll()
            .where {
                var c = (CommissionRunsTable.tenantId eq support.tenantId()) and (CommissionRunsTable.beneficiaryType eq bt) and (CommissionRunsTable.status eq "APPROVED") and (CommissionRunsTable.periodMonth less start)
                if (location != null) c = c and (CommissionRunsTable.locationId eq location)
                c
            }
            .orderBy(CommissionRunsTable.periodMonth to SortOrder.DESC).limit(1).firstOrNull() ?: return emptyMap()
        return CommissionRunBeneficiariesTable.selectAll().where { CommissionRunBeneficiariesTable.runId eq prev[CommissionRunsTable.id].value }
            .filter { it[CommissionRunBeneficiariesTable.carriedOut].signum() < 0 }
            .associate { it[CommissionRunBeneficiariesTable.beneficiaryId] to it[CommissionRunBeneficiariesTable.carriedOut] }
    }

    private fun allocatedLineIds(bt: String, source: String): Set<UUID> =
        CommissionAllocatedLinesTable.select(CommissionAllocatedLinesTable.saleLineId)
            .where { (CommissionAllocatedLinesTable.tenantId eq support.tenantId()) and (CommissionAllocatedLinesTable.beneficiaryType eq bt) and (CommissionAllocatedLinesTable.sourceKind eq source) }
            .map { it[CommissionAllocatedLinesTable.saleLineId] }.toSet()

    private fun allocate(bt: String, source: String, lineId: UUID, runId: UUID) {
        CommissionAllocatedLinesTable.insert {
            it[tenantId] = support.tenantId(); it[beneficiaryType] = bt; it[sourceKind] = source
            it[saleLineId] = lineId; it[CommissionAllocatedLinesTable.runId] = runId
        }
    }

    // ---- helpers ----

    private fun liveRun(bt: String, start: LocalDate, location: UUID?): ResultRow? =
        CommissionRunsTable.selectAll().where {
            var c = (CommissionRunsTable.tenantId eq support.tenantId()) and (CommissionRunsTable.beneficiaryType eq bt) and (CommissionRunsTable.periodMonth eq start) and (CommissionRunsTable.status neq "CANCELLED")
            c = if (location == null) c and CommissionRunsTable.locationId.isNull() else c and (CommissionRunsTable.locationId eq location)
            c
        }.firstOrNull()

    private fun hasEarlierDraft(bt: String, start: LocalDate): Boolean =
        CommissionRunsTable.selectAll().where {
            (CommissionRunsTable.tenantId eq support.tenantId()) and (CommissionRunsTable.beneficiaryType eq bt) and (CommissionRunsTable.status eq "DRAFT") and (CommissionRunsTable.periodMonth less start)
        }.any()

    private fun runForUpdate(runId: UUID): ResultRow =
        CommissionRunsTable.selectAll().where { (CommissionRunsTable.id eq runId) and (CommissionRunsTable.tenantId eq support.tenantId()) }.forUpdate().firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("id" to "run not found"))

    private fun statusLabel(run: ResultRow): String = when (run[CommissionRunsTable.status]) {
        "DRAFT" -> "Draft"; "APPROVED" -> "Approved"; else -> "Cancelled"
    }

    private fun periodLabel(month: LocalDate): String = "${month.month.getDisplayName(TextStyle.FULL, Locale.UK)} ${month.year}"

    private fun toRunRow(r: ResultRow): RunRow {
        val total = r[CommissionRunsTable.totalCommission]
        return RunRow(
            id = r[CommissionRunsTable.id].value, ref = r[CommissionRunsTable.ref], period = periodLabel(r[CommissionRunsTable.periodMonth]),
            beneficiaryType = r[CommissionRunsTable.beneficiaryType], beneficiaries = r[CommissionRunsTable.beneficiaryCount], total = total,
            paid = BigDecimal.ZERO, outstanding = if (r[CommissionRunsTable.status] == "APPROVED") total else BigDecimal.ZERO, status = statusLabel(r),
            runOn = r[CommissionRunsTable.createdAt], runBy = UserRef.of(r[CommissionRunsTable.runByUserId].value), approvedBy = UserRef.of(r[CommissionRunsTable.approvedByUserId]?.value),
        )
    }

    private fun toBeneficiaryView(b: ResultRow, total: BigDecimal): RunBeneficiaryView {
        val earned = b[CommissionRunBeneficiariesTable.earned]
        return RunBeneficiaryView(
            beneficiary = BeneficiaryRef(b[CommissionRunBeneficiariesTable.beneficiaryId], b[CommissionRunBeneficiariesTable.beneficiaryCode], b[CommissionRunBeneficiariesTable.beneficiaryName], ""),
            region = b[CommissionRunBeneficiariesTable.region],
            products = CommissionRunLinesTable.selectAll().where { (CommissionRunLinesTable.runId eq b[CommissionRunBeneficiariesTable.runId]) and (CommissionRunLinesTable.beneficiaryId eq b[CommissionRunBeneficiariesTable.beneficiaryId]) and (CommissionRunLinesTable.eligible eq true) }.count().toInt(),
            units = BigDecimal.ZERO, commission = earned, sharePct = if (total.signum() == 0) BigDecimal.ZERO else earned.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP),
            carriedIn = b[CommissionRunBeneficiariesTable.carriedIn], carriedOut = b[CommissionRunBeneficiariesTable.carriedOut], paid = BigDecimal.ZERO, outstanding = earned,
        )
    }

    private fun toLineView(l: ResultRow) = RunLineView(
        beneficiary = BeneficiaryRef(l[CommissionRunLinesTable.beneficiaryId], l[CommissionRunLinesTable.beneficiaryCode], l[CommissionRunLinesTable.beneficiaryName], ""),
        itemCode = l[CommissionRunLinesTable.itemCode], product = l[CommissionRunLinesTable.itemName], qtySold = l[CommissionRunLinesTable.qtySold],
        qtyReturned = l[CommissionRunLinesTable.qtyReturned], netQty = l[CommissionRunLinesTable.netQty], rateType = l[CommissionRunLinesTable.rateType],
        unitRate = l[CommissionRunLinesTable.rate], commission = l[CommissionRunLinesTable.commission],
        eligible = when {
            l[CommissionRunLinesTable.eligible] -> "Yes"
            l[CommissionRunLinesTable.ineligibleReason] == "RATE_INACTIVE" -> "Inactive"
            l[CommissionRunLinesTable.ineligibleReason] == "CORRESPONDENT_NOT_ASSIGNED" -> "Rep not assigned"
            else -> "No rate"
        },
        lateFromPeriod = l[CommissionRunLinesTable.lateFromPeriod],
    )
}
