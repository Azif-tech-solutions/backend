package com.zifiq.account.service

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Expression
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.common.sql.Aggregates
import com.zifiq.platform.directory.Names
import com.zifiq.account.spi.Channel
import com.zifiq.common.money.Money
import com.zifiq.account.dto.LocationVatHistoryRow
import com.zifiq.account.dto.LocationVatList
import com.zifiq.account.dto.LocationVatRequest
import com.zifiq.account.dto.LocationVatRow
import com.zifiq.account.dto.ScheduledVat
import com.zifiq.account.dto.TenderableGeneralReport
import com.zifiq.account.dto.TenderableGeneralRow
import com.zifiq.account.dto.TenderablePayableRow
import com.zifiq.account.dto.TenderableWalletFundRow
import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.LocationVatSettingsTable
import com.zifiq.account.model.PaymentReceiptsTable
import com.zifiq.account.model.WalletFundingsTable
import com.zifiq.account.spi.VatRates
import com.zifiq.account.spi.VatSetting
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.search.allOf
import com.zifiq.customers.spi.CustomerQueries
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerEntriesFilter
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Tenderable (docs/07 §4.11): what each cashier took, by channel, built from `payment_receipts`
 * (and Customers' ledger for dealers' return credits). Pending and declined receipts never count
 * towards a cashier's total — they're shown separately, so nobody is credited with money that
 * hasn't arrived.
 */
