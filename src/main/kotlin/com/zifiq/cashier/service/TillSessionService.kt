package com.zifiq.cashier.service

import com.zifiq.account.spi.CashAccounts
import com.zifiq.cashier.dto.TillCloseRequest
import com.zifiq.cashier.dto.TillOpenRequest
import com.zifiq.cashier.dto.TillRunningFigures
import com.zifiq.cashier.dto.TillSessionView
import com.zifiq.cashier.model.SalePaymentsTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.cashier.model.TillSessionsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.inventory.service.StockLocations
import com.zifiq.platform.directory.Names
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import com.zifiq.common.search.allOf
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Filters for the till session list (docs/09 §4.1). */
data class TillSessionFilter(
    val locationId: UUID? = null,
    val cashierUserId: UUID? = null,
    val status: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val withVarianceOnly: Boolean = false,
)

/**
 * Till sessions (docs/09 §4.1): a cashier opens a till (a TILL cash account at a showroom) with a
 * float, sells through it, and closes it with a counted cash figure. Expected cash is the float plus
 * everything Account posted to that till during the session; the difference is a recorded variance
 * that Accounts resolves — nothing is posted automatically. One open session per till and per cashier.
 */
@Service
class TillSessionService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val cashAccounts: CashAccounts,
    private val stockLocations: StockLocations,
    private val notify: Notify,
) {
    fun open(request: TillOpenRequest): TillSessionView = tx.execute {
        val till = cashAccounts.get(request.cashAccountId!!) ?: throw ApiErrors.invalidTill("No such till")
        if (till.kind != "TILL" || !till.active) throw ApiErrors.invalidTill("${till.code} is not an active till")
        val locationId = till.locationId ?: throw ApiErrors.invalidTill("${till.code} is not at a showroom")
        support.assertAccess(locationId)
        val location = stockLocations.requireTrading(locationId)
        if (location.isArea || location.type != "SHOWROOM") throw ApiErrors.invalidTill("Tills sell at showrooms; ${till.code} is at ${location.label}")
        openSession(TillSessionsTable.cashAccountId eq till.id)?.let { open ->
            throw ApiErrors.tillAlreadyOpen(Names.users(listOf(open[TillSessionsTable.cashierUserId]))[open[TillSessionsTable.cashierUserId]] ?: "another cashier", open[TillSessionsTable.openedAt])
        }
        openSession(TillSessionsTable.cashierUserId eq support.userId())?.let { throw ApiErrors.cashierSessionOpen(it[TillSessionsTable.tillCode]) }
        val float = Money.round(request.openingFloat!!)
        val id = TillSessionsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[cashAccountId] = till.id
            it[tillCode] = till.code.take(30)
            it[TillSessionsTable.locationId] = locationId
            it[cashierUserId] = support.userId()
            it[status] = "OPEN"
            it[openedAt] = Instant.now()
            it[openingFloat] = float
            it[forceClosed] = false
        }.value
        support.record("cashier.till.opened", "till-session", id, till.code, "${till.code} opened with a float of ${Money.naira(float)}",
            mapOf("openingFloat" to float), locationId)
        view(row(id))
    }

    /** The caller's open session, with what it has taken so far. */
    fun current(): TillSessionView = tx.execute {
        view(openSession(TillSessionsTable.cashierUserId eq support.userId()) ?: throw ApiErrors.noOpenTillSession())
    }

    fun get(id: UUID): TillSessionView = tx.execute { view(visibleRow(id)) }

    fun close(id: UUID, request: TillCloseRequest): TillSessionView = tx.execute {
        val session = visibleRow(id, forUpdate = true)
        if (session[TillSessionsTable.status] != "OPEN") throw ApiErrors.tillSessionClosed()
        val own = session[TillSessionsTable.cashierUserId] == support.userId()
        if (!own && !support.can(SUPERVISE)) throw ApiErrors.accessDenied()
        val open = SalesTable.select(SalesTable.id).where { (SalesTable.tillSessionId eq id) and (SalesTable.status eq "OPEN") }.map { it[SalesTable.id].value.toString() }
        if (open.isNotEmpty()) throw ApiErrors.openSalesExist(open)
        val now = Instant.now()
        val expected = expectedCash(session, now)
        val counted = Money.round(request.countedCash!!)
        val variance = counted - expected
        TillSessionsTable.update({ TillSessionsTable.id eq id }) {
            it[status] = "CLOSED"
            it[closedAt] = now
            it[closedByUserId] = support.userId()
            it[countedCash] = counted
            it[expectedCash] = expected
            it[TillSessionsTable.variance] = variance
            it[closeNote] = request.note?.trim()?.ifEmpty { null }
            it[forceClosed] = !own
        }
        val locationId = session[TillSessionsTable.locationId].value
        val till = session[TillSessionsTable.tillCode]
        support.record("cashier.till.closed", "till-session", id, till,
            "$till closed: counted ${Money.naira(counted)}, expected ${Money.naira(expected)}" + if (own) "" else " (force-closed by a supervisor)",
            mapOf("countedCash" to counted, "expectedCash" to expected, "variance" to variance, "forceClosed" to !own), locationId)
        if (variance.signum() != 0) {
            val eventId = support.record("cashier.till.variance", "till-session", id, till, "$till closed with a variance of ${Money.naira(variance)}",
                mapOf("variance" to variance), locationId)
            notify.holdersOf(
                permission = "accounting.receipts.confirm", locationId = locationId, category = NotificationCategory.ALERT,
                title = "Till variance — $till", body = "Counted ${Money.naira(counted)} against ${Money.naira(expected)} expected: ${Money.naira(variance)}.",
                linkType = "till-session", linkRef = id.toString(), sourceEventId = eventId, exclude = support.userId(),
            )
        }
        view(row(id))
    }

    fun list(filter: TillSessionFilter, paging: PageRequest): PageResult<TillSessionView> = tx.execute {
        val condition = listOfNotNull(
            TillSessionsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { TillSessionsTable.locationId inList it },
            if (support.ownOnly()) TillSessionsTable.cashierUserId eq support.userId() else filter.cashierUserId?.let { TillSessionsTable.cashierUserId eq it },
            filter.locationId?.let { TillSessionsTable.locationId eq it },
            filter.status?.let { TillSessionsTable.status eq it.uppercase() },
            filter.from?.let { TillSessionsTable.openedAt greaterEq it.atStartOfDay(support.zone()).toInstant() },
            filter.to?.let { TillSessionsTable.openedAt less it.plusDays(1).atStartOfDay(support.zone()).toInstant() },
            if (filter.withVarianceOnly) TillSessionsTable.variance neq BigDecimal.ZERO else null,
        ).allOf()!!
        Paging.fetch(TillSessionsTable.selectAll().where(condition).orderBy(TillSessionsTable.openedAt to SortOrder.DESC, TillSessionsTable.id to SortOrder.DESC), paging) { it }
            .let { page -> page.withItems(page.items.map(::view)) }
    }

    /** The caller's open session, locked, for a sale to run against (409 NO_OPEN_TILL_SESSION when none). */
    fun requireOwnOpen(): ResultRow =
        openSession(TillSessionsTable.cashierUserId eq support.userId(), forUpdate = false) ?: throw ApiErrors.noOpenTillSession()

    fun lockOpen(sessionId: UUID): ResultRow {
        val session = TillSessionsTable.selectAll().where { TillSessionsTable.id eq sessionId }.forUpdate().single()
        if (session[TillSessionsTable.status] != "OPEN") throw ApiErrors.tillSessionClosed()
        return session
    }

    private fun openSession(by: Op<Boolean>, forUpdate: Boolean = false): ResultRow? =
        TillSessionsTable.selectAll().where { (TillSessionsTable.tenantId eq support.tenantId()) and (TillSessionsTable.status eq "OPEN") and by }
            .let { if (forUpdate) it.forUpdate() else it }
            .firstOrNull()

    private fun row(id: UUID): ResultRow = TillSessionsTable.selectAll().where { TillSessionsTable.id eq id }.single()

    private fun visibleRow(id: UUID, forUpdate: Boolean = false): ResultRow {
        val row = TillSessionsTable.selectAll().where { (TillSessionsTable.id eq id) and (TillSessionsTable.tenantId eq support.tenantId()) }
            .let { if (forUpdate) it.forUpdate() else it }
            .firstOrNull() ?: throw ApiErrors.tillSessionNotFound()
        support.assertVisible(row[TillSessionsTable.locationId].value, row[TillSessionsTable.cashierUserId]) { ApiErrors.tillSessionNotFound() }
        return row
    }

    /** Float + everything Account posted to the till since the session opened (cash sales, refunds, lodgements). */
    private fun expectedCash(session: ResultRow, until: Instant): BigDecimal =
        Money.round(session[TillSessionsTable.openingFloat] + cashAccounts.netCashBetween(session[TillSessionsTable.cashAccountId], session[TillSessionsTable.openedAt], until).net)

    private fun view(session: ResultRow): TillSessionView {
        val id = session[TillSessionsTable.id].value
        val open = session[TillSessionsTable.status] == "OPEN"
        return TillSessionView(
            id = id, till = Ref.cashAccount(session[TillSessionsTable.cashAccountId]), location = Ref.location(session[TillSessionsTable.locationId].value),
            cashier = UserRef(session[TillSessionsTable.cashierUserId]), status = session[TillSessionsTable.status],
            openedAt = session[TillSessionsTable.openedAt], openingFloat = session[TillSessionsTable.openingFloat],
            closedAt = session[TillSessionsTable.closedAt], closedBy = UserRef.of(session[TillSessionsTable.closedByUserId]),
            countedCash = session[TillSessionsTable.countedCash], expectedCash = session[TillSessionsTable.expectedCash],
            variance = session[TillSessionsTable.variance], closeNote = session[TillSessionsTable.closeNote], forceClosed = session[TillSessionsTable.forceClosed],
            running = if (open) running(id, session) else null,
        )
    }

    /** What the session took, by method, in one grouped read over its completed sales. */
    private fun running(id: UUID, session: ResultRow): TillRunningFigures {
        val amount = SalePaymentsTable.amount.sum()
        val byMethod = SalePaymentsTable.join(SalesTable, org.jetbrains.exposed.sql.JoinType.INNER, SalePaymentsTable.saleId, SalesTable.id)
            .select(SalePaymentsTable.method, amount)
            .where { (SalesTable.tillSessionId eq id) and (SalesTable.status eq "COMPLETED") }
            .groupBy(SalePaymentsTable.method)
            .associate { it[SalePaymentsTable.method] to (it[amount] ?: BigDecimal.ZERO) }
        val onAccount = SalesTable.onAccount.sum()
        val count = SalesTable.id.count()
        val totals = SalesTable.select(count, onAccount).where { (SalesTable.tillSessionId eq id) and (SalesTable.status eq "COMPLETED") }.single()
        fun m(method: String) = Money.round(byMethod[method] ?: BigDecimal.ZERO)
        return TillRunningFigures(
            salesCount = totals[count].toInt(), cashTaken = m("CASH"), cardTaken = m("POS"), transferTaken = m("FUND_TRANSFER"),
            walletTaken = m("WALLET"), depositTaken = m("DEPOSIT"), onAccount = Money.round(totals[onAccount] ?: BigDecimal.ZERO),
            expectedCashNow = expectedCash(session, Instant.now()),
        )
    }

    companion object {
        const val SUPERVISE = "cashier.till.supervise"
    }
}
