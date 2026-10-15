package com.zifiq.account.service

import com.zifiq.platform.directory.Refs
import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.account.dto.CashLodgementRequest
import com.zifiq.account.dto.CashLodgementRow
import com.zifiq.account.dto.FundConfirmRequest
import com.zifiq.account.dto.FundDisbursementRequest
import com.zifiq.account.dto.FundDisbursementRow
import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.CashLodgementsTable
import com.zifiq.account.model.FundDisbursementsTable
import com.zifiq.account.repository.BankEntryType
import com.zifiq.account.repository.LedgerPosting
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.Recipients
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Funds Awaiting (docs/07 §4.14): head office sends money to a branch or factory, which confirms
 * what actually arrived. The source pays at once; the destination is credited only with what was
 * received, and a shortfall is reported, never silently re-credited (rule A11).
 */
@Service
class FundDisbursementService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val moves: MoneyMoves,
    private val sequences: DocumentSequenceService,
    private val notify: Notify,
    private val recipients: Recipients,
) {
    fun send(request: FundDisbursementRequest): FundDisbursementRow = tx.execute {
        val destinationLocation = request.destinationLocationId!!
        support.validateLocation(destinationLocation)
        val channel = request.channel!!
        val (source, destination) = when (channel) {
            "BANK_TRANSFER" -> {
                if (request.sourceBankAccountId == null || request.destinationBankAccountId == null || request.sourceCashAccountId != null || request.destinationCashAccountId != null) {
                    throw ApiErrors.validationFailed(mapOf("channel" to "a bank transfer needs sourceBankAccountId and destinationBankAccountId (and no cash accounts)"))
                }
                moves.bank(request.sourceBankAccountId) to moves.existing(true, request.destinationBankAccountId)
            }
            else -> {
                if (request.sourceCashAccountId == null || request.destinationCashAccountId == null || request.sourceBankAccountId != null || request.destinationBankAccountId != null) {
                    throw ApiErrors.validationFailed(mapOf("channel" to "a cash disbursement needs sourceCashAccountId and destinationCashAccountId (and no bank accounts)"))
                }
                moves.cash(request.sourceCashAccountId) to moves.existing(false, request.destinationCashAccountId)
            }
        }
        if (destination.locationId != destinationLocation) throw ApiErrors.invalidDestinationAccount("${destination.code} does not belong to the destination location")
        if (!destination.bank && destination.kind !in setOf("VAULT", "TILL")) throw ApiErrors.invalidDestinationAccount("Cash is received into a vault or till, not ${destination.code}")
        if (source.id == destination.id) throw ApiErrors.invalidDestinationAccount("The source and destination are the same account")
        val amount = Money.round(request.sentAmount!!)

        val ref = sequences.next("BF")
        val id = FundDisbursementsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[FundDisbursementsTable.ref] = ref
            it[FundDisbursementsTable.channel] = channel
            it[sourceBankAccountId] = source.id.takeIf { source.bank }
            it[sourceCashAccountId] = source.id.takeUnless { source.bank }
            it[destinationLocationId] = destinationLocation
            it[destinationBankAccountId] = destination.id.takeIf { destination.bank }
            it[destinationCashAccountId] = destination.id.takeUnless { destination.bank }
            it[purpose] = request.purpose.trim()
            it[sentAmount] = amount
            it[sentByUserId] = support.userId()
            it[sentAt] = Instant.now()
            it[status] = "AWAITING"
        }.value
        moves.out(source, amount, LedgerPosting(ref, "Funds sent to ${destination.code}: ${request.purpose.trim()}", "FUND_DISBURSEMENT_OUT", id, entryDate = support.today(), userId = support.userId()))
        val eventId = support.record("account.funds.disbursed", "fund_disbursement", id, ref, "$ref: ${Money.naira(amount)} sent to ${destination.code}",
            mapOf("amount" to amount, "channel" to channel), destinationLocation)
        notify.holdersOf(
            permission = "accounting.funds.confirm", locationId = destinationLocation, category = NotificationCategory.APPROVAL_REQUIRED,
            title = "Funds sent to your location — $ref", body = "${Money.naira(amount)} into ${destination.code}: ${request.purpose.trim()}. Confirm what you received.",
            linkType = "fund-disbursement", linkRef = id.toString(), sourceEventId = eventId, exclude = support.userId(),
        )
        row(id).let(::toRow)
    }

    /** The destination is credited with what arrived; less than sent needs a reason and raises a shortfall. */
    fun confirm(id: UUID, request: FundConfirmRequest): FundDisbursementRow = tx.execute {
        val row = awaiting(id)
        val ref = row[FundDisbursementsTable.ref]
        val sent = row[FundDisbursementsTable.sentAmount]
        val received = Money.round(request.receivedAmount!!)
        if (received > sent) throw ApiErrors.receivedExceedsSent()
        val reason = request.shortfallReason?.trim()?.ifEmpty { null }
        if (received < sent && reason == null) throw ApiErrors.shortfallReasonRequired()
        val dateReceived = request.dateReceived!!
        support.requireNotFuture("dateReceived", dateReceived)
        val destination = moves.existing(row[FundDisbursementsTable.destinationBankAccountId] != null,
            row[FundDisbursementsTable.destinationBankAccountId] ?: row[FundDisbursementsTable.destinationCashAccountId]!!)
        if (received.signum() > 0) {
            moves.into(destination, received, LedgerPosting(ref, "Funds received: ${row[FundDisbursementsTable.purpose]}", "FUND_DISBURSEMENT_IN", id, entryDate = dateReceived, userId = support.userId()))
        }
        val shortfall = sent - received
        FundDisbursementsTable.update({ FundDisbursementsTable.id eq id }) {
            it[receivedAmount] = received
            it[shortfallAmount] = shortfall
            it[shortfallReason] = reason
            it[bankCreditReference] = request.bankCreditReference?.trim()?.ifEmpty { null }
            it[FundDisbursementsTable.dateReceived] = dateReceived
            it[confirmedByUserId] = support.userId()
            it[confirmedAt] = Instant.now()
            it[status] = if (shortfall.signum() == 0) "CONFIRMED" else "PARTIALLY_CONFIRMED"
        }
        val location = row[FundDisbursementsTable.destinationLocationId]
        val eventId = support.record("account.funds.confirmed", "fund_disbursement", id, ref, "$ref: ${Money.naira(received)} of ${Money.naira(sent)} received",
            mapOf("sent" to sent, "received" to received), location)
        notify.inApp(listOf(row[FundDisbursementsTable.sentByUserId]), NotificationCategory.CONFIRMATION, "Funds received — $ref",
            "${Money.naira(received)} of ${Money.naira(sent)} was confirmed.", "fund-disbursement", id.toString(), eventId, exclude = support.userId())
        if (shortfall.signum() > 0) {
            // Money that left the source but didn't arrive: reported for investigation, not re-credited anywhere.
            val shortfallEvent = support.record("account.funds.shortfall", "fund_disbursement", id, ref, "$ref: shortfall of ${Money.naira(shortfall)} — $reason",
                mapOf("sent" to sent, "received" to received, "shortfall" to shortfall, "reason" to reason), location)
            val recipients = (listOf(row[FundDisbursementsTable.sentByUserId]) + notifyManagement(location)).toSet()
            notify.inApp(recipients, NotificationCategory.ALERT, "Funds shortfall — $ref",
                "${Money.naira(shortfall)} of ${Money.naira(sent)} did not arrive: $reason", "fund-disbursement", id.toString(), shortfallEvent, exclude = support.userId())
        }
        row(id).let(::toRow)
    }

    /** Nothing arrived, or it was sent in error: the full amount goes back to the source. */
    fun reject(id: UUID, reason: String): FundDisbursementRow = tx.execute {
        val row = awaiting(id)
        val ref = row[FundDisbursementsTable.ref]
        val source = moves.existing(row[FundDisbursementsTable.sourceBankAccountId] != null, row[FundDisbursementsTable.sourceBankAccountId] ?: row[FundDisbursementsTable.sourceCashAccountId]!!)
        moves.into(source, row[FundDisbursementsTable.sentAmount], LedgerPosting(ref, "Funds returned, rejected by the destination: ${reason.trim()}", "FUND_DISBURSEMENT_RETURN", id, entryDate = support.today(), userId = support.userId()))
        FundDisbursementsTable.update({ FundDisbursementsTable.id eq id }) {
            it[status] = "REJECTED"; it[rejectionReason] = reason.trim(); it[confirmedByUserId] = support.userId(); it[confirmedAt] = Instant.now()
        }
        val eventId = support.record("account.funds.rejected", "fund_disbursement", id, ref, "$ref rejected: ${reason.trim()}", mapOf("amount" to row[FundDisbursementsTable.sentAmount]), row[FundDisbursementsTable.destinationLocationId])
        notify.inApp(listOf(row[FundDisbursementsTable.sentByUserId]), NotificationCategory.ALERT, "Funds rejected — $ref", reason.trim(), "fund-disbursement", id.toString(), eventId, exclude = support.userId())
        row(id).let(::toRow)
    }

    fun get(id: UUID): FundDisbursementRow = tx.execute { toRow(row(id)) }

    fun list(request: ListSearchRequest): PageResult<FundDisbursementRow> = tx.execute {
        val context = support.filterContext()
        val parsed = searchSpec.parse(request.q)
        val condition = listOfNotNull(
            FundDisbursementsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { FundDisbursementsTable.destinationLocationId inList it },
            searchSpec.condition(parsed),
            filterSpec.fromParams(request.params, context),
            filterSpec.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) searchSpec.rank(parsed) + filterSpec.sort(emptyList(), context) else filterSpec.sort(request.sortParams, context)
        val joined = joined()
        val page = Paging.fetch(joined.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) {
            val all = joined.select(FundDisbursementsTable.status, FundDisbursementsTable.sentAmount, FundDisbursementsTable.shortfallAmount).where(condition).toList()
            val awaiting = all.filter { it[FundDisbursementsTable.status] == "AWAITING" }
            mapOf(
                "awaiting" to awaiting.size,
                "valueAwaiting" to Money.round(awaiting.fold(BigDecimal.ZERO) { s, it -> s + it[FundDisbursementsTable.sentAmount] }),
                "shortfallTotal" to Money.round(all.fold(BigDecimal.ZERO) { s, it -> s + (it[FundDisbursementsTable.shortfallAmount] ?: BigDecimal.ZERO) }),
            )
        } else null
        page.withItems(toRows(page.items)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filterSpec.metadata(support.filterContext()) }

    // ---- Helpers ----

    private fun notifyManagement(locationId: UUID): Set<UUID> = recipients.withPermission(CashAccountService.MANAGEMENT_ALERTS, locationId)

    private fun awaiting(id: UUID): ResultRow {
        val row = FundDisbursementsTable.selectAll().where { (FundDisbursementsTable.id eq id) and (FundDisbursementsTable.tenantId eq support.tenantId()) }
            .forUpdate().firstOrNull() ?: throw ApiErrors.disbursementNotFound()
        support.assertAccess(row[FundDisbursementsTable.destinationLocationId])
        if (row[FundDisbursementsTable.status] != "AWAITING") throw ApiErrors.alreadyDecided("disbursement")
        return row
    }

    private val sourceBank = BankAccountsTable.alias("src_bank")
    private val sourceCash = CashAccountsTable.alias("src_cash")
    private val destinationBank = BankAccountsTable.alias("dst_bank")
    private val destinationCash = CashAccountsTable.alias("dst_cash")

    private fun joined() = FundDisbursementsTable
        .join(LocationsTable, JoinType.INNER, additionalConstraint = { RawOp { +FundDisbursementsTable.destinationLocationId; +" = "; +LocationsTable.id } })
        .join(sourceBank, JoinType.LEFT, FundDisbursementsTable.sourceBankAccountId, sourceBank[BankAccountsTable.id])
        .join(sourceCash, JoinType.LEFT, FundDisbursementsTable.sourceCashAccountId, sourceCash[CashAccountsTable.id])
        .join(destinationBank, JoinType.LEFT, FundDisbursementsTable.destinationBankAccountId, destinationBank[BankAccountsTable.id])
        .join(destinationCash, JoinType.LEFT, FundDisbursementsTable.destinationCashAccountId, destinationCash[CashAccountsTable.id])

    private fun row(id: UUID): ResultRow {
        val row = joined().selectAll().where { (FundDisbursementsTable.id eq id) and (FundDisbursementsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.disbursementNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[FundDisbursementsTable.destinationLocationId] !in visible) throw ApiErrors.disbursementNotFound()
        return row
    }

    private fun toRow(row: ResultRow) = toRows(listOf(row)).single()

    private fun toRows(rows: List<ResultRow>): List<FundDisbursementRow> {
        return rows.map { row ->
            FundDisbursementRow(
                id = row[FundDisbursementsTable.id].value, ref = row[FundDisbursementsTable.ref], date = row[FundDisbursementsTable.sentAt],
                destinationLocation = Ref.location(row[FundDisbursementsTable.destinationLocationId]),
                purpose = row[FundDisbursementsTable.purpose], channel = row[FundDisbursementsTable.channel],
                sourceAccount = row[sourceBank[BankAccountsTable.code]] ?: row[sourceCash[CashAccountsTable.code]],
                destinationAccount = row[destinationBank[BankAccountsTable.code]] ?: row[destinationCash[CashAccountsTable.code]],
                sentBy = UserRef(row[FundDisbursementsTable.sentByUserId]), sent = row[FundDisbursementsTable.sentAmount],
                received = row[FundDisbursementsTable.receivedAmount], shortfall = row[FundDisbursementsTable.shortfallAmount],
                shortfallReason = row[FundDisbursementsTable.shortfallReason], status = row[FundDisbursementsTable.status],
                confirmedBy = UserRef.of(row[FundDisbursementsTable.confirmedByUserId]), dateReceived = row[FundDisbursementsTable.dateReceived],
                rejectionReason = row[FundDisbursementsTable.rejectionReason],
            )
        }
    }

    private val hasShortfall = RawExpression<Boolean>({ +"(coalesce("; +FundDisbursementsTable.shortfallAmount; +", 0) > 0)" })

    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("locationId", "Destination", FilterType.LOCATION, FundDisbursementsTable.destinationLocationId, aliases = setOf("branch"), facetable = true),
            FilterField("status", "Status", FilterType.ENUM, FundDisbursementsTable.status,
                listOf("AWAITING", "CONFIRMED", "PARTIALLY_CONFIRMED", "REJECTED").map { FilterOption(it, BankAccountService.label(it)) }, facetable = true),
            FilterField("channel", "Channel", FilterType.ENUM, FundDisbursementsTable.channel, listOf(FilterOption("BANK_TRANSFER", "Bank transfer"), FilterOption("CASH", "Cash"))),
            FilterField("hasShortfall", "Has shortfall", FilterType.BOOLEAN, hasShortfall),
            FilterField("sentBy", "Sent by", FilterType.UUID, FundDisbursementsTable.sentByUserId),
            FilterField("sentAmount", "Amount sent", FilterType.MONEY, FundDisbursementsTable.sentAmount, sortable = true),
            FilterField("sentAt", "Sent", FilterType.DATETIME, FundDisbursementsTable.sentAt, sortable = true),
        ),
        primaryDateKey = "sentAt",
        defaultSort = listOf("sentAt" to SortOrder.DESC),
        tiebreaker = FundDisbursementsTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(FundDisbursementsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(FundDisbursementsTable.purpose, SearchKind.TRIGRAM, "Purpose"),
            SearchField(LocationsTable.name, SearchKind.TRIGRAM, "Branch"),
            SearchField(FundDisbursementsTable.sentAmount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = filterSpec,
    )
}

/**
 * Cash lodgements — Cash Transfer History (docs/07 §4.15). The cash leaves the till when it is taken
 * to the bank; the bank is credited when Accounts confirms it arrived (rule A12). A rejected
 * lodgement is a cash-loss investigation, not a reversal: the cash is not put back.
 */
@Service
class CashLodgementService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val moves: MoneyMoves,
    private val ledger: MoneyLedgerRepository,
    private val sequences: DocumentSequenceService,
    private val notify: Notify,
) {
    fun record(request: CashLodgementRequest): CashLodgementRow = tx.execute {
        val locationId = request.locationId!!
        support.validateLocation(locationId)
        val till = moves.cash(request.cashAccountId!!, setOf("TILL", "VAULT"))
        if (till.locationId != locationId) throw ApiErrors.invalidPayingAccount("${till.code} is not at this location")
        // Takings go into any active company account, including head office's collection account.
        val bank = moves.bank(request.bankAccountId!!, checkAccess = false)
        val takings = Money.round(request.takings!!)
        val banked = Money.round(request.banked!!)
        if (banked > takings) throw ApiErrors.bankedExceedsTakings()
        val date = request.depositDate!!
        support.requireNotFuture("depositDate", date)

        val ref = sequences.next("LDG")
        val id = CashLodgementsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[CashLodgementsTable.ref] = ref
            it[CashLodgementsTable.locationId] = locationId
            it[cashAccountId] = till.id
            it[depositDate] = date
            it[CashLodgementsTable.takings] = takings
            it[CashLodgementsTable.banked] = banked
            it[retained] = takings - banked
            it[bankAccountId] = bank.id
            it[tellerReference] = request.tellerReference?.trim()?.ifEmpty { null }
            it[depositedByUserId] = support.userId()
            it[status] = "AWAITING_CONFIRMATION"
            it[createdAt] = Instant.now()
        }.value
        val posted = moves.out(till, banked, LedgerPosting(ref, "Cash lodgement to ${bank.code} ${bank.name} — $ref", "CASH_LODGEMENT", id, entryDate = date, userId = support.userId()))
        CashLodgementsTable.update({ CashLodgementsTable.id eq id }) { it[cashTransactionId] = posted.cashTransactionId }
        support.record("account.lodgement.recorded", "cash_lodgement", id, ref, "$ref: ${Money.naira(banked)} of ${Money.naira(takings)} taken to ${bank.code}",
            mapOf("takings" to takings, "banked" to banked), locationId)
        toRow(row(id))
    }

    /** The deposit shows on the bank statement. */
    fun confirm(id: UUID): CashLodgementRow = tx.execute {
        val row = awaiting(id)
        val ref = row[CashLodgementsTable.ref]
        val bank = moves.existing(true, row[CashLodgementsTable.bankAccountId])
        val posted = ledger.postBank(support.tenantId(), bank.id, BankEntryType.CREDIT, row[CashLodgementsTable.banked],
            LedgerPosting(ref, "Cash lodgement — $ref", "CASH_LODGEMENT", id, entryDate = row[CashLodgementsTable.depositDate], userId = support.userId()))
        CashLodgementsTable.update({ CashLodgementsTable.id eq id }) {
            it[status] = "CONFIRMED"; it[confirmedByUserId] = support.userId(); it[confirmedAt] = Instant.now(); it[bankTransactionId] = posted.id
        }
        support.record("account.lodgement.confirmed", "cash_lodgement", id, ref, "$ref confirmed: ${Money.naira(row[CashLodgementsTable.banked])} credited to ${bank.code}",
            mapOf("banked" to row[CashLodgementsTable.banked]), row[CashLodgementsTable.locationId])
        toRow(row(id))
    }

    /** The bank never received it: flagged to Management; the cash is not put back (it physically left). */
    fun reject(id: UUID, reason: String): CashLodgementRow = tx.execute {
        val row = awaiting(id)
        val ref = row[CashLodgementsTable.ref]
        CashLodgementsTable.update({ CashLodgementsTable.id eq id }) {
            it[status] = "REJECTED"; it[rejectionReason] = reason.trim(); it[confirmedByUserId] = support.userId(); it[confirmedAt] = Instant.now()
        }
        val eventId = support.record("account.lodgement.rejected", "cash_lodgement", id, ref,
            "$ref rejected — ${Money.naira(row[CashLodgementsTable.banked])} not received by the bank: ${reason.trim()}",
            mapOf("banked" to row[CashLodgementsTable.banked], "reason" to reason.trim()), row[CashLodgementsTable.locationId])
        notify.holdersOf(
            permission = CashAccountService.MANAGEMENT_ALERTS, locationId = row[CashLodgementsTable.locationId], category = NotificationCategory.ALERT,
            title = "Cash lodgement not received — $ref",
            body = "${Money.naira(row[CashLodgementsTable.banked])} taken to the bank was not received: ${reason.trim()}",
            linkType = "cash-lodgement", linkRef = id.toString(), sourceEventId = eventId, exclude = support.userId(),
        )
        toRow(row(id))
    }

    fun get(id: UUID): CashLodgementRow = tx.execute { toRow(row(id)) }

    fun list(request: ListSearchRequest): PageResult<CashLodgementRow> = tx.execute {
        val context = support.filterContext()
        val parsed = searchSpec.parse(request.q)
        val condition = listOfNotNull(
            CashLodgementsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { CashLodgementsTable.locationId inList it },
            searchSpec.condition(parsed),
            filterSpec.fromParams(request.params, context),
            filterSpec.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) searchSpec.rank(parsed) + filterSpec.sort(emptyList(), context) else filterSpec.sort(request.sortParams, context)
        val joined = joined()
        val page = Paging.fetch(joined.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) {
            val all = joined.select(CashLodgementsTable.takings, CashLodgementsTable.banked, CashLodgementsTable.retained).where(condition).toList()
            mapOf(
                "takings" to Money.round(all.fold(BigDecimal.ZERO) { s, it -> s + it[CashLodgementsTable.takings] }),
                "banked" to Money.round(all.fold(BigDecimal.ZERO) { s, it -> s + it[CashLodgementsTable.banked] }),
                "retained" to Money.round(all.fold(BigDecimal.ZERO) { s, it -> s + it[CashLodgementsTable.retained] }),
            )
        } else null
        page.withItems(toRows(page.items)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filterSpec.metadata(support.filterContext()) }

    fun search(q: String, limit: Int): List<SearchHit> =
        list(ListSearchRequest(q = q, size = limit, summary = false)).items.also { tx.execute { Refs.resolve(it) } }.map {
            SearchHit("lodgement", it.id, it.ref, "${it.location.name ?: ""} · ${Money.naira(it.banked)} · ${it.status.lowercase().replace('_', ' ')}", it.ref, "/account/cash-lodgements/${it.id}")
        }

    private fun awaiting(id: UUID): ResultRow {
        val row = CashLodgementsTable.selectAll().where { (CashLodgementsTable.id eq id) and (CashLodgementsTable.tenantId eq support.tenantId()) }
            .forUpdate().firstOrNull() ?: throw ApiErrors.lodgementNotFound()
        support.assertAccess(row[CashLodgementsTable.locationId])
        if (row[CashLodgementsTable.status] != "AWAITING_CONFIRMATION") throw ApiErrors.alreadyDecided("lodgement")
        return row
    }

    private fun joined() = CashLodgementsTable
        .join(LocationsTable, JoinType.INNER, additionalConstraint = { RawOp { +CashLodgementsTable.locationId; +" = "; +LocationsTable.id } })
        .join(CashAccountsTable, JoinType.INNER, CashLodgementsTable.cashAccountId, CashAccountsTable.id)
        .join(BankAccountsTable, JoinType.INNER, CashLodgementsTable.bankAccountId, BankAccountsTable.id)

    private fun row(id: UUID): ResultRow {
        val row = joined().selectAll().where { (CashLodgementsTable.id eq id) and (CashLodgementsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.lodgementNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[CashLodgementsTable.locationId] !in visible) throw ApiErrors.lodgementNotFound()
        return row
    }

    private fun toRow(row: ResultRow) = toRows(listOf(row)).single()

    private fun toRows(rows: List<ResultRow>): List<CashLodgementRow> {
        return rows.map { row ->
            CashLodgementRow(
                id = row[CashLodgementsTable.id].value, ref = row[CashLodgementsTable.ref], date = row[CashLodgementsTable.depositDate],
                location = Ref.location(row[CashLodgementsTable.locationId]), cashAccountCode = row[CashAccountsTable.code],
                takings = row[CashLodgementsTable.takings], banked = row[CashLodgementsTable.banked], retained = row[CashLodgementsTable.retained],
                bank = "${row[BankAccountsTable.code]} ${row[BankAccountsTable.bankName]}", accountNumberMasked = BankAccountService.mask(row[BankAccountsTable.accountNumber]),
                depositedBy = UserRef(row[CashLodgementsTable.depositedByUserId]), teller = row[CashLodgementsTable.tellerReference],
                status = row[CashLodgementsTable.status], confirmedBy = UserRef.of(row[CashLodgementsTable.confirmedByUserId]),
                confirmedAt = row[CashLodgementsTable.confirmedAt], rejectionReason = row[CashLodgementsTable.rejectionReason],
            )
        }
    }

    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("locationId", "Showroom", FilterType.LOCATION, CashLodgementsTable.locationId, aliases = setOf("showroom"), facetable = true),
            FilterField("bankAccountId", "Bank account", FilterType.UUID, CashLodgementsTable.bankAccountId, lookup = "/api/v1/account/bank-accounts/lookup", facetable = true),
            FilterField("status", "Status", FilterType.ENUM, CashLodgementsTable.status,
                listOf("AWAITING_CONFIRMATION", "CONFIRMED", "REJECTED").map { FilterOption(it, BankAccountService.label(it)) }, facetable = true),
            FilterField("depositedBy", "Deposited by", FilterType.UUID, CashLodgementsTable.depositedByUserId),
            FilterField("banked", "Banked", FilterType.MONEY, CashLodgementsTable.banked, sortable = true),
            FilterField("depositDate", "Deposit date", FilterType.DATE, CashLodgementsTable.depositDate, sortable = true),
            FilterField("createdAt", "Recorded", FilterType.DATETIME, CashLodgementsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "depositDate",
        defaultSort = listOf("depositDate" to SortOrder.DESC, "createdAt" to SortOrder.DESC),
        tiebreaker = CashLodgementsTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(CashLodgementsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(CashLodgementsTable.tellerReference, SearchKind.CODE, "Teller"),
            SearchField(CashLodgementsTable.banked, SearchKind.AMOUNT, "Banked"),
        ),
        qualifiers = filterSpec,
    )
}

/** Global search provider `lodgement` (docs/07 §9). */
@Component
class LodgementSearchProvider(private val support: AccountSupport, private val lodgements: CashLodgementService) : SearchProvider {
    override val type = "lodgement"
    override val label = "Cash lodgements"
    override val listRoute = "/account/cash-lodgements"

    override fun canSearch() = support.can("accounting.read")

    override fun search(q: String, limit: Int): List<SearchHit> = lodgements.search(q, limit)
}