@Service
class TenderableService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val customerQueries: CustomerQueries,
) {
    private data class Takings(val ftf: BigDecimal, val cash: BigDecimal, val pos: BigDecimal, val wallet: BigDecimal, val deposit: BigDecimal,
                               val refund: BigDecimal, val pending: BigDecimal, val declined: BigDecimal) {
        val total: BigDecimal get() = ftf + cash + pos + wallet + deposit - refund
    }

    /** One row per cashier per day, newest day first, a page at a time; every total is an SQL aggregate. */
    fun payable(locationId: UUID?, cashierUserId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageRequest): PageResult<TenderablePayableRow> = tx.execute {
        val t = TakingsColumns()
        val day = day()
        val query = PaymentReceiptsTable.join(AppUsersTable, JoinType.LEFT, PaymentReceiptsTable.cashierUserId, AppUsersTable.id)
            .select(listOf(day, PaymentReceiptsTable.cashierUserId, AppUsersTable.displayName) + t.all)
            .where(receipts(locationId, cashierUserId, from, to))
            .groupBy(day, PaymentReceiptsTable.cashierUserId, AppUsersTable.displayName)
            .orderBy(day to SortOrder.DESC, AppUsersTable.displayName to SortOrder.ASC_NULLS_LAST, PaymentReceiptsTable.cashierUserId to SortOrder.ASC)
        Paging.fetch(query, paging) { row ->
            val k = t.read(row)
            TenderablePayableRow(LocalDate.parse(row[day]), UserRef(row[PaymentReceiptsTable.cashierUserId]),
                k.ftf, k.cash, k.pos, k.wallet, k.deposit, k.refund, Money.round(k.total), k.pending, k.declined)
        }
    }

    /** Per cashier for the period, with grand totals: two aggregate queries. */
    fun generalReport(locationId: UUID?, from: LocalDate?, to: LocalDate?): TenderableGeneralReport = tx.execute {
        val t = TakingsColumns()
        val days = RawExpression<Long>({ +"count(distinct "; +day(); +")" })
        val count = Aggregates.count()
        val condition = receipts(locationId, null, from, to)
        val perCashier = PaymentReceiptsTable.join(AppUsersTable, JoinType.LEFT, PaymentReceiptsTable.cashierUserId, AppUsersTable.id)
            .select(listOf(PaymentReceiptsTable.cashierUserId, AppUsersTable.displayName, days) + t.all)
            .where(condition)
            .groupBy(PaymentReceiptsTable.cashierUserId, AppUsersTable.displayName)
            .orderBy(AppUsersTable.displayName to SortOrder.ASC_NULLS_FIRST, PaymentReceiptsTable.cashierUserId to SortOrder.ASC)
            .map { row ->
                val k = t.read(row)
                TenderableGeneralRow(UserRef(row[PaymentReceiptsTable.cashierUserId]), Aggregates.long(row, days).toInt(),
                    k.ftf, k.cash, k.pos, k.wallet, k.deposit, k.refund, Money.round(k.total))
            }
        val all = PaymentReceiptsTable.select(listOf(count, days) + t.all).where(condition).single()
        val totals = if (Aggregates.long(all, count) == 0L) null else t.read(all).let { k ->
            TenderableGeneralRow(null, Aggregates.long(all, days).toInt(), k.ftf, k.cash, k.pos, k.wallet, k.deposit, k.refund, Money.round(k.total))
        }
        TenderableGeneralReport(mapOf("from" to from, "to" to to), perCashier, totals)
    }

    /**
     * Wallet Funds: dealers' fundings plus the return credits Customers posted for them, newest first.
     * The two sources are each read newest first up to the end of the requested page and merged, and
     * their counts and totals are aggregates — neither source is ever loaded whole.
     */
    fun walletFunds(customerId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageRequest): PageResult<TenderableWalletFundRow> = tx.execute {
        val zone = support.moduleSettings().zone()
        val upTo = (paging.offset + paging.size).toInt()
        val fundingCondition = listOfNotNull(
            WalletFundingsTable.tenantId eq support.tenantId(),
            WalletFundingsTable.balanceType eq "WALLET",
            customerId?.let { WalletFundingsTable.customerId eq it },
            support.visibleLocations()?.let { WalletFundingsTable.locationId inList it },
            from?.let { WalletFundingsTable.createdAt greaterEq it.atStartOfDay(zone).toInstant() },
            to?.let { WalletFundingsTable.createdAt less it.plusDays(1).atStartOfDay(zone).toInstant() },
        ).allOf()!!
        val fundings = WalletFundingsTable.selectAll().where(fundingCondition)
            .orderBy(WalletFundingsTable.createdAt to SortOrder.DESC, WalletFundingsTable.id to SortOrder.DESC).limit(upTo).toList()
        val receipts = PaymentReceiptsTable.selectAll().where { PaymentReceiptsTable.id inList fundings.map { it[WalletFundingsTable.paymentReceiptId] }.ifEmpty { listOf(UUID(0, 0)) } }
            .associateBy { it[PaymentReceiptsTable.id].value }
        val banks = BankAccountsTable.selectAll().where { BankAccountsTable.tenantId eq support.tenantId() }.associate { it[BankAccountsTable.id].value to "${it[BankAccountsTable.code]} ${it[BankAccountsTable.bankName]}" }
        val fromFundings = fundings.map { row ->
            val receipt = receipts[row[WalletFundingsTable.paymentReceiptId]]
            TenderableWalletFundRow(
                ref = row[WalletFundingsTable.ref], date = row[WalletFundingsTable.createdAt], customer = Ref.customer(row[WalletFundingsTable.customerId]),
                dealer = "${row[WalletFundingsTable.customerCode]} ${row[WalletFundingsTable.customerName]}",
                method = Channel.labelOf(row[WalletFundingsTable.channel]),
                bank = receipt?.get(PaymentReceiptsTable.receivingBankAccountId)?.let(banks::get), amount = row[WalletFundingsTable.amount],
                confirmedBy = UserRef.of(receipt?.get(PaymentReceiptsTable.reviewedByUserId) ?: row[WalletFundingsTable.createdByUserId].takeIf { row[WalletFundingsTable.channel] == "CASH" }),
                status = when (row[WalletFundingsTable.status]) { "CONFIRMED" -> "Confirmed"; "AWAITING_CONFIRMATION" -> "Awaiting confirmation"; "DECLINED" -> "Declined"; else -> "Voided" },
            )
        }
        val creditFilter = LedgerEntriesFilter(customerIds = customerId?.let(::setOf), entryTypes = setOf(LedgerEntryType.RETURN_CREDIT), from = from, to = to,
            customerTypes = setOf(CustomerType.AUTHORISED_DEALER), newestFirst = true, limit = upTo)
        val fromReturns = customerQueries.ledgerEntries(creditFilter).map {
            TenderableWalletFundRow(it.reference, it.createdAt, Ref.customer(it.customerId), "${it.customerCode} ${it.customerName}", "Return credit", null, it.amount, null, "Confirmed")
        }
        val items = (fromFundings + fromReturns).sortedByDescending { it.date }.drop(paging.offset.toInt()).take(paging.size)

        val fundingCount = Aggregates.count()
        val confirmed = Aggregates.sum(WalletFundingsTable.amount, WalletFundingsTable.status eq "CONFIRMED")
        val awaiting = Aggregates.sum(WalletFundingsTable.amount, WalletFundingsTable.status eq "AWAITING_CONFIRMATION")
        val fundingTotals = WalletFundingsTable.select(fundingCount, confirmed, awaiting).where(fundingCondition).single()
        val credits = customerQueries.ledgerTotal(creditFilter.copy(limit = null))
        val totalItems = Aggregates.long(fundingTotals, fundingCount) + credits.count
        val confirmedTotal = Aggregates.money(fundingTotals, confirmed) + credits.amount
        val awaitingTotal = Aggregates.money(fundingTotals, awaiting)
        PageResult(items, paging.page, paging.size, totalItems, ((totalItems + paging.size - 1) / paging.size).toInt(), false,
            summary = mapOf("total" to Money.round(confirmedTotal + awaitingTotal), "confirmed" to Money.round(confirmedTotal), "awaiting" to awaitingTotal))
    }

    /** The receipts a takings report covers: issued, in the caller's scope, in the period (tenant time zone). */
    private fun receipts(locationId: UUID?, cashierUserId: UUID?, from: LocalDate?, to: LocalDate?): Op<Boolean> {
        val zone = support.moduleSettings().zone()
        return listOfNotNull(
            PaymentReceiptsTable.tenantId eq support.tenantId(),
            PaymentReceiptsTable.status eq "ISSUED",
            support.visibleLocations()?.let { PaymentReceiptsTable.locationId inList it },
            support.resolveLocation(locationId)?.let { PaymentReceiptsTable.locationId eq it },
            cashierUserId?.let { PaymentReceiptsTable.cashierUserId eq it },
            from?.let { PaymentReceiptsTable.receivedAt greaterEq it.atStartOfDay(zone).toInstant() },
            to?.let { PaymentReceiptsTable.receivedAt less it.plusDays(1).atStartOfDay(zone).toInstant() },
        ).allOf()!!
    }

    /**
     * The receipt's day in the tenant's time zone, as ISO text (a raw date comes back from JDBC as
     * java.sql.Date). The zone is a literal, not a parameter, so GROUP BY sees the same expression
     * as the select list; a ZoneId's id holds only letters, digits and `/_+-`.
     */
    private fun day(): Expression<String> {
        val zone = support.moduleSettings().zone().id
        require(zone.all { it.isLetterOrDigit() || it in "/_+-" }) { "Unexpected zone id" }
        return RawExpression({ +"(timezone('$zone', "; +PaymentReceiptsTable.receivedAt; +"))::date::text" })
    }

    /** A cashier's takings by channel as aggregate columns. Pending and declined receipts are shown apart and never counted. */
    private class TakingsColumns {
        private val counted = PaymentReceiptsTable.confirmationStatus inList listOf("NOT_REQUIRED", "APPROVED")
        private fun receipt(vararg channels: String): Op<Boolean> =
            (PaymentReceiptsTable.kind eq "RECEIPT") and counted and (PaymentReceiptsTable.channel inList channels.toList())

        private val ftf = Aggregates.sum(PaymentReceiptsTable.amount, receipt("FUND_TRANSFER", "CHEQUE"))
        private val cash = Aggregates.sum(PaymentReceiptsTable.amount, receipt("CASH"))
        private val pos = Aggregates.sum(PaymentReceiptsTable.amount, receipt("POS"))
        private val wallet = Aggregates.sum(PaymentReceiptsTable.amount, receipt("WALLET"))
        private val deposit = Aggregates.sum(PaymentReceiptsTable.amount, receipt("DEPOSIT"))
        private val refund = Aggregates.sum(PaymentReceiptsTable.amount, PaymentReceiptsTable.kind eq "REFUND")
        private val pending = Aggregates.sum(PaymentReceiptsTable.amount, PaymentReceiptsTable.confirmationStatus eq "PENDING")
        private val declined = Aggregates.sum(PaymentReceiptsTable.amount, PaymentReceiptsTable.confirmationStatus eq "DECLINED")
        val all: List<Expression<*>> = listOf(ftf, cash, pos, wallet, deposit, refund, pending, declined)

        fun read(row: ResultRow) = Takings(
            Aggregates.money(row, ftf), Aggregates.money(row, cash), Aggregates.money(row, pos), Aggregates.money(row, wallet),
            Aggregates.money(row, deposit), Aggregates.money(row, refund), Aggregates.money(row, pending), Aggregates.money(row, declined),
        )
    }
}

