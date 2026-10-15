package com.zifiq.showroom.service

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.common.money.Money
import com.zifiq.inventory.repository.StockSql
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
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
import com.zifiq.common.tax.TaxClassificationRepository
import com.zifiq.inventory.model.StockItemPricesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.PriceListRepository
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.StockLocations
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.showroom.dto.DirectEntryRequest
import com.zifiq.showroom.dto.OutsourceReceiptRequest
import com.zifiq.showroom.dto.StockEntryLineRow
import com.zifiq.showroom.dto.StockEntryLineView
import com.zifiq.showroom.dto.StockEntrySummary
import com.zifiq.showroom.model.ShowroomStockEntriesTable
import com.zifiq.showroom.model.ShowroomStockEntryLinesTable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
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
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Stock Entry (Direct) and Outsource Stock (docs/05 §4.4–§4.5): goods that reach a showroom from
 * outside the company's own factories. Direct-entry goods become DIRECT_ENTRY items held only at
 * that showroom, priced as they're entered; outsourced goods must be registered OUTSOURCED products
 * and are costed at their registered landing cost.
 */
@Service
class StockIntakeService(
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
    private val itemCodes: com.zifiq.inventory.service.StockItemCodes,
    private val hierarchy: LocationHierarchy,
    private val register: ShowroomRegisterService,
    private val names: ReferenceNames,
    private val taxes: TaxClassificationRepository,
    private val domainEvents: DomainEvents,
) {
    private fun today(): LocalDate = LocalDate.now(moduleSettings.zone())

    private fun costVisible() = permissionAuthorizer.hasPermission(currentActorProvider.current().userId, "factory.costing.read")


    /** The showroom (must be able to receive) and where the goods go: an area of it, or its receiving default. */
    private fun receivingPoint(locationId: UUID, intoLocationId: UUID?): Pair<ResultRow, UUID> {
        val showroom = register.showroomRow(locationId, forWrite = true)
        stockLocations.requireCanReceive(locationId)
        val into = intoLocationId ?: showroom[LocationsTable.defaultReceivingLocationId] ?: locationId
        stockLocations.requireWithin(locationId, into, "intoLocationId")
        stockLocations.requireCanReceive(into)
        return showroom to into
    }

    private fun requireReceivedDate(date: LocalDate) {
        if (date.isAfter(today())) throw ApiErrors.validationFailed(mapOf("receivedDate" to "cannot be in the future"))
    }

    // ---- Direct entry ----

    fun directEntry(request: DirectEntryRequest): StockEntrySummary = tx.execute {
        val actor = currentActorProvider.current()
        val (showroom, into) = receivingPoint(request.locationId!!, request.intoLocationId)
        val showroomId = showroom[LocationsTable.id].value
        val receivedDate = request.receivedDate!!
        requireReceivedDate(receivedDate)

        val supplierName = when {
            request.supplierId != null -> names.activeSupplierName(request.supplierId) ?: throw ApiErrors.validationFailed(mapOf("supplierId" to "Supplier not found or inactive"))
            !request.supplierName.isNullOrBlank() -> request.supplierName.trim()
            else -> throw ApiErrors.validationFailed(mapOf("supplierName" to "Give a registered supplier or the supplier's name"))
        }
        val vatCategoryId = request.vatCategoryId ?: names.activeStandardVatCategory()
            ?: throw ApiErrors.validationFailed(mapOf("vatCategoryId" to "Activate the standard VAT category (VAT Control) or choose one"))
        if (!taxes.isVatCategoryActiveForTenant(actor.tenantId, vatCategoryId)) throw ApiErrors.vatCategoryNotActive()
        request.lines.forEachIndexed { index, line ->
            names.unitName(line.unitOfMeasureId!!) ?: throw ApiErrors.validationFailed(mapOf("lines[$index].unitOfMeasureId" to "Unit not found"))
            val dealer = line.dealerPrice ?: line.sellingPrice!!
            if (dealer > line.sellingPrice!!) throw ApiErrors.dealerPriceAboveRetail(line.description.trim())
            if (line.expiryDate != null && !line.expiryDate.isAfter(receivedDate)) throw ApiErrors.invalidExpiry()
        }

        val ref = sequences.next("DE")
        val priceDate = maxOf(receivedDate, today())
        var priceBatch: String? = null
        val warnings = mutableListOf<String>()
        val now = Instant.now()
        val prepared = request.lines.mapIndexed { index, line ->
            val description = line.description.trim()
            val itemId = findOrCreateDirectItem(showroomId, description, line.unitOfMeasureId!!, line.unitCost!!, vatCategoryId, actor.userId)
            StockItemsTable.update({ StockItemsTable.id eq itemId }) { it[landingCost] = Money.round(line.unitCost) } // latest-cost method

            val retail = Money.round(line.sellingPrice!!)
            val dealer = Money.round(line.dealerPrice ?: line.sellingPrice)
            val current = prices.currentPrice(itemId, priceDate)
            if (current == null || current.retailPrice.compareTo(retail) != 0 || current.dealerPrice.compareTo(dealer) != 0) {
                if (current != null && current.effectiveFrom == priceDate) {
                    warnings += "$description: already priced for $priceDate (${current.batchRef}); price unchanged — publish a new price in the price list"
                } else {
                    val batch = priceBatch ?: sequences.next("PL").also { priceBatch = it }
                    StockItemPricesTable.insert {
                        it[tenantId] = actor.tenantId
                        it[stockItemId] = itemId
                        it[retailPrice] = retail
                        it[dealerPrice] = dealer
                        it[effectiveFrom] = priceDate
                        it[batchRef] = batch
                        it[note] = "Direct entry $ref"
                        it[setByUserId] = actor.userId
                        it[createdAt] = now
                    }
                }
            }
            Triple(index, line, itemId)
        }

        val entryId = insertEntry(
            ref = ref, kind = "DIRECT_ENTRY", showroomId = showroomId, into = into, receivedDate = receivedDate,
            supplierId = request.supplierId, supplierName = if (request.supplierId == null) supplierName else null, manufacturerId = null,
            partnerReference = request.supplierInvoiceNo, paymentTerms = request.paymentTerms, checkedBy = null,
            lines = prepared.map { (_, line, itemId) ->
                EntryLine(itemId, line.description.trim(), line.quantity!!, line.unitOfMeasureId!!, line.unitCost!!, line.inputVatRate ?: BigDecimal.ZERO,
                    line.batchNo, line.expiryDate, Money.round(line.sellingPrice!!))
            },
        )
        prepared.forEach { (_, line, itemId) ->
            ledger.postEntry(
                tenantId = actor.tenantId, locationId = into, stockItemId = itemId, sourceType = "DIRECT_ENTRY", sourceReference = ref,
                qtyIn = line.quantity!!, unitCost = Money.round(line.unitCost!!), remark = "Direct entry — $supplierName".take(300),
                performedByUserId = actor.userId, entryDate = receivedDate,
            )
        }
        domainEvents.record(
            type = "showroom.direct-entry.posted", aggregateType = "stock-entry", aggregateId = entryId, aggregateRef = ref,
            summary = "$ref: ${request.lines.size} line(s) from $supplierName received at ${showroom[LocationsTable.name]}",
            payload = mapOf("lines" to request.lines.size, "priceBatch" to priceBatch), locationId = showroomId,
        )
        summary(entryId, warnings)
    }

    /** One DIRECT_ENTRY item per (showroom, name, unit): restocking the same goods adds to the same item. */
    private fun findOrCreateDirectItem(showroomId: UUID, description: String, unitId: UUID, unitCost: BigDecimal, vatCategoryId: UUID, userId: UUID): UUID {
        val existing = StockItemsTable.select(StockItemsTable.id).where {
            (StockItemsTable.tenantId eq tenantContext.requireTenantId()) and (StockItemsTable.itemType eq "DIRECT_ENTRY") and
                (StockItemsTable.locationId eq showroomId) and (StockItemsTable.name.lowerCase() eq description.lowercase()) and
                (StockItemsTable.unitOfMeasureId eq unitId)
        }.firstOrNull()?.get(StockItemsTable.id)?.value
        if (existing != null) return existing
        val code = itemCodes.next()
        return StockItemsTable.insertAndGetId {
            it[tenantId] = tenantContext.requireTenantId()
            it[locationId] = showroomId
            it[itemType] = "DIRECT_ENTRY"
            it[StockItemsTable.code] = code
            it[name] = description
            it[unitOfMeasureId] = unitId
            it[reorderLevel] = BigDecimal.ZERO
            it[landingCost] = Money.round(unitCost)
            it[StockItemsTable.vatCategoryId] = vatCategoryId
            it[status] = "ACTIVE"
            it[createdByUserId] = userId
            it[createdAt] = Instant.now()
        }.value
    }

    // ---- Outsource receipt ----

    fun outsourceReceipt(request: OutsourceReceiptRequest): StockEntrySummary = tx.execute {
        val actor = currentActorProvider.current()
        val (showroom, into) = receivingPoint(request.locationId!!, request.intoLocationId)
        val showroomId = showroom[LocationsTable.id].value
        val receivedDate = request.receivedDate!!
        requireReceivedDate(receivedDate)
        val partner = names.manufacturerName(request.manufacturerId!!) ?: throw ApiErrors.validationFailed(mapOf("manufacturerId" to "Outsource partner not found"))

        val items = StockItemsTable.selectAll()
            .where { (StockItemsTable.tenantId eq actor.tenantId) and (StockItemsTable.id inList request.lines.map { it.stockItemId!! }) }
            .associateBy { it[StockItemsTable.id].value }
        val lines = request.lines.map { line ->
            val item = items[line.stockItemId!!] ?: throw ApiErrors.stockItemNotFound()
            if (item[StockItemsTable.itemType] != "OUTSOURCED") throw ApiErrors.itemNotOutsourced()
            if (item[StockItemsTable.status] != "ACTIVE") throw ApiErrors.stockItemSuspended()
            val cost = item[StockItemsTable.landingCost] ?: throw ApiErrors.outsourceCostNotSet()
            if (line.expiryDate != null && !line.expiryDate.isAfter(receivedDate)) throw ApiErrors.invalidExpiry()
            EntryLine(line.stockItemId, item[StockItemsTable.name], line.quantity!!, item[StockItemsTable.unitOfMeasureId], cost,
                line.inputVatRate ?: BigDecimal.ZERO, line.batchNo, line.expiryDate, null)
        }

        val ref = sequences.next("OS")
        val entryId = insertEntry(
            ref = ref, kind = "OUTSOURCE_RECEIPT", showroomId = showroomId, into = into, receivedDate = receivedDate,
            supplierId = null, supplierName = null, manufacturerId = request.manufacturerId, partnerReference = request.partnerReference,
            paymentTerms = null, checkedBy = request.checkedBy, lines = lines,
        )
        lines.forEach { line ->
            ledger.postEntry(
                tenantId = actor.tenantId, locationId = into, stockItemId = line.stockItemId, sourceType = "OUTSOURCE_RECEIPT", sourceReference = ref,
                qtyIn = line.quantity, unitCost = Money.round(line.unitCost), remark = "Outsource receipt — $partner".take(300),
                performedByUserId = actor.userId, entryDate = receivedDate,
            )
        }
        domainEvents.record(
            type = "showroom.outsource-receipt.posted", aggregateType = "stock-entry", aggregateId = entryId, aggregateRef = ref,
            summary = "$ref: ${lines.size} outsourced line(s) from $partner received at ${showroom[LocationsTable.name]}",
            payload = mapOf("lines" to lines.size), locationId = showroomId,
        )
        summary(entryId, emptyList())
    }

    // ---- Shared ----

    private data class EntryLine(
        val stockItemId: UUID,
        val description: String,
        val quantity: BigDecimal,
        val unitId: UUID,
        val unitCost: BigDecimal,
        val inputVatRate: BigDecimal,
        val batchNo: String?,
        val expiryDate: LocalDate?,
        val sellingPrice: BigDecimal?,
    )

    private fun insertEntry(
        ref: String, kind: String, showroomId: UUID, into: UUID, receivedDate: LocalDate, supplierId: UUID?, supplierName: String?,
        manufacturerId: UUID?, partnerReference: String?, paymentTerms: String?, checkedBy: String?, lines: List<EntryLine>,
    ): UUID {
        val tenantId = tenantContext.requireTenantId()
        val lineCosts = lines.map { Money.round(it.unitCost * it.quantity) }
        val vat = lines.zip(lineCosts) { line, cost -> Money.round(cost * line.inputVatRate / BigDecimal(100)) }
        val entryId = ShowroomStockEntriesTable.insertAndGetId {
            it[ShowroomStockEntriesTable.tenantId] = tenantId
            it[ShowroomStockEntriesTable.ref] = ref
            it[ShowroomStockEntriesTable.kind] = kind
            it[locationId] = showroomId
            it[intoLocationId] = into
            it[ShowroomStockEntriesTable.supplierId] = supplierId
            it[ShowroomStockEntriesTable.supplierName] = supplierName
            it[ShowroomStockEntriesTable.manufacturerId] = manufacturerId
            it[ShowroomStockEntriesTable.partnerReference] = partnerReference?.trim()?.ifEmpty { null }
            it[ShowroomStockEntriesTable.paymentTerms] = paymentTerms?.trim()?.ifEmpty { null }
            it[ShowroomStockEntriesTable.checkedBy] = checkedBy?.trim()?.ifEmpty { null }
            it[ShowroomStockEntriesTable.receivedDate] = receivedDate
            it[totalCost] = lineCosts.fold(BigDecimal.ZERO, BigDecimal::add)
            it[totalInputVat] = vat.fold(BigDecimal.ZERO, BigDecimal::add)
            it[createdByUserId] = currentActorProvider.current().userId
            it[createdAt] = Instant.now()
        }.value
        lines.forEachIndexed { index, line ->
            ShowroomStockEntryLinesTable.insert {
                it[ShowroomStockEntryLinesTable.tenantId] = tenantId
                it[ShowroomStockEntryLinesTable.entryId] = entryId
                it[lineNo] = index + 1
                it[stockItemId] = line.stockItemId
                it[description] = line.description.take(200)
                it[quantity] = line.quantity
                it[unitOfMeasureId] = line.unitId
                it[unitCost] = Money.round(line.unitCost)
                it[lineCost] = lineCosts[index]
                it[inputVatRate] = line.inputVatRate
                it[inputVatAmount] = vat[index]
                it[batchNo] = line.batchNo?.trim()?.ifEmpty { null }
                it[expiryDate] = line.expiryDate
                it[sellingPrice] = line.sellingPrice
            }
        }
        return entryId
    }

    private fun summary(entryId: UUID, warnings: List<String>): StockEntrySummary {
        val visible = costVisible()
        fun mask(value: BigDecimal) = if (visible) value else BigDecimal.ZERO.setScale(2)
        val entry = ShowroomStockEntriesTable.selectAll().where { ShowroomStockEntriesTable.id eq entryId }.single()
        val unit = StockSql.unitName(ShowroomStockEntryLinesTable.unitOfMeasureId)
        val lines = ShowroomStockEntryLinesTable
            .join(StockItemsTable, JoinType.INNER, ShowroomStockEntryLinesTable.stockItemId, StockItemsTable.id)
            .select(ShowroomStockEntryLinesTable.columns + StockItemsTable.code + unit)
            .where { ShowroomStockEntryLinesTable.entryId eq entryId }
            .orderBy(ShowroomStockEntryLinesTable.lineNo to SortOrder.ASC)
            .map {
                StockEntryLineView(
                    lineNo = it[ShowroomStockEntryLinesTable.lineNo], stockItem = Ref.stockItem(it[ShowroomStockEntryLinesTable.stockItemId]), code = it[StockItemsTable.code],
                    description = it[ShowroomStockEntryLinesTable.description], quantity = it[ShowroomStockEntryLinesTable.quantity], unit = it.getOrNull(unit),
                    unitCost = mask(it[ShowroomStockEntryLinesTable.unitCost]), lineCost = mask(it[ShowroomStockEntryLinesTable.lineCost]),
                    inputVatRate = it[ShowroomStockEntryLinesTable.inputVatRate], inputVatAmount = mask(it[ShowroomStockEntryLinesTable.inputVatAmount]),
                    sellingPrice = it[ShowroomStockEntryLinesTable.sellingPrice], batchNo = it[ShowroomStockEntryLinesTable.batchNo],
                    expiryDate = it[ShowroomStockEntryLinesTable.expiryDate],
                )
            }
        val location = stockLocations.get(entry[ShowroomStockEntriesTable.locationId])
        val into = stockLocations.get(entry[ShowroomStockEntriesTable.intoLocationId])
        val userId = entry[ShowroomStockEntriesTable.createdByUserId].value
        return StockEntrySummary(
            id = entryId, ref = entry[ShowroomStockEntriesTable.ref], kind = entry[ShowroomStockEntriesTable.kind],
            location = Ref.location(location.id), intoLocation = Ref.location(into.id),
            supplier = ref(entry[ShowroomStockEntriesTable.supplierId], Ref::supplier),
            manufacturer = ref(entry[ShowroomStockEntriesTable.manufacturerId], Ref::manufacturer),
            partnerName = entry[ShowroomStockEntriesTable.manufacturerId]?.let(names::manufacturerName),
            partnerReference = entry[ShowroomStockEntriesTable.partnerReference], paymentTerms = entry[ShowroomStockEntriesTable.paymentTerms],
            checkedBy = entry[ShowroomStockEntriesTable.checkedBy], receivedDate = entry[ShowroomStockEntriesTable.receivedDate], lines = lines,
            totalCost = mask(entry[ShowroomStockEntriesTable.totalCost]), totalInputVat = mask(entry[ShowroomStockEntriesTable.totalInputVat]),
            enteredBy = UserRef(userId),
            createdAt = entry[ShowroomStockEntriesTable.createdAt], costVisible = visible, warnings = warnings,
        )
    }

    fun get(entryId: UUID): StockEntrySummary = tx.execute {
        val entry = ShowroomStockEntriesTable.selectAll()
            .where { (ShowroomStockEntriesTable.id eq entryId) and (ShowroomStockEntriesTable.tenantId eq tenantContext.requireTenantId()) }
            .firstOrNull() ?: throw ApiErrors.stockEntryNotFound()
        locationAccess.allowedLocationIds()?.let { if (entry[ShowroomStockEntriesTable.locationId] !in it) throw ApiErrors.stockEntryNotFound() }
        summary(entryId, emptyList())
    }

    // ---- Intake reports (one row per line) ----

    private val location = LocationsTable.alias("entry_location")
    private val unitName = StockSql.unitName(ShowroomStockEntryLinesTable.unitOfMeasureId)
    private val partyName = RawExpression<String>({
        +"coalesce("; +ShowroomStockEntriesTable.supplierName
        +", (select s.company_name from suppliers s where s.id = "; +ShowroomStockEntriesTable.supplierId
        +"), (select m.name from manufacturers m where m.id = "; +ShowroomStockEntriesTable.manufacturerId; +"))"
    })

    private fun joined() = ShowroomStockEntryLinesTable
        .join(ShowroomStockEntriesTable, JoinType.INNER, ShowroomStockEntryLinesTable.entryId, ShowroomStockEntriesTable.id)
        .join(StockItemsTable, JoinType.INNER, ShowroomStockEntryLinesTable.stockItemId, StockItemsTable.id)
        .join(location, JoinType.INNER, ShowroomStockEntriesTable.locationId, location[LocationsTable.id])
        .join(AppUsersTable, JoinType.LEFT, ShowroomStockEntriesTable.createdByUserId, AppUsersTable.id)

    private fun filterSpec(kind: String) = FilterSpec(
        fields = listOfNotNull(
            FilterField("locationId", "Showroom", FilterType.LOCATION, ShowroomStockEntriesTable.locationId, aliases = setOf("showroom"), facetable = true),
            if (kind == "DIRECT_ENTRY") FilterField("supplierId", "Supplier", FilterType.UUID, ShowroomStockEntriesTable.supplierId, aliases = setOf("supplier"), facetable = true)
            else FilterField("manufacturerId", "Outsource partner", FilterType.UUID, ShowroomStockEntriesTable.manufacturerId, aliases = setOf("partner"), facetable = true),
            FilterField("stockItemId", "Product", FilterType.UUID, ShowroomStockEntryLinesTable.stockItemId, aliases = setOf("item")),
            FilterField("receivedDate", "Received", FilterType.DATE, ShowroomStockEntriesTable.receivedDate, sortable = true),
            FilterField("expiryDate", "Expiry", FilterType.DATE, ShowroomStockEntryLinesTable.expiryDate, sortable = true),
            FilterField("hasBatch", "Has batch number", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +ShowroomStockEntryLinesTable.batchNo; +" IS NOT NULL)" })),
            FilterField("inputVatRate", "Input VAT %", FilterType.NUMBER, ShowroomStockEntryLinesTable.inputVatRate),
            FilterField("quantity", "Quantity", FilterType.NUMBER, ShowroomStockEntryLinesTable.quantity, sortable = true),
            FilterField("lineCost", "Line cost", FilterType.MONEY, ShowroomStockEntryLinesTable.lineCost, sortable = true, cost = true),
            FilterField("enteredBy", "Entered by", FilterType.UUID, ShowroomStockEntriesTable.createdByUserId),
            FilterField("ref", "Reference", FilterType.REF, ShowroomStockEntriesTable.ref, sortable = true),
        ),
        primaryDateKey = "receivedDate",
        defaultSort = listOf("receivedDate" to SortOrder.DESC, "ref" to SortOrder.DESC),
        tiebreaker = ShowroomStockEntryLinesTable.id,
    )

    private fun searchSpec(filters: FilterSpec) = SearchSpec(
        fields = listOf(
            SearchField(ShowroomStockEntriesTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(ShowroomStockEntriesTable.partnerReference, SearchKind.CODE, "Invoice / waybill no."),
            SearchField(ShowroomStockEntryLinesTable.description, SearchKind.TRIGRAM, "Product"),
            SearchField(ShowroomStockEntriesTable.supplierName, SearchKind.TRIGRAM, "Supplier"),
            SearchField(ShowroomStockEntryLinesTable.batchNo, SearchKind.CODE, "Batch"),
        ),
        qualifiers = filters,
    )

    fun filters(kind: String): FilterMetadata = tx.execute { filterSpec(kind).metadata(FilterContext(costVisible(), moduleSettings.zone(), expandLocations = hierarchy::withAreas)) }

    fun list(kind: String, request: ListSearchRequest): PageResult<StockEntryLineRow> = tx.execute {
        val visible = costVisible()
        val context = FilterContext(visible, moduleSettings.zone(), expandLocations = hierarchy::withAreas)
        val filters = filterSpec(kind)
        val search = searchSpec(filters)
        val parsed = search.parse(request.q, visible)
        val parts = mutableListOf<Op<Boolean>>(
            ShowroomStockEntriesTable.tenantId eq tenantContext.requireTenantId(),
            ShowroomStockEntriesTable.kind eq kind,
        )
        locationAccess.allowedLocationIds()?.let { parts += ShowroomStockEntriesTable.locationId inList it }
        search.condition(parsed)?.let { parts += it }
        filters.fromParams(request.params, context)?.let { parts += it }
        filters.fromJson(request.filter, context)?.let { parts += it }
        val condition = parts.allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) search.rank(parsed) + filters.sort(emptyList(), context) else filters.sort(request.sortParams, context)
        val base = joined()
        val query = base.select(base.columns + unitName + partyName).where(condition).orderBy(*sort.toTypedArray())
        fun mask(value: BigDecimal) = if (visible) value else BigDecimal.ZERO.setScale(2)
        val page = Paging.fetch(query, PageRequest.of(request.page, request.size)) {
            StockEntryLineRow(
                entryId = it[ShowroomStockEntriesTable.id].value, lineId = it[ShowroomStockEntryLinesTable.id].value,
                date = it[ShowroomStockEntriesTable.receivedDate], ref = it[ShowroomStockEntriesTable.ref], kind = kind,
                location = Ref.location(it[ShowroomStockEntriesTable.locationId]),
                stockItem = Ref.stockItem(it[ShowroomStockEntryLinesTable.stockItemId]),
                product = it[ShowroomStockEntryLinesTable.description], party = it.getOrNull(partyName),
                partnerReference = it[ShowroomStockEntriesTable.partnerReference], batchNo = it[ShowroomStockEntryLinesTable.batchNo],
                expiryDate = it[ShowroomStockEntryLinesTable.expiryDate], quantity = it[ShowroomStockEntryLinesTable.quantity], unit = it.getOrNull(unitName),
                unitCost = mask(it[ShowroomStockEntryLinesTable.unitCost]), lineCost = mask(it[ShowroomStockEntryLinesTable.lineCost]),
                inputVatAmount = it[ShowroomStockEntryLinesTable.inputVatAmount], enteredBy = UserRef(it[ShowroomStockEntriesTable.createdByUserId].value),
            )
        }
        val summary = if (request.summary) {
            val entries = RawExpression<Long>({ +"count(distinct "; +ShowroomStockEntriesTable.id; +")" })
            val lines = RawExpression<Long>({ +"count(*)" })
            val units = RawExpression<BigDecimal>({ +"coalesce(sum("; +ShowroomStockEntryLinesTable.quantity; +"), 0)" })
            val cost = RawExpression<BigDecimal>({ +"coalesce(sum("; +ShowroomStockEntryLinesTable.lineCost; +"), 0)" })
            val vat = RawExpression<BigDecimal>({ +"coalesce(sum("; +ShowroomStockEntryLinesTable.inputVatAmount; +"), 0)" })
            val row = base.select(entries, lines, units, cost, vat).where(condition).single()
            mapOf(
                "entries" to (row[entries] as Number).toLong(), "lines" to (row[lines] as Number).toLong(),
                "units" to BigDecimal(row[units].toString()), "totalCost" to if (visible) BigDecimal(row[cost].toString()) else BigDecimal.ZERO,
                "inputVat" to BigDecimal(row[vat].toString()), "costVisible" to visible,
            )
        } else null
        page.copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }
}
