package com.zifiq.customers.service

import com.zifiq.platform.directory.UserRef
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.customers.dto.LedgerPage
import com.zifiq.customers.dto.LedgerRow
import com.zifiq.customers.dto.ReversalRequest
import com.zifiq.customers.dto.WalletAdjustmentRequest
import com.zifiq.customers.model.CustomerLedgerEntriesTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.LedgerDirection
import com.zifiq.customers.spi.LedgerEntryRef
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.DecimalColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Sum
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/** Customer History (wallet/deposit ledger) and admin corrections — docs/04 §4.5, §4.6, §9. */
@Service
class CustomerLedgerService(
    private val support: CustomerSupport,
    private val tx: TenantScopedTransaction,
    private val customers: CustomerService,
    private val walletLedger: WalletLedgerService,
    private val sequences: DocumentSequenceService,
    private val moduleSettings: ModuleSettings,
    private val notify: Notify,
) {
    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("entryType", "Entry type", FilterType.ENUM, CustomerLedgerEntriesTable.entryType, LedgerEntryType.entries.map { FilterOption(it.name, it.name) }, facetable = true),
            FilterField("direction", "Direction", FilterType.ENUM, CustomerLedgerEntriesTable.direction, LedgerDirection.entries.map { FilterOption(it.name, it.name.lowercase().replaceFirstChar(Char::titlecase)) }, facetable = true),
            FilterField("sourceModule", "Source module", FilterType.ENUM, CustomerLedgerEntriesTable.sourceModule),
            FilterField("locationId", "Location", FilterType.LOCATION, CustomerLedgerEntriesTable.locationId),
            FilterField("performedBy", "Performed by", FilterType.UUID, CustomerLedgerEntriesTable.performedByUserId),
            FilterField("amount", "Amount", FilterType.MONEY, CustomerLedgerEntriesTable.amount),
            FilterField("entryDate", "Date", FilterType.DATE, CustomerLedgerEntriesTable.entryDate, sortable = true),
            FilterField("createdAt", "Recorded", FilterType.DATETIME, CustomerLedgerEntriesTable.createdAt, sortable = true),
        ),
        primaryDateKey = "entryDate",
        defaultSort = listOf("createdAt" to SortOrder.ASC),
        tiebreaker = CustomerLedgerEntriesTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(CustomerLedgerEntriesTable.reference, SearchKind.REFERENCE, "Reference"),
            SearchField(RawExpression<String>({ +"customer_ledger_entries.search_vector" }), SearchKind.FULLTEXT, "Description"),
            SearchField(CustomerLedgerEntriesTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = filterSpec,
    )

    fun filters(): FilterMetadata = tx.execute { filterSpec.metadata(context()) }

    private fun context() = FilterContext(costVisible = true, zone = moduleSettings.zone())

    /** Staff view (customers.ledger.read), within the caller's read scope. */
    fun ledger(customerId: UUID, balanceType: BalanceType, request: ListSearchRequest): LedgerPage = tx.execute {
        customers.assertVisible(support.row(customerId))
        page(customerId, balanceType, request)
    }

    /** Self-service: the caller's own record (customers.self.read). */
    fun myLedger(balanceType: BalanceType, request: ListSearchRequest): LedgerPage = tx.execute {
        page(customers.myId(), balanceType, request)
    }

    private fun page(customerId: UUID, balanceType: BalanceType, request: ListSearchRequest): LedgerPage {
        val context = context()
        val parsed = searchSpec.parse(request.q)
        val pageRequest = PageRequest.of(request.page, request.size)
        // `reversible` is computed, so it's a post-filter-free convenience flag only; strip it from params.
        val params = request.params - setOf("reversible", "balanceType", "includeRunningBalance")

        val base = (CustomerLedgerEntriesTable.tenantId eq support.tenantId()) and
            (CustomerLedgerEntriesTable.customerId eq customerId) and
            (CustomerLedgerEntriesTable.balanceType eq balanceType.name)
        val condition = listOfNotNull(base, searchSpec.condition(parsed), filterSpec.fromParams(params, context), filterSpec.fromJson(request.filter, context)).allOf()!!

        // Running balances read top-down, so the order is fixed oldest-first unless the caller opts out.
        val descending = request.sort.firstOrNull()?.let { it.field == "createdAt" && it.dir.equals("desc", true) } == true &&
            request.params["includeRunningBalance"]?.firstOrNull() == "false"
        val order = if (descending) SortOrder.DESC else SortOrder.ASC
        val query = CustomerLedgerEntriesTable.selectAll().where(condition)
            .orderBy(CustomerLedgerEntriesTable.createdAt to order, CustomerLedgerEntriesTable.id to order)
        val result = Paging.fetch(query, pageRequest) { it }

        val ids = result.items.map { it[CustomerLedgerEntriesTable.id].value }
        val reversedBy = if (ids.isEmpty()) emptyMap() else CustomerLedgerEntriesTable
            .select(CustomerLedgerEntriesTable.id, CustomerLedgerEntriesTable.reversesEntryId)
            .where { CustomerLedgerEntriesTable.reversesEntryId inList ids }
            .associate { it[CustomerLedgerEntriesTable.reversesEntryId]!! to it[CustomerLedgerEntriesTable.id].value }

        val rows = result.items.map { row ->
            val id = row[CustomerLedgerEntriesTable.id].value
            val type = LedgerEntryType.valueOf(row[CustomerLedgerEntriesTable.entryType])
            val amount = row[CustomerLedgerEntriesTable.amount]
            LedgerRow(
                id = id,
                entryDate = row[CustomerLedgerEntriesTable.entryDate],
                reference = row[CustomerLedgerEntriesTable.reference],
                entryType = type,
                displayType = type.displayType,
                direction = type.direction,
                description = row[CustomerLedgerEntriesTable.description],
                paymentMethod = row[CustomerLedgerEntriesTable.paymentMethod],
                amount = if (type.direction == LedgerDirection.CREDIT) amount else amount.negate(),
                balanceAfter = row[CustomerLedgerEntriesTable.balanceAfter],
                locationId = row[CustomerLedgerEntriesTable.locationId],
                sourceModule = row[CustomerLedgerEntriesTable.sourceModule],
                performedBy = UserRef.of(row[CustomerLedgerEntriesTable.performedByUserId]),
                reversible = row[CustomerLedgerEntriesTable.sourceModule] == SOURCE_MODULE &&
                    type != LedgerEntryType.REVERSAL_CREDIT && type != LedgerEntryType.REVERSAL_DEBIT && id !in reversedBy,
                reversedByEntryId = reversedBy[id],
                createdAt = row[CustomerLedgerEntriesTable.createdAt],
            )
        }

        val (from, to) = dateRange(request.params, context)
        val opening = from?.let { balanceAt(base, CustomerLedgerEntriesTable.entryDate less it) } ?: BigDecimal.ZERO.setScale(2)
        val closing = balanceAt(base, to?.let { CustomerLedgerEntriesTable.entryDate lessEq it }) ?: opening
        val credits = total(condition, LedgerDirection.CREDIT)
        val debits = total(condition, LedgerDirection.DEBIT)

        return LedgerPage(
            customer = support.toSummary(support.row(customerId)),
            balanceType = balanceType,
            openingBalance = opening,
            closingBalance = closing,
            totalCredits = credits,
            totalDebits = debits,
            items = rows,
            page = result.page,
            size = result.size,
            totalItems = result.totalItems,
            totalPages = result.totalPages,
        )
    }

    /** The from/to that bound opening/closing balances: explicit dates or a period preset. */
    private fun dateRange(params: Map<String, List<String>>, context: FilterContext): Pair<LocalDate?, LocalDate?> {
        val period = params["period"]?.firstOrNull()?.let { com.zifiq.common.filter.DatePeriod.parse(it) }?.resolve(context.today)
        val from = params["from"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: period?.from
        val to = params["to"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: period?.to
        return from to to
    }

    private fun balanceAt(base: Op<Boolean>, bound: Op<Boolean>?): BigDecimal? =
        CustomerLedgerEntriesTable.select(CustomerLedgerEntriesTable.balanceAfter)
            .where(if (bound == null) base else base and bound)
            .orderBy(CustomerLedgerEntriesTable.createdAt to SortOrder.DESC, CustomerLedgerEntriesTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.get(CustomerLedgerEntriesTable.balanceAfter)

    private fun total(condition: Op<Boolean>, direction: LedgerDirection): BigDecimal {
        val sum = Sum(CustomerLedgerEntriesTable.amount, DecimalColumnType(16, 2))
        return CustomerLedgerEntriesTable.select(sum)
            .where(condition and (CustomerLedgerEntriesTable.direction eq direction.name))
            .single()[sum] ?: BigDecimal.ZERO.setScale(2)
    }

    /** §4.6: an Administrator's correction, through the same ledger path as every other movement. */
    fun adjust(customerId: UUID, request: WalletAdjustmentRequest): LedgerEntryRef = tx.execute {
        val customer = support.row(customerId)
        customers.assertVisible(customer)
        val direction = request.direction!!
        val posting = LedgerPosting(
            customerId = customerId,
            balanceType = request.balanceType!!,
            entryType = if (direction == LedgerDirection.CREDIT) LedgerEntryType.ADJUSTMENT_CREDIT else LedgerEntryType.ADJUSTMENT_DEBIT,
            amount = request.amount!!,
            reference = sequences.next("WLT"),
            description = "Adjustment: ${request.reason.trim()}",
            paymentMethod = "Wallet adjustment",
            sourceModule = SOURCE_MODULE,
            sourceType = "ADMIN_ADJUSTMENT",
            sourceId = null,
            locationId = customer[CustomersTable.locationId],
            reason = request.reason.trim(),
        )
        val entry = if (direction == LedgerDirection.CREDIT) walletLedger.credit(posting) else walletLedger.debit(posting)
        notifyLedgerReaders(customer[CustomersTable.code], customer[CustomersTable.name], customerId,
            "${posting.balanceType.name.lowercase().replaceFirstChar(Char::titlecase)} ${direction.name.lowercase()} adjustment of ₦${entry.amount.toPlainString()} (${entry.reference}): ${request.reason.trim()}")
        entry
    }

    /** §4.6: only this module's own entries are reversed here; sales etc. are reversed by voiding their source document. */
    fun reverse(entryId: UUID, request: ReversalRequest): LedgerEntryRef = tx.execute {
        val entry = CustomerLedgerEntriesTable.selectAll()
            .where { (CustomerLedgerEntriesTable.id eq entryId) and (CustomerLedgerEntriesTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.ledgerEntryNotFound()
        val customerId = entry[CustomerLedgerEntriesTable.customerId].value
        val customer = support.row(customerId)
        customers.assertVisible(customer)
        val result = walletLedger.reverse(entryId, request.reason.trim(), SOURCE_MODULE)
        notifyLedgerReaders(customer[CustomersTable.code], customer[CustomersTable.name], customerId,
            "Entry ${entry[CustomerLedgerEntriesTable.reference]} (₦${entry[CustomerLedgerEntriesTable.amount].toPlainString()}) reversed: ${request.reason.trim()}")
        result
    }

    private fun notifyLedgerReaders(code: String, name: String, customerId: UUID, body: String) {
        notify.holdersOf(
            permission = "customers.ledger.read",
            locationId = null,
            category = NotificationCategory.ALERT,
            title = "Wallet correction — $code",
            body = "$name ($code): $body",
            linkType = "customer",
            linkRef = customerId.toString(),
            exclude = support.userId(),
        )
    }

    companion object {
        const val SOURCE_MODULE = "customers"
    }
}