/**
 * VAT Control per location (docs/07 §4.17): effective-dated rate, enablement and registration
 * number per showroom/factory. `locations.vat_*` stays the cache of what is in force today,
 * refreshed when a change takes effect (at once when dated today, else by [applyDue]).
 */
@Service
class LocationVatService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val database: Database,
    private val notify: Notify,
    private val hierarchy: LocationHierarchy = LocationHierarchy(),
) : VatRates {
    fun list(): LocationVatList = tx.execute {
        val today = support.today()
        val locations = LocationsTable.selectAll().where(
            listOfNotNull(
                LocationsTable.tenantId eq support.tenantId(),
                LocationsTable.type inList listOf("SHOWROOM", "FACTORY"),
                support.visibleLocations()?.let { LocationsTable.id inList it },
            ).allOf()!!,
        ).orderBy(LocationsTable.type to SortOrder.ASC, LocationsTable.code to SortOrder.ASC).toList()
        val settings = LocationVatSettingsTable.selectAll()
            .where { (LocationVatSettingsTable.tenantId eq support.tenantId()) and (LocationVatSettingsTable.locationId inList locations.map { it[LocationsTable.id].value }.ifEmpty { listOf(UUID(0, 0)) }) }
            .toList().groupBy { it[LocationVatSettingsTable.locationId] }
        val rows = locations.map { location ->
            val id = location[LocationsTable.id].value
            val all = settings[id].orEmpty()
            val current = all.filter { !it[LocationVatSettingsTable.effectiveFrom].isAfter(today) }.maxByOrNull { it[LocationVatSettingsTable.effectiveFrom] }
            val next = all.filter { it[LocationVatSettingsTable.effectiveFrom].isAfter(today) }.minByOrNull { it[LocationVatSettingsTable.effectiveFrom] }
            LocationVatRow(
                location = Ref.location(id),
                registration = current?.get(LocationVatSettingsTable.vatRegistrationNumber) ?: location[LocationsTable.vatRegistrationNumber],
                rate = current?.get(LocationVatSettingsTable.vatRate) ?: location[LocationsTable.vatRate],
                enabled = current?.get(LocationVatSettingsTable.vatEnabled) ?: location[LocationsTable.vatEnabled],
                effectiveFrom = current?.get(LocationVatSettingsTable.effectiveFrom), updatedBy = UserRef.of(current?.get(LocationVatSettingsTable.updatedByUserId)),
                scheduled = next?.let { ScheduledVat(it[LocationVatSettingsTable.vatRate], it[LocationVatSettingsTable.vatEnabled], it[LocationVatSettingsTable.effectiveFrom]) },
            )
        }
        LocationVatList(rows.count { !it.enabled }, rows)
    }

    /** A new setting from [LocationVatRequest.effectiveFrom] (today or later); history is kept. */
    fun set(locationId: UUID, request: LocationVatRequest): LocationVatList = tx.execute {
        val location = location(locationId)
        val effectiveFrom = request.effectiveFrom!!
        if (effectiveFrom.isBefore(support.today())) throw ApiErrors.validationFailed(mapOf("effectiveFrom" to "must be today or later; past periods keep the VAT they were charged at"))
        val exists = !LocationVatSettingsTable.selectAll()
            .where { (LocationVatSettingsTable.tenantId eq support.tenantId()) and (LocationVatSettingsTable.locationId eq locationId) and (LocationVatSettingsTable.effectiveFrom eq effectiveFrom) }.empty()
        if (exists) throw ApiErrors.vatSettingExistsForDate(effectiveFrom)
        val id = LocationVatSettingsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[LocationVatSettingsTable.locationId] = locationId
            it[vatEnabled] = request.vatEnabled!!
            it[vatRate] = request.vatRate!!.setScale(2, java.math.RoundingMode.HALF_UP)
            it[vatRegistrationNumber] = request.vatRegistrationNumber?.trim()?.ifEmpty { null }
            it[LocationVatSettingsTable.effectiveFrom] = effectiveFrom
            it[note] = request.note?.trim()?.ifEmpty { null }
            it[updatedByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        if (!effectiveFrom.isAfter(support.today())) applyCurrent(locationId, support.today())
        val summary = "VAT at ${location[LocationsTable.name]}: ${if (request.vatEnabled!!) "${request.vatRate!!.stripTrailingZeros().toPlainString()}%" else "collection disabled"} from $effectiveFrom"
        val eventId = support.record("account.vat.location-updated", "location_vat_setting", id, location[LocationsTable.code], summary,
            mapOf("enabled" to request.vatEnabled, "rate" to request.vatRate, "effectiveFrom" to effectiveFrom.toString()), locationId)
        notify.holdersOf(permission = "accounting.read", locationId = locationId, category = NotificationCategory.INFO, title = "VAT setting changed — ${location[LocationsTable.code]}",
            body = summary, linkType = "location-vat", linkRef = locationId.toString(), sourceEventId = eventId, exclude = support.userId())
        list()
    }

    fun history(locationId: UUID): List<LocationVatHistoryRow> = tx.execute {
        location(locationId)
        val today = support.today()
        val rows = LocationVatSettingsTable.selectAll()
            .where { (LocationVatSettingsTable.tenantId eq support.tenantId()) and (LocationVatSettingsTable.locationId eq locationId) }
            .orderBy(LocationVatSettingsTable.effectiveFrom to SortOrder.DESC).toList()
        val inForce = rows.firstOrNull { !it[LocationVatSettingsTable.effectiveFrom].isAfter(today) }?.get(LocationVatSettingsTable.id)?.value
        rows.map {
            LocationVatHistoryRow(
                id = it[LocationVatSettingsTable.id].value, enabled = it[LocationVatSettingsTable.vatEnabled], rate = it[LocationVatSettingsTable.vatRate],
                registration = it[LocationVatSettingsTable.vatRegistrationNumber], effectiveFrom = it[LocationVatSettingsTable.effectiveFrom], note = it[LocationVatSettingsTable.note],
                updatedBy = UserRef(it[LocationVatSettingsTable.updatedByUserId]), createdAt = it[LocationVatSettingsTable.createdAt],
                state = when {
                    it[LocationVatSettingsTable.effectiveFrom].isAfter(today) -> "Scheduled"
                    it[LocationVatSettingsTable.id].value == inForce -> "In force"
                    else -> "Superseded"
                },
            )
        }
    }

    // ---- VatRates (spi): caller's transaction ----

    override fun forLocation(locationId: UUID, onDate: LocalDate): VatSetting {
        val root = hierarchy.rootOf(locationId)
        val setting = LocationVatSettingsTable.selectAll()
            .where { (LocationVatSettingsTable.tenantId eq support.tenantId()) and (LocationVatSettingsTable.locationId eq root) }
            .filter { !it[LocationVatSettingsTable.effectiveFrom].isAfter(onDate) }
            .maxByOrNull { it[LocationVatSettingsTable.effectiveFrom] }
        if (setting != null) {
            val enabled = setting[LocationVatSettingsTable.vatEnabled]
            return VatSetting(root, enabled, if (enabled) setting[LocationVatSettingsTable.vatRate] else BigDecimal.ZERO.setScale(2),
                setting[LocationVatSettingsTable.vatRegistrationNumber], setting[LocationVatSettingsTable.effectiveFrom])
        }
        val location = LocationsTable.selectAll().where { (LocationsTable.id eq root) and (LocationsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.invalidLocation("Location not found")
        val enabled = location[LocationsTable.vatEnabled]
        return VatSetting(root, enabled, if (enabled) location[LocationsTable.vatRate] else BigDecimal.ZERO.setScale(2), location[LocationsTable.vatRegistrationNumber],
            LocalDate.ofInstant(location[LocationsTable.createdAt], ZoneId.of("UTC")))
    }

    // ---- Applying scheduled changes ----

    /** Hourly, so "takes effect at the start of the day" holds in every tenant's timezone. */
    @Scheduled(cron = "\${zifiq.account.vat-cron:0 7 * * * *}")
    fun applyDue() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            runCatching { tx.executeForTenant(tenantId) { applyAll() } }
                .onFailure { logger.warn("VAT settings for tenant {} could not be applied: {}", tenantId, it.message) }
        }
    }

    /** Visible for tests: refresh every location's cache for the current tenant. */
    fun applyAll() {
        val today = support.today()
        LocationVatSettingsTable.select(LocationVatSettingsTable.locationId).where { LocationVatSettingsTable.tenantId eq support.tenantId() }
            .withDistinct().map { it[LocationVatSettingsTable.locationId] }.forEach { applyCurrent(it, today) }
    }

    private fun applyCurrent(locationId: UUID, today: LocalDate) {
        val current = LocationVatSettingsTable.selectAll()
            .where { (LocationVatSettingsTable.tenantId eq support.tenantId()) and (LocationVatSettingsTable.locationId eq locationId) }
            .filter { !it[LocationVatSettingsTable.effectiveFrom].isAfter(today) }
            .maxByOrNull { it[LocationVatSettingsTable.effectiveFrom] } ?: return
        LocationsTable.update({ (LocationsTable.id eq locationId) and (LocationsTable.tenantId eq support.tenantId()) }) {
            it[vatEnabled] = current[LocationVatSettingsTable.vatEnabled]
            it[vatRate] = current[LocationVatSettingsTable.vatRate]
            it[vatRegistrationNumber] = current[LocationVatSettingsTable.vatRegistrationNumber]
        }
    }

    private fun location(locationId: UUID): ResultRow {
        val location = LocationsTable.selectAll().where { (LocationsTable.id eq locationId) and (LocationsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.invalidLocation("Location not found")
        if (location[LocationsTable.type] !in setOf("SHOWROOM", "FACTORY")) throw ApiErrors.invalidLocation("VAT is set per showroom or factory")
        support.assertAccess(locationId)
        return location
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LocationVatService::class.java)
    }
}
