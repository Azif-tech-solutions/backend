package com.zifiq.showroom.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
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
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.customers.spi.TemporaryCustomers
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.PriceListRepository
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.StockLocations
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.showroom.dto.ReasonTotal
import com.zifiq.showroom.dto.ReturnCustomerView
import com.zifiq.showroom.dto.ReturnLineRow
import com.zifiq.showroom.dto.ReturnLineView
import com.zifiq.showroom.dto.ReturnRequest
import com.zifiq.showroom.dto.ReturnSummary
import com.zifiq.showroom.model.CustomerReturnLinesTable
import com.zifiq.showroom.model.CustomerReturnsTable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Customer returns (docs/05 §4.8–§4.9). The full value is credited to the customer's wallet at the
 * price the customer type pays today (dealers the dealer price, everyone else retail); resaleable
 * goods go back into stock, quarantined goods into a quarantine area that is never sold from, and
 * damaged goods are written off. A walk-in with no record gets a temporary customer whose wallet
 * disappears once spent (PRD rule 10, docs/04).
 */
@Service
class CustomerReturnService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
    private val sequences: DocumentSequenceService,
    private val ledger: StockLedgerRepository,
    private val prices: PriceListRepository,
    private val stockLocations: StockLocations,
    private val hierarchy: LocationHierarchy,
    private val register: ShowroomRegisterService,
    private val customers: CustomerDirectory,
    private val temporaryCustomers: TemporaryCustomers,
    private val wallets: WalletLedger,
    private val domainEvents: DomainEvents,
    private val notify: Notify,
) {
    private fun today(): LocalDate = LocalDate.now(moduleSettings.zone())

    fun post(request: ReturnRequest): ReturnSummary = tx.execute {
        val actor = currentActorProvider.current()
        val showroomId = request.locationId!!
        val showroom = register.showroomRow(showroomId, forWrite = true)
        stockLocations.requireTrading(showroomId)
        val returnDate = request.returnDate ?: today()
        if (returnDate.isAfter(today())) throw ApiErrors.validationFailed(mapOf("returnDate" to "cannot be in the future"))

        val customer = resolveCustomer(request, showroomId)
        val dealer = customer.type == CustomerType.AUTHORISED_DEALER
        if (customer.status !in setOf(CustomerStatus.ACTIVE, CustomerStatus.WATCH)) throw ApiErrors.customerNotActive(customer.status.name)

        val items = StockItemsTable.selectAll()
            .where { (StockItemsTable.tenantId eq actor.tenantId) and (StockItemsTable.id inList request.lines.map { it.stockItemId!! }) }
            .associateBy { it[StockItemsTable.id].value }
        val defaultInto = showroom[LocationsTable.defaultReceivingLocationId] ?: showroomId
        var quarantine: UUID? = null
        val lines = request.lines.mapIndexed { index, line ->
            val item = items[line.stockItemId!!] ?: throw ApiErrors.stockItemNotFound()
            val label = "${item[StockItemsTable.code]} ${item[StockItemsTable.name]}"
            if (item[StockItemsTable.status] != "ACTIVE") throw ApiErrors.stockItemSuspended()
            if (item[StockItemsTable.itemType] == "DIRECT_ENTRY" && item[StockItemsTable.locationId] != showroomId) throw ApiErrors.itemLocationRestricted()
            val price = prices.currentPrice(line.stockItemId, returnDate) ?: throw ApiErrors.priceNotSet(label)
            val unitPrice = price.priceFor(dealer)
            val into = when (line.condition!!) {
                "RESALEABLE" -> (line.intoLocationId ?: defaultInto).also { stockLocations.requireWithin(showroomId, it, "lines[$index].intoLocationId") }
                "QUARANTINE" -> quarantine ?: register.quarantineArea(showroomId).also { quarantine = it }
                else -> null
            }
            PreparedLine(index + 1, line.stockItemId, line.quantity!!, unitPrice, (unitPrice * line.quantity).setScale(2, RoundingMode.HALF_UP),
                line.reason!!, line.condition, into, line.note?.trim()?.ifEmpty { null })
        }
        val total = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.lineValue }
        val writeOff = lines.filter { it.condition == "DAMAGED_WRITE_OFF" }.fold(BigDecimal.ZERO) { sum, line -> sum + line.lineValue }
        if (total.signum() <= 0) throw ApiErrors.validationFailed(mapOf("lines" to "The returned goods have no value to credit (their price is zero)"))

        val id = UUID.randomUUID()
        val ref = sequences.next("RT")
        // Every line is credited, write-offs included: the customer is compensated for all goods returned.
        val credit = wallets.credit(
            LedgerPosting(
                customerId = customer.id, balanceType = BalanceType.WALLET, entryType = LedgerEntryType.RETURN_CREDIT, amount = total,
                reference = ref, description = "Return $ref at ${showroom[LocationsTable.name]}", paymentMethod = "Wallet credit",
                sourceModule = SOURCE_MODULE, sourceType = "CUSTOMER_RETURN", sourceId = id, locationId = showroomId,
            ),
        )
        CustomerReturnsTable.insert {
            it[CustomerReturnsTable.id] = id
            it[tenantId] = actor.tenantId
            it[CustomerReturnsTable.ref] = ref
            it[returnType] = request.returnType!!
            it[locationId] = showroomId
            it[customerId] = customer.id
            it[customerCode] = customer.code
            it[customerName] = customer.name
            it[customerType] = customer.type.name
            it[customerPhone] = customer.phone
            it[temporaryCustomer] = customer.isTemporary
            it[originalDocumentRef] = request.originalDocumentRef?.trim()?.ifEmpty { null }
            it[CustomerReturnsTable.returnDate] = returnDate
            it[totalValue] = total
            it[writeOffValue] = writeOff
            it[walletEntryId] = credit.id
            it[walletBalanceBefore] = credit.balanceAfter - credit.amount
            it[walletBalanceAfter] = credit.balanceAfter
            it[note] = request.note?.trim()?.ifEmpty { null }
            it[status] = "POSTED"
            it[createdByUserId] = actor.userId
            it[createdAt] = Instant.now()
        }
        lines.forEach { line ->
            CustomerReturnLinesTable.insert {
                it[tenantId] = actor.tenantId
                it[returnId] = id
                it[lineNo] = line.lineNo
                it[stockItemId] = line.stockItemId
                it[quantity] = line.quantity
                it[unitPrice] = line.unitPrice
                it[lineValue] = line.lineValue
                it[reason] = line.reason
                it[condition] = line.condition
                it[intoLocationId] = line.into
                it[note] = line.note
            }
            line.into?.let { into ->
                ledger.postEntry(actor.tenantId, into, line.stockItemId, "CUSTOMER_RETURN", ref, qtyIn = line.quantity,
                    remark = "Returned by ${customer.name} (${line.reason.lowercase().replace('_', ' ')})".take(300),
                    performedByUserId = actor.userId, entryDate = returnDate)
            }
        }

        domainEvents.record(
            type = "showroom.return.posted", aggregateType = "customer-return", aggregateId = id, aggregateRef = ref,
            summary = "$ref: ${naira(total)} credited to ${customer.code} at ${showroom[LocationsTable.name]}" +
                if (writeOff.signum() > 0) " (${naira(writeOff)} written off)" else "",
            payload = mapOf("returnType" to request.returnType, "lines" to lines.size, "totalValue" to total, "writeOffValue" to writeOff),
            locationId = showroomId,
        )
        if (writeOff.signum() > 0) {
            notify.holdersOf(
                permission = "showroom.returns.read", locationId = showroomId, category = NotificationCategory.ALERT,
                title = "Goods written off — $ref", body = "$ref: ${naira(writeOff)} written off at ${showroom[LocationsTable.name]}.",
                linkType = "showroom.return", linkRef = id.toString(), exclude = actor.userId,
            )
        }
        summary(id)
    }

    /** DEALER: an Authorised Dealer. NON_DEALER: a registered non-dealer, or the walk-in's (temporary) record by phone. */
    private fun resolveCustomer(request: ReturnRequest, showroomId: UUID): CustomerRef {
        if (request.returnType == "DEALER") {
            val customer = customers.get(request.customerId ?: throw ApiErrors.validationFailed(mapOf("customerId" to "is required for a dealer return")))
            if (customer.type != CustomerType.AUTHORISED_DEALER) throw ApiErrors.notADealer()
            return customer
        }
        if (request.customerId != null) {
            val customer = customers.get(request.customerId)
            if (customer.type == CustomerType.AUTHORISED_DEALER) throw ApiErrors.useDealerReturn()
            return customer
        }
        val walkIn = request.walkIn ?: throw ApiErrors.validationFailed(mapOf("walkIn" to "Give the customer, or the walk-in's name and phone"))
        return temporaryCustomers.findOrCreate(walkIn.name.trim(), walkIn.phone.trim(), showroomId)
    }

    private data class PreparedLine(
        val lineNo: Int,
        val stockItemId: UUID,
        val quantity: BigDecimal,
        val unitPrice: BigDecimal,
        val lineValue: BigDecimal,
        val reason: String,
        val condition: String,
        val into: UUID?,
        val note: String?,
    )

    /**
     * Undoes a return while that's still possible: the wallet must still hold the credit (otherwise
     * 400 INSUFFICIENT_WALLET_BALANCE) and the returned goods must still be where they went.
     */
    fun reverse(id: UUID, reason: String): ReturnSummary = tx.execute {
        val actor = currentActorProvider.current()
        if (!permissionAuthorizer.hasPermission(actor.userId, "customers.wallet.adjust")) throw ApiErrors.accessDenied()
        val header = CustomerReturnsTable.selectAll()
            .where { (CustomerReturnsTable.id eq id) and (CustomerReturnsTable.tenantId eq actor.tenantId) }
            .forUpdate().firstOrNull() ?: throw ApiErrors.returnNotFound()
        locationAccess.assertAccess(header[CustomerReturnsTable.locationId])
        if (header[CustomerReturnsTable.status] != "POSTED") throw ApiErrors.returnAlreadyReversed()

        val lines = CustomerReturnLinesTable.selectAll().where { CustomerReturnLinesTable.returnId eq id }.toList()
        lines.filter { it[CustomerReturnLinesTable.intoLocationId] != null }
            .groupBy({ it[CustomerReturnLinesTable.intoLocationId]!! to it[CustomerReturnLinesTable.stockItemId] }, { it[CustomerReturnLinesTable.quantity] })
            .forEach { (key, quantities) ->
                val (location, itemId) = key
                if (ledger.lockedBalance(actor.tenantId, location, itemId) < quantities.fold(BigDecimal.ZERO, BigDecimal::add)) {
                    throw ApiErrors.insufficientStockForTransfer(itemId)
                }
            }
        val trimmed = reason.trim()
        wallets.reverse(header[CustomerReturnsTable.walletEntryId], "Return ${header[CustomerReturnsTable.ref]} reversed: $trimmed", SOURCE_MODULE)
        val ref = header[CustomerReturnsTable.ref]
        lines.forEach { line ->
            line[CustomerReturnLinesTable.intoLocationId]?.let { location ->
                ledger.postEntry(actor.tenantId, location, line[CustomerReturnLinesTable.stockItemId], "CUSTOMER_RETURN", ref,
                    qtyOut = line[CustomerReturnLinesTable.quantity], remark = "Return reversed: $trimmed".take(300), performedByUserId = actor.userId)
            }
        }
        CustomerReturnsTable.update({ CustomerReturnsTable.id eq id }) {
            it[status] = "REVERSED"
            it[reversedReason] = trimmed.take(300)
            it[reversedByUserId] = actor.userId
            it[reversedAt] = Instant.now()
        }
        domainEvents.record(
            type = "showroom.return.reversed", aggregateType = "customer-return", aggregateId = id, aggregateRef = ref,
            summary = "$ref reversed: $trimmed", payload = mapOf("totalValue" to header[CustomerReturnsTable.totalValue]),
            locationId = header[CustomerReturnsTable.locationId],
        )
        summary(id)
    }

    fun get(id: UUID): ReturnSummary = tx.execute {
        val header = CustomerReturnsTable.selectAll()
            .where { (CustomerReturnsTable.id eq id) and (CustomerReturnsTable.tenantId eq tenantContext.requireTenantId()) }
            .firstOrNull() ?: throw ApiErrors.returnNotFound()
        locationAccess.allowedLocationIds()?.let { if (header[CustomerReturnsTable.locationId] !in it) throw ApiErrors.returnNotFound() }
        summary(id)
    }

    private fun summary(id: UUID): ReturnSummary {
        val header = CustomerReturnsTable.selectAll().where { CustomerReturnsTable.id eq id }.single()
        val unit = RawExpression<String>({ +"(select u.name from units_of_measure u where u.id = "; +StockItemsTable.unitOfMeasureId; +")" })
        val lines = CustomerReturnLinesTable
            .join(StockItemsTable, JoinType.INNER, CustomerReturnLinesTable.stockItemId, StockItemsTable.id)
            .select(CustomerReturnLinesTable.columns + StockItemsTable.code + StockItemsTable.name + unit)
            .where { CustomerReturnLinesTable.returnId eq id }
            .orderBy(CustomerReturnLinesTable.lineNo to SortOrder.ASC)
            .map {
                val into = it[CustomerReturnLinesTable.intoLocationId]
                ReturnLineView(
                    lineNo = it[CustomerReturnLinesTable.lineNo], stockItemId = it[CustomerReturnLinesTable.stockItemId], code = it[StockItemsTable.code],
                    name = it[StockItemsTable.name], quantity = it[CustomerReturnLinesTable.quantity], unit = it.getOrNull(unit),
                    unitPrice = it[CustomerReturnLinesTable.unitPrice], lineValue = it[CustomerReturnLinesTable.lineValue],
                    reason = it[CustomerReturnLinesTable.reason], condition = it[CustomerReturnLinesTable.condition],
                    intoLocationId = into, intoLocationName = into?.let { location -> stockLocations.get(location).label }, note = it[CustomerReturnLinesTable.note],
                )
            }
        val userId = header[CustomerReturnsTable.createdByUserId].value
        return ReturnSummary(
            id = id, ref = header[CustomerReturnsTable.ref], returnType = header[CustomerReturnsTable.returnType],
            locationId = header[CustomerReturnsTable.locationId], locationName = stockLocations.get(header[CustomerReturnsTable.locationId]).name,
            customer = ReturnCustomerView(header[CustomerReturnsTable.customerId], header[CustomerReturnsTable.customerCode], header[CustomerReturnsTable.customerName],
                header[CustomerReturnsTable.customerType], header[CustomerReturnsTable.temporaryCustomer]),
            originalDocumentRef = header[CustomerReturnsTable.originalDocumentRef], returnDate = header[CustomerReturnsTable.returnDate],
            note = header[CustomerReturnsTable.note], lines = lines, totalValue = header[CustomerReturnsTable.totalValue],
            writeOffValue = header[CustomerReturnsTable.writeOffValue], walletBalanceBefore = header[CustomerReturnsTable.walletBalanceBefore],
            walletCredit = header[CustomerReturnsTable.totalValue], walletBalanceAfter = header[CustomerReturnsTable.walletBalanceAfter],
            status = header[CustomerReturnsTable.status], reversedReason = header[CustomerReturnsTable.reversedReason], reversedAt = header[CustomerReturnsTable.reversedAt],
            processedByUserId = userId,
            processedByName = AppUsersTable.select(AppUsersTable.displayName).where { AppUsersTable.id eq userId }.firstOrNull()?.get(AppUsersTable.displayName),
            createdAt = header[CustomerReturnsTable.createdAt],
        )
    }

    // ---- Return Reports (one row per line) ----

    private val location = LocationsTable.alias("return_location")
    private val creditedTo = RawExpression<String>({
        +"(CASE WHEN "; +CustomerReturnsTable.returnType; +" = 'DEALER' THEN 'Dealer wallet' WHEN "; +CustomerReturnsTable.temporaryCustomer
        +" THEN 'Temporary wallet' ELSE 'Customer wallet' END)"
    })

    private fun joined() = CustomerReturnLinesTable
        .join(CustomerReturnsTable, JoinType.INNER, CustomerReturnLinesTable.returnId, CustomerReturnsTable.id)
        .join(StockItemsTable, JoinType.INNER, CustomerReturnLinesTable.stockItemId, StockItemsTable.id)
        .join(location, JoinType.INNER, CustomerReturnsTable.locationId, location[LocationsTable.id])

    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("locationId", "Showroom", FilterType.LOCATION, CustomerReturnsTable.locationId, aliases = setOf("showroom"), facetable = true),
            FilterField("returnType", "Return type", FilterType.ENUM, CustomerReturnsTable.returnType, listOf(FilterOption("DEALER", "Dealer"), FilterOption("NON_DEALER", "Non-dealer")), aliases = setOf("type"), facetable = true),
            FilterField("condition", "Condition", FilterType.ENUM, CustomerReturnLinesTable.condition,
                listOf(FilterOption("RESALEABLE", "Resaleable"), FilterOption("QUARANTINE", "Quarantine"), FilterOption("DAMAGED_WRITE_OFF", "Damaged / write-off")), facetable = true),
            FilterField("reason", "Reason", FilterType.ENUM, CustomerReturnLinesTable.reason,
                listOf("DAMAGED_IN_TRANSIT", "EXPIRED_OR_NEAR_EXPIRY", "WRONG_ITEM_SUPPLIED", "PRODUCT_DEFECT", "UNSOLD_AGREED_RETURN", "CUSTOMER_DISSATISFACTION")
                    .map { FilterOption(it, it.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)) }, facetable = true),
            FilterField("customerId", "Customer", FilterType.UUID, CustomerReturnsTable.customerId, aliases = setOf("customer"), lookup = "/api/v1/customers/lookup"),
            FilterField("customerType", "Customer type", FilterType.ENUM, CustomerReturnsTable.customerType),
            FilterField("status", "Status", FilterType.ENUM, CustomerReturnsTable.status, listOf(FilterOption("POSTED", "Posted"), FilterOption("REVERSED", "Reversed"))),
            FilterField("stockItemId", "Product", FilterType.UUID, CustomerReturnLinesTable.stockItemId, aliases = setOf("item")),
            FilterField("lineValue", "Value", FilterType.MONEY, CustomerReturnLinesTable.lineValue, sortable = true),
            FilterField("creditedTo", "Credited to", FilterType.ENUM, creditedTo,
                listOf(FilterOption("Dealer wallet", "Dealer wallet"), FilterOption("Temporary wallet", "Temporary wallet"), FilterOption("Customer wallet", "Customer wallet"))),
            FilterField("returnDate", "Date", FilterType.DATE, CustomerReturnsTable.returnDate, sortable = true),
            FilterField("createdAt", "Recorded", FilterType.DATETIME, CustomerReturnsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "returnDate",
        defaultSort = listOf("returnDate" to SortOrder.DESC, "createdAt" to SortOrder.DESC),
        tiebreaker = CustomerReturnLinesTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(CustomerReturnsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(CustomerReturnsTable.originalDocumentRef, SearchKind.REFERENCE, "Original document"),
            SearchField(CustomerReturnsTable.customerCode, SearchKind.REFERENCE, "Customer code"),
            SearchField(CustomerReturnsTable.customerName, SearchKind.TRIGRAM, "Customer"),
            SearchField(CustomerReturnsTable.customerPhone, SearchKind.PHONE, "Phone"),
            SearchField(StockItemsTable.name, SearchKind.TRIGRAM, "Product"),
            SearchField(RawExpression<String>({ +"customer_returns.search_vector" }), SearchKind.FULLTEXT, "Note"),
        ),
        qualifiers = filterSpec,
    )

    private fun context() = FilterContext(costVisible = true, zone = moduleSettings.zone(), expandLocations = hierarchy::withAreas)

    fun filters(): FilterMetadata = tx.execute { filterSpec.metadata(context()) }

    /** Return Reports: posted returns unless `status` is filtered; summary totals plus a by-reason breakdown. */
    fun list(request: ListSearchRequest): PageResult<ReturnLineRow> = tx.execute {
        val context = context()
        val parsed = searchSpec.parse(request.q)
        val params = if ("status" in request.params || request.q?.contains("status:", ignoreCase = true) == true) request.params
        else request.params + ("status" to listOf("POSTED"))
        val parts = mutableListOf<Op<Boolean>>(CustomerReturnsTable.tenantId eq tenantContext.requireTenantId())
        locationAccess.allowedLocationIds()?.let { parts += CustomerReturnsTable.locationId inList it }
        searchSpec.condition(parsed)?.let { parts += it }
        filterSpec.fromParams(params, context)?.let { parts += it }
        filterSpec.fromJson(request.filter, context)?.let { parts += it }
        val condition = parts.allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) searchSpec.rank(parsed) + filterSpec.sort(emptyList(), context) else filterSpec.sort(request.sortParams, context)
        val base = joined()
        val query = base.select(base.columns + creditedTo).where(condition).orderBy(*sort.toTypedArray())
        val page = Paging.fetch(query, PageRequest.of(request.page, request.size), ::row)

        val summary = if (request.summary) {
            val returns = RawExpression<Long>({ +"count(distinct "; +CustomerReturnsTable.id; +")" })
            val units = RawExpression<BigDecimal>({ +"coalesce(sum("; +CustomerReturnLinesTable.quantity; +"), 0)" })
            fun valueWhere(condition: String?) = RawExpression<BigDecimal>({
                +"coalesce(sum("; +CustomerReturnLinesTable.lineValue; +")"
                if (condition != null) { +" filter (where "; +CustomerReturnLinesTable.condition; +" = '$condition')" }
                +", 0)"
            })
            val total = valueWhere(null)
            val resaleable = valueWhere("RESALEABLE")
            val quarantined = valueWhere("QUARANTINE")
            val writtenOff = valueWhere("DAMAGED_WRITE_OFF")
            val totals = base.select(returns, units, total, resaleable, quarantined, writtenOff).where(condition).single()
            val lineCount = RawExpression<Long>({ +"count(*)" })
            val byReason = base.select(CustomerReturnLinesTable.reason, lineCount, units, total).where(condition)
                .groupBy(CustomerReturnLinesTable.reason)
                .map { ReasonTotal(it[CustomerReturnLinesTable.reason], (it[lineCount] as Number).toLong(), BigDecimal(it[units].toString()), BigDecimal(it[total].toString())) }
                .sortedByDescending { it.value }
            fun decimal(expression: RawExpression<BigDecimal>) = BigDecimal(totals[expression].toString())
            mapOf(
                "returns" to (totals[returns] as Number).toLong(), "units" to decimal(units), "valueCredited" to decimal(total),
                "resaleableValue" to decimal(resaleable), "quarantineValue" to decimal(quarantined),
                "writeOffValue" to decimal(writtenOff), "byReason" to byReason,
            )
        } else null
        page.copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    private fun row(it: ResultRow) = ReturnLineRow(
        returnId = it[CustomerReturnsTable.id].value, lineId = it[CustomerReturnLinesTable.id].value, date = it[CustomerReturnsTable.returnDate],
        ref = it[CustomerReturnsTable.ref], returnType = it[CustomerReturnsTable.returnType], originalDocumentRef = it[CustomerReturnsTable.originalDocumentRef],
        customerId = it[CustomerReturnsTable.customerId], customerCode = it[CustomerReturnsTable.customerCode], customerName = it[CustomerReturnsTable.customerName],
        customerType = it[CustomerReturnsTable.customerType], productCode = it[StockItemsTable.code], productName = it[StockItemsTable.name],
        quantity = it[CustomerReturnLinesTable.quantity], unitPrice = it[CustomerReturnLinesTable.unitPrice], lineValue = it[CustomerReturnLinesTable.lineValue],
        reason = it[CustomerReturnLinesTable.reason], condition = it[CustomerReturnLinesTable.condition], locationId = it[CustomerReturnsTable.locationId],
        locationName = it[location[LocationsTable.name]], creditedTo = it[creditedTo], status = it[CustomerReturnsTable.status],
    )

    private fun naira(amount: BigDecimal) = "₦" + NumberFormat.getNumberInstance(Locale.UK).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(amount)

    companion object {
        const val SOURCE_MODULE = "showroom"
    }
}
