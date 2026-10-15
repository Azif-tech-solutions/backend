package com.zifiq.account.service

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

    /** One row per cashier per day. */
    fun payable(locationId: UUID?, cashierUserId: UUID?, from: LocalDate?, to: LocalDate?): List<TenderablePayableRow> = tx.execute {
        val zone = support.moduleSettings().zone()
        val rows = receipts(locationId, cashierUserId, from, to)
        val names = Names.users(rows.map { it[PaymentReceiptsTable.cashierUserId] })
        rows.groupBy { LocalDate.ofInstant(it[PaymentReceiptsTable.receivedAt], zone) to it[PaymentReceiptsTable.cashierUserId] }
            .map { (key, group) ->
                val t = takings(group)
                TenderablePayableRow(key.first, key.second, names[key.second], t.ftf, t.cash, t.pos, t.wallet, t.deposit, t.refund, Money.round(t.total), t.pending, t.declined)
            }
            .sortedWith(compareByDescending<TenderablePayableRow> { it.date }.thenBy { it.cashier ?: "" })
    }

    /** Per cashier for the period, with grand totals. */
    fun generalReport(locationId: UUID?, from: LocalDate?, to: LocalDate?): TenderableGeneralReport = tx.execute {
        val zone = support.moduleSettings().zone()
        val rows = receipts(locationId, null, from, to)
        val names = Names.users(rows.map { it[PaymentReceiptsTable.cashierUserId] })
        val perCashier = rows.groupBy { it[PaymentReceiptsTable.cashierUserId] }.map { (cashier, group) ->
            val t = takings(group)
            TenderableGeneralRow(cashier, names[cashier], group.map { LocalDate.ofInstant(it[PaymentReceiptsTable.receivedAt], zone) }.distinct().size,
                t.ftf, t.cash, t.pos, t.wallet, t.deposit, t.refund, Money.round(t.total))
        }.sortedBy { it.cashier ?: "" }
        val all = takings(rows)
        val totals = if (rows.isEmpty()) null else TenderableGeneralRow(UUID(0, 0), "All cashiers",
            rows.map { LocalDate.ofInstant(it[PaymentReceiptsTable.receivedAt], zone) }.distinct().size,
            all.ftf, all.cash, all.pos, all.wallet, all.deposit, all.refund, Money.round(all.total))
        TenderableGeneralReport(mapOf("from" to from, "to" to to), perCashier, totals)
    }

    /** Wallet Funds: dealers' fundings plus the return credits Customers posted for them, newest first. */
    fun walletFunds(customerId: UUID?, from: LocalDate?, to: LocalDate?, page: Int?, size: Int?): PageResult<TenderableWalletFundRow> = tx.execute {
        val zone = support.moduleSettings().zone()
        val fundings = WalletFundingsTable.selectAll().where(
            listOfNotNull(
                WalletFundingsTable.tenantId eq support.tenantId(),
                WalletFundingsTable.balanceType eq "WALLET",
                customerId?.let { WalletFundingsTable.customerId eq it },
                support.visibleLocations()?.let { WalletFundingsTable.locationId inList it },
                from?.let { WalletFundingsTable.createdAt greaterEq it.atStartOfDay(zone).toInstant() },
                to?.let { WalletFundingsTable.createdAt less it.plusDays(1).atStartOfDay(zone).toInstant() },
            ).allOf()!!,
        ).toList()
        val receipts = PaymentReceiptsTable.selectAll().where { PaymentReceiptsTable.id inList fundings.map { it[WalletFundingsTable.paymentReceiptId] }.ifEmpty { listOf(UUID(0, 0)) } }
            .associateBy { it[PaymentReceiptsTable.id].value }
        val banks = BankAccountsTable.selectAll().where { BankAccountsTable.tenantId eq support.tenantId() }.associate { it[BankAccountsTable.id].value to "${it[BankAccountsTable.code]} ${it[BankAccountsTable.bankName]}" }
        val reviewers = Names.users(receipts.values.mapNotNull { it[PaymentReceiptsTable.reviewedByUserId] } + fundings.map { it[WalletFundingsTable.createdByUserId] })
        val fromFundings = fundings.map { row ->
            val receipt = receipts[row[WalletFundingsTable.paymentReceiptId]]
            TenderableWalletFundRow(
                ref = row[WalletFundingsTable.ref], date = row[WalletFundingsTable.createdAt], customerId = row[WalletFundingsTable.customerId],
                dealer = "${row[WalletFundingsTable.customerCode]} ${row[WalletFundingsTable.customerName]}",
                method = Channel.labelOf(row[WalletFundingsTable.channel]),
                bank = receipt?.get(PaymentReceiptsTable.receivingBankAccountId)?.let(banks::get), amount = row[WalletFundingsTable.amount],
                confirmedBy = receipt?.get(PaymentReceiptsTable.reviewedByUserId)?.let(reviewers::get) ?: if (row[WalletFundingsTable.channel] == "CASH") reviewers[row[WalletFundingsTable.createdByUserId]] else null,
                status = when (row[WalletFundingsTable.status]) { "CONFIRMED" -> "Confirmed"; "AWAITING_CONFIRMATION" -> "Awaiting confirmation"; "DECLINED" -> "Declined"; else -> "Voided" },
            )
        }
        val returnCredits = customerQueries.ledgerEntries(LedgerEntriesFilter(customerIds = customerId?.let(::setOf), entryTypes = setOf(LedgerEntryType.RETURN_CREDIT), from = from, to = to))
        val dealers = customerQueries.byIds(returnCredits.map { it.customerId }.distinct()).filter { it.type == CustomerType.AUTHORISED_DEALER }.map { it.id }.toSet()
        val fromReturns = returnCredits.filter { it.customerId in dealers }.map {
            TenderableWalletFundRow(it.reference, it.createdAt, it.customerId, "${it.customerCode} ${it.customerName}", "Return credit", null, it.amount, null, "Confirmed")
        }
        val all = (fromFundings + fromReturns).sortedByDescending { it.date }
        val request = PageRequest.of(page, size)
        val items = all.drop(request.offset.toInt()).take(request.size)
        val confirmed = all.filter { it.status == "Confirmed" }.fold(BigDecimal.ZERO) { s, it -> s + it.amount }
        val awaiting = all.filter { it.status == "Awaiting confirmation" }.fold(BigDecimal.ZERO) { s, it -> s + it.amount }
        PageResult(items, request.page, request.size, all.size.toLong(), ((all.size + request.size - 1) / request.size), false,
            summary = mapOf("total" to Money.round(confirmed + awaiting), "confirmed" to Money.round(confirmed), "awaiting" to Money.round(awaiting)))
    }

    private fun receipts(locationId: UUID?, cashierUserId: UUID?, from: LocalDate?, to: LocalDate?): List<ResultRow> {
        val zone = support.moduleSettings().zone()
        return PaymentReceiptsTable.selectAll().where(
            listOfNotNull(
                PaymentReceiptsTable.tenantId eq support.tenantId(),
                PaymentReceiptsTable.status eq "ISSUED",
                support.visibleLocations()?.let { PaymentReceiptsTable.locationId inList it },
                support.resolveLocation(locationId)?.let { PaymentReceiptsTable.locationId eq it },
                cashierUserId?.let { PaymentReceiptsTable.cashierUserId eq it },
                from?.let { PaymentReceiptsTable.receivedAt greaterEq it.atStartOfDay(zone).toInstant() },
                to?.let { PaymentReceiptsTable.receivedAt less it.plusDays(1).atStartOfDay(zone).toInstant() },
            ).allOf()!!,
        ).toList()
    }

    private fun takings(rows: List<ResultRow>): Takings {
        fun sum(filter: (ResultRow) -> Boolean) = Money.round(rows.filter(filter).fold(BigDecimal.ZERO) { s, it -> s + it[PaymentReceiptsTable.amount] })
        fun counted(row: ResultRow) = row[PaymentReceiptsTable.confirmationStatus] in setOf("NOT_REQUIRED", "APPROVED")
        fun receipt(row: ResultRow, vararg channels: String) = row[PaymentReceiptsTable.kind] == "RECEIPT" && counted(row) && row[PaymentReceiptsTable.channel] in channels
        return Takings(
            ftf = sum { receipt(it, "FUND_TRANSFER", "CHEQUE") },
            cash = sum { receipt(it, "CASH") },
            pos = sum { receipt(it, "POS") },
            wallet = sum { receipt(it, "WALLET") },
            deposit = sum { receipt(it, "DEPOSIT") },
            refund = sum { it[PaymentReceiptsTable.kind] == "REFUND" },
            pending = sum { it[PaymentReceiptsTable.confirmationStatus] == "PENDING" },
            declined = sum { it[PaymentReceiptsTable.confirmationStatus] == "DECLINED" },
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
        val users = Names.users(settings.values.flatten().map { it[LocationVatSettingsTable.updatedByUserId] })
        val rows = locations.map { location ->
            val id = location[LocationsTable.id].value
            val all = settings[id].orEmpty()
            val current = all.filter { !it[LocationVatSettingsTable.effectiveFrom].isAfter(today) }.maxByOrNull { it[LocationVatSettingsTable.effectiveFrom] }
            val next = all.filter { it[LocationVatSettingsTable.effectiveFrom].isAfter(today) }.minByOrNull { it[LocationVatSettingsTable.effectiveFrom] }
            LocationVatRow(
                locationId = id, code = location[LocationsTable.code], name = location[LocationsTable.name], type = location[LocationsTable.type],
                registration = current?.get(LocationVatSettingsTable.vatRegistrationNumber) ?: location[LocationsTable.vatRegistrationNumber],
                rate = current?.get(LocationVatSettingsTable.vatRate) ?: location[LocationsTable.vatRate],
                enabled = current?.get(LocationVatSettingsTable.vatEnabled) ?: location[LocationsTable.vatEnabled],
                effectiveFrom = current?.get(LocationVatSettingsTable.effectiveFrom), updatedBy = current?.get(LocationVatSettingsTable.updatedByUserId)?.let(users::get),
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
        val users = Names.users(rows.map { it[LocationVatSettingsTable.updatedByUserId] })
        val inForce = rows.firstOrNull { !it[LocationVatSettingsTable.effectiveFrom].isAfter(today) }?.get(LocationVatSettingsTable.id)?.value
        rows.map {
            LocationVatHistoryRow(
                id = it[LocationVatSettingsTable.id].value, enabled = it[LocationVatSettingsTable.vatEnabled], rate = it[LocationVatSettingsTable.vatRate],
                registration = it[LocationVatSettingsTable.vatRegistrationNumber], effectiveFrom = it[LocationVatSettingsTable.effectiveFrom], note = it[LocationVatSettingsTable.note],
                updatedBy = users[it[LocationVatSettingsTable.updatedByUserId]], createdAt = it[LocationVatSettingsTable.createdAt],
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
