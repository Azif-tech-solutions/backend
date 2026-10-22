package com.zifiq.cashier.service

import com.zifiq.cashier.dto.SupplyHistoryRow
import com.zifiq.cashier.dto.SupplyPosition
import com.zifiq.cashier.dto.SupplyPositionLine
import com.zifiq.cashier.dto.SupplyTotals
import com.zifiq.cashier.dto.WaybillAcknowledgeRequest
import com.zifiq.cashier.dto.WaybillCancelRequest
import com.zifiq.cashier.dto.WaybillLineView
import com.zifiq.cashier.dto.WaybillRequest
import com.zifiq.cashier.dto.WaybillRow
import com.zifiq.cashier.dto.WaybillView
import com.zifiq.cashier.model.CustomerWaybillLinesTable
import com.zifiq.cashier.model.CustomerWaybillsTable
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.allOf
import com.zifiq.inventory.model.StockReservationsTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.DriverService
import com.zifiq.inventory.service.StockLocations
import com.zifiq.inventory.service.StockReservations
import com.zifiq.invoicing.spi.InvoiceWaybillLinks
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Filters for the waybill list (docs/09 §4.5). */
data class WaybillFilter(
    val locationId: UUID? = null,
    val saleId: UUID? = null,
    val driverId: UUID? = null,
    val status: String? = null,
    val ref: String? = null,
)

/**
 * Customer waybills (docs/09 §4.5): releasing a sale's outstanding goods, which posts the reserved
 * stock out of the ledger. A release can't exceed what is outstanding; a delivery needs an active
 * driver, a collection needs who collected. Acknowledge records the signed copy; cancel (same day,
 * before the goods left) puts the stock and the reservation back. Runs through the tenant-scoped
 * executor.
 */
@Service
class CustomerWaybillService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val views: SaleViews,
    private val reservations: StockReservations,
    private val stockLedger: StockLedgerRepository,
    private val drivers: DriverService,
    private val stockLocations: StockLocations,
    private val invoiceLinks: InvoiceWaybillLinks,
    private val documents: DocumentRegistry,
    private val sequences: DocumentSequenceService,
    private val pdfRenderer: com.zifiq.platform.documents.PdfRenderer,
    private val companyProfile: com.zifiq.settings.spi.CompanyProfileProvider,
) {
    /** The outstanding position of a sale, found by its TXN or INV reference. */
    fun lookup(ref: String): SupplyPosition = tx.execute { position(saleByRef(ref)) }

    fun create(request: WaybillRequest): WaybillView = tx.execute {
        val sale = views.visibleRow(request.saleId!!, forUpdate = true)
        if (sale[SalesTable.status] != "COMPLETED") throw ApiErrors.saleNotFound()
        val method = request.releaseMethod!!.trim().uppercase()
        if (method !in setOf("DELIVERY", "COLLECTION")) throw ApiErrors.validationFailed(mapOf("releaseMethod" to "DELIVERY or COLLECTION"))
        val releaseDate = request.releaseDate ?: support.today()
        if (releaseDate.isAfter(support.today())) throw ApiErrors.validationFailed(mapOf("releaseDate" to "can't be in the future"))
        sale[SalesTable.saleDate]?.let { if (releaseDate.isBefore(it)) throw ApiErrors.validationFailed(mapOf("releaseDate" to "can't be before the sale date")) }

        val driver = if (method == "DELIVERY") drivers.activeDriver(request.driverId ?: throw ApiErrors.validationFailed(mapOf("driverId" to "a delivery needs a driver"))) else null
        val collectedBy = if (method == "COLLECTION") request.collectedBy?.trim()?.ifEmpty { null } ?: throw ApiErrors.validationFailed(mapOf("collectedBy" to "who collected the goods")) else null

        val locationId = sale[SalesTable.locationId].value
        val ref = sequences.next("WB")
        val waybillId = CustomerWaybillsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[CustomerWaybillsTable.ref] = ref
            it[saleId] = sale[SalesTable.id].value
            it[invoiceNumber] = sale[SalesTable.invoiceNumber]
            it[CustomerWaybillsTable.locationId] = locationId
            it[releaseMethod] = method
            it[CustomerWaybillsTable.releaseDate] = releaseDate
            it[driverId] = driver?.id
            it[driverName] = driver?.name
            it[vehicleNumber] = (request.vehicleNumber?.trim()?.ifEmpty { null } ?: driver?.vehicleNumber)?.uppercase()
            it[CustomerWaybillsTable.collectedBy] = collectedBy
            it[deliveryAddress] = request.deliveryAddress?.trim()?.ifEmpty { null }
            it[status] = "ISSUED"
            it[issuedByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value

        request.lines.forEach { input ->
            val saleLine = SaleLinesTable.selectAll().where { (SaleLinesTable.id eq input.saleLineId!!) and (SaleLinesTable.saleId eq sale[SalesTable.id].value) }.forUpdate()
                .firstOrNull() ?: throw ApiErrors.validationFailed(mapOf("lines" to "line ${input.saleLineId} is not on this sale"))
            val outstanding = saleLine[SaleLinesTable.quantity] - saleLine[SaleLinesTable.suppliedQty]
            val qty = input.quantity!!.setScale(3, RoundingMode.HALF_UP)
            if (qty.signum() <= 0 || qty > outstanding) throw ApiErrors.releaseExceedsOutstanding(outstanding)
            val from = input.fromLocationId?.let { stockLocations.requireWithin(locationId, it, "fromLocationId").id }
                ?: saleLine[SaleLinesTable.fromLocationId] ?: sellingAreaOf(locationId)
            if (stockLedger.currentBalance(from, saleLine[SaleLinesTable.stockItemId]) < qty) throw ApiErrors.insufficientStockAtArea(stockLocations.get(from).label)
            val reservationId = reservations.openForSource("SALE_LINE", saleLine[SaleLinesTable.id].value)?.get(StockReservationsTable.id)?.value
                ?: throw ApiErrors.releaseExceedsOutstanding(outstanding)
            val remaining = reservations.release(reservationId, qty, from, "CUSTOMER_WAYBILL", ref,
                "Released to ${sale[SalesTable.customerName]} ($ref)", support.userId(), releaseDate)
            SaleLinesTable.update({ SaleLinesTable.id eq saleLine[SaleLinesTable.id].value }) { it[suppliedQty] = saleLine[SaleLinesTable.suppliedQty] + qty }
            CustomerWaybillLinesTable.insert {
                it[tenantId] = support.tenantId()
                it[CustomerWaybillLinesTable.waybillId] = waybillId
                it[saleLineId] = saleLine[SaleLinesTable.id].value
                it[fromLocationId] = from
                it[quantity] = qty
                it[outstandingAfter] = remaining
            }
        }

        val nowFull = recomputeSupplyStatus(sale[SalesTable.id].value)
        sale[SalesTable.invoiceId]?.let { invoiceLinks.link(it, waybillId, ref, releaseDate) }
        documents.register(ref, DocumentType.WAYBILL, CashierSupport.SOURCE_MODULE, waybillId, locationId, sale[SalesTable.customerName], sale[SalesTable.customerId], null)
        val eventId = support.record("cashier.waybill.issued", "customer-waybill", waybillId, ref,
            "$ref: goods released to ${sale[SalesTable.customerName]} against ${sale[SalesTable.invoiceNumber] ?: sale[SalesTable.ref]}",
            mapOf("saleId" to sale[SalesTable.id].value, "invoiceNumber" to sale[SalesTable.invoiceNumber]), locationId)
        if (nowFull) support.record("cashier.sale.fully-supplied", "sale", sale[SalesTable.id].value, sale[SalesTable.ref], "${sale[SalesTable.ref]} fully supplied",
            mapOf("sourceEvent" to eventId), locationId)
        view(waybillRow(waybillId))
    }

    fun acknowledge(id: UUID, request: WaybillAcknowledgeRequest): WaybillView = tx.execute {
        val waybill = visible(id, forUpdate = true)
        if (waybill[CustomerWaybillsTable.status] != "ISSUED") throw ApiErrors.invalidStatusTransition(waybill[CustomerWaybillsTable.status], "DELIVERED")
        CustomerWaybillsTable.update({ CustomerWaybillsTable.id eq id }) {
            it[status] = "DELIVERED"
            it[receivedBy] = request.receivedBy!!.trim().take(160)
            it[receivedAt] = request.receivedAt ?: Instant.now()
            it[acknowledgementNote] = request.note?.trim()?.ifEmpty { null }
        }
        support.record("cashier.waybill.delivered", "customer-waybill", id, waybill[CustomerWaybillsTable.ref],
            "${waybill[CustomerWaybillsTable.ref]} acknowledged by ${request.receivedBy}", emptyMap(), waybill[CustomerWaybillsTable.locationId])
        view(waybillRow(id))
    }

    fun cancel(id: UUID, request: WaybillCancelRequest): WaybillView = tx.execute {
        val waybill = visible(id, forUpdate = true)
        if (waybill[CustomerWaybillsTable.status] != "ISSUED" || waybill[CustomerWaybillsTable.releaseDate] != support.today()) throw ApiErrors.waybillNotCancellable()
        val reason = request.reason!!.trim()
        CustomerWaybillLinesTable.selectAll().where { CustomerWaybillLinesTable.waybillId eq id }.forEach { line ->
            val saleLineId = line[CustomerWaybillLinesTable.saleLineId]
            val qty = line[CustomerWaybillLinesTable.quantity]
            val saleLine = SaleLinesTable.selectAll().where { SaleLinesTable.id eq saleLineId }.single()
            // The released goods go back where they left from; the reservation re-opens for that quantity.
            stockLedger.postEntry(tenantId = support.tenantId(), locationId = line[CustomerWaybillLinesTable.fromLocationId], stockItemId = saleLine[SaleLinesTable.stockItemId],
                sourceType = "CUSTOMER_WAYBILL_CANCEL", sourceReference = waybill[CustomerWaybillsTable.ref], qtyIn = qty,
                remark = "${waybill[CustomerWaybillsTable.ref]} cancelled: $reason", performedByUserId = support.userId(), entryDate = support.today())
            reservations.findBySource("SALE_LINE", saleLineId)?.let { reservations.restore(it[StockReservationsTable.id].value, qty) }
            SaleLinesTable.update({ SaleLinesTable.id eq saleLineId }) { it[suppliedQty] = (saleLine[SaleLinesTable.suppliedQty] - qty).max(BigDecimal.ZERO) }
        }
        CustomerWaybillsTable.update({ CustomerWaybillsTable.id eq id }) {
            it[status] = "CANCELLED"
            it[cancelledReason] = reason.take(300)
            it[cancelledByUserId] = support.userId()
        }
        documents.markVoided(waybill[CustomerWaybillsTable.ref])
        recomputeSupplyStatus(waybill[CustomerWaybillsTable.saleId])
        support.record("cashier.waybill.cancelled", "customer-waybill", id, waybill[CustomerWaybillsTable.ref],
            "${waybill[CustomerWaybillsTable.ref]} cancelled: $reason", emptyMap(), waybill[CustomerWaybillsTable.locationId])
        view(waybillRow(id))
    }

    fun get(id: UUID): WaybillView = tx.execute { view(visible(id)) }

    fun pdf(id: UUID): SaleDocument = tx.execute {
        val w = visible(id)
        val view = view(w).also(com.zifiq.platform.directory.Refs::resolve)
        val company = companyProfile.get()
        val model = mapOf(
            "waybill" to mapOf("ref" to view.ref, "releaseDate" to view.releaseDate.toString(), "releaseMethod" to view.releaseMethod,
                "driverName" to view.driverName, "vehicleNumber" to view.vehicleNumber, "collectedBy" to view.collectedBy,
                "deliveryAddress" to view.deliveryAddress, "customerName" to view.customerName, "invoiceNumber" to view.invoiceNumber, "saleRef" to view.saleRef),
            "company" to company, "logo" to company.logoDataUri(), "showroom" to view.location.name, "status" to view.status,
            "lines" to view.lines.map { mapOf("product" to it.product, "unit" to it.unit,
                "quantity" to it.quantity.stripTrailingZeros().toPlainString(), "outstanding" to it.stillOutstanding.stripTrailingZeros().toPlainString()) },
            "totalReleased" to view.lines.fold(java.math.BigDecimal.ZERO) { a, l -> a + l.quantity }.stripTrailingZeros().toPlainString(),
            "duplicate" to documents.isReprint(view.ref),
            "printedAt" to java.time.LocalDateTime.now(support.zone()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
        )
        SaleDocument("${view.ref}.pdf", pdfRenderer.render("customer-waybill", model))
    }

    fun list(filter: WaybillFilter, paging: PageRequest): PageResult<WaybillRow> = tx.execute {
        val refU = filter.ref?.trim()?.uppercase()?.ifEmpty { null }
        val condition = listOfNotNull(
            CustomerWaybillsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { CustomerWaybillsTable.locationId inList it },
            filter.locationId?.let { CustomerWaybillsTable.locationId eq it },
            filter.saleId?.let { CustomerWaybillsTable.saleId eq it },
            filter.driverId?.let { CustomerWaybillsTable.driverId eq it },
            filter.status?.let { CustomerWaybillsTable.status eq it.uppercase() },
            refU?.let { (CustomerWaybillsTable.ref eq it) or (CustomerWaybillsTable.invoiceNumber eq it) },
        ).allOf()!!
        val page = Paging.fetch(CustomerWaybillsTable.selectAll().where(condition)
            .orderBy(CustomerWaybillsTable.releaseDate to SortOrder.DESC, CustomerWaybillsTable.id to SortOrder.DESC), paging) { it }
        page.withItems(page.items.map { w ->
            val units = CustomerWaybillLinesTable.select(CustomerWaybillLinesTable.quantity).where { CustomerWaybillLinesTable.waybillId eq w[CustomerWaybillsTable.id].value }
                .fold(BigDecimal.ZERO) { s, l -> s + l[CustomerWaybillLinesTable.quantity] }
            WaybillRow(
                id = w[CustomerWaybillsTable.id].value, ref = w[CustomerWaybillsTable.ref], sale = Ref.sale(w[CustomerWaybillsTable.saleId]), saleRef = saleRefOf(w[CustomerWaybillsTable.saleId]),
                invoiceNumber = w[CustomerWaybillsTable.invoiceNumber], location = Ref.location(w[CustomerWaybillsTable.locationId]), customerName = customerNameOf(w[CustomerWaybillsTable.saleId]),
                releaseMethod = w[CustomerWaybillsTable.releaseMethod], releaseDate = w[CustomerWaybillsTable.releaseDate], driverName = w[CustomerWaybillsTable.driverName],
                collectedBy = w[CustomerWaybillsTable.collectedBy], units = units, status = w[CustomerWaybillsTable.status],
            )
        })
    }

    // ---- internals ----

    /** FULLY_SUPPLIED once nothing is outstanding; returns whether the sale just became fully supplied. */
    private fun recomputeSupplyStatus(saleId: UUID): Boolean {
        val lines = SaleLinesTable.select(SaleLinesTable.quantity, SaleLinesTable.suppliedQty).where { SaleLinesTable.saleId eq saleId }.toList()
        val full = lines.all { it[SaleLinesTable.suppliedQty] >= it[SaleLinesTable.quantity] }
        val nothing = lines.all { it[SaleLinesTable.suppliedQty].signum() == 0 }
        val status = if (full) "FULLY_SUPPLIED" else if (nothing) "NOT_SUPPLIED" else "PARTIALLY_SUPPLIED"
        val was = SalesTable.select(SalesTable.supplyStatus).where { SalesTable.id eq saleId }.single()[SalesTable.supplyStatus]
        SalesTable.update({ SalesTable.id eq saleId }) { it[supplyStatus] = status }
        return status == "FULLY_SUPPLIED" && was != "FULLY_SUPPLIED"
    }

    private fun position(sale: ResultRow): SupplyPosition {
        val saleId = sale[SalesTable.id].value
        val lines = SaleLinesTable.selectAll().where { SaleLinesTable.saleId eq saleId }.orderBy(SaleLinesTable.lineNo).toList()
        val positionLines = lines.map { l ->
            SupplyPositionLine(l[SaleLinesTable.id].value, Ref.stockItem(l[SaleLinesTable.stockItemId]), l[SaleLinesTable.itemName], l[SaleLinesTable.unitName],
                l[SaleLinesTable.quantity], l[SaleLinesTable.suppliedQty], l[SaleLinesTable.quantity] - l[SaleLinesTable.suppliedQty])
        }
        val purchased = positionLines.fold(BigDecimal.ZERO) { s, l -> s + l.purchased }
        val supplied = positionLines.fold(BigDecimal.ZERO) { s, l -> s + l.supplied }
        val history = CustomerWaybillsTable.selectAll().where { (CustomerWaybillsTable.saleId eq saleId) and (CustomerWaybillsTable.status neq "CANCELLED") }
            .orderBy(CustomerWaybillsTable.releaseDate to SortOrder.ASC).flatMap { w ->
                CustomerWaybillLinesTable.selectAll().where { CustomerWaybillLinesTable.waybillId eq w[CustomerWaybillsTable.id].value }.map { wl ->
                    val sl = lines.first { it[SaleLinesTable.id].value == wl[CustomerWaybillLinesTable.saleLineId] }
                    SupplyHistoryRow(Ref.customerWaybill(w[CustomerWaybillsTable.id].value), w[CustomerWaybillsTable.ref], w[CustomerWaybillsTable.releaseDate],
                        sl[SaleLinesTable.itemName], wl[CustomerWaybillLinesTable.quantity], w[CustomerWaybillsTable.driverName], w[CustomerWaybillsTable.collectedBy],
                        w[CustomerWaybillsTable.receivedBy], w[CustomerWaybillsTable.status])
                }
            }
        return SupplyPosition(
            sale = Ref.sale(saleId), saleRef = sale[SalesTable.ref] ?: "", invoiceNumber = sale[SalesTable.invoiceNumber],
            customer = ref(sale[SalesTable.customerId], Ref::customer), customerName = sale[SalesTable.customerName], customerType = sale[SalesTable.customerType],
            address = null, contact = sale[SalesTable.walkInPhone], lines = positionLines,
            totals = SupplyTotals(purchased, supplied, purchased - supplied, pct(supplied, purchased)),
            history = history, waybillCount = history.map { it.waybillRef }.distinct().size, fullySupplied = (purchased - supplied).signum() == 0,
        )
    }

    private fun pct(part: BigDecimal, whole: BigDecimal): Int =
        if (whole.signum() == 0) 100 else (part * BigDecimal(100) / whole).setScale(0, RoundingMode.HALF_UP).toInt()

    private fun saleByRef(ref: String): ResultRow {
        val r = ref.trim().uppercase()
        val sale = SalesTable.selectAll()
            .where { (SalesTable.tenantId eq support.tenantId()) and (SalesTable.status eq "COMPLETED") and ((SalesTable.ref eq r) or (SalesTable.invoiceNumber eq r)) }
            .firstOrNull() ?: throw ApiErrors.saleNotFound()
        support.assertVisible(sale[SalesTable.locationId].value, sale[SalesTable.cashierUserId]) { ApiErrors.saleNotFound() }
        return sale
    }

    private fun saleRefOf(saleId: UUID): String? = SalesTable.select(SalesTable.ref).where { SalesTable.id eq saleId }.firstOrNull()?.get(SalesTable.ref)
    private fun customerNameOf(saleId: UUID): String = SalesTable.select(SalesTable.customerName).where { SalesTable.id eq saleId }.single()[SalesTable.customerName]

    private fun visible(id: UUID, forUpdate: Boolean = false): ResultRow {
        val row = CustomerWaybillsTable.selectAll().where { (CustomerWaybillsTable.id eq id) and (CustomerWaybillsTable.tenantId eq support.tenantId()) }
            .let { if (forUpdate) it.forUpdate() else it }.firstOrNull() ?: throw ApiErrors.validationFailed(mapOf("id" to "waybill not found"))
        support.assertVisible(row[CustomerWaybillsTable.locationId], support.userId()) { ApiErrors.validationFailed(mapOf("id" to "waybill not found")) }
        return row
    }

    private fun waybillRow(id: UUID): ResultRow = CustomerWaybillsTable.selectAll().where { CustomerWaybillsTable.id eq id }.single()

    private fun view(w: ResultRow): WaybillView {
        val id = w[CustomerWaybillsTable.id].value
        val saleId = w[CustomerWaybillsTable.saleId]
        val lines = CustomerWaybillLinesTable.selectAll().where { CustomerWaybillLinesTable.waybillId eq id }.map { wl ->
            val sl = SaleLinesTable.selectAll().where { SaleLinesTable.id eq wl[CustomerWaybillLinesTable.saleLineId] }.single()
            WaybillLineView(Ref.stockItem(sl[SaleLinesTable.stockItemId]), sl[SaleLinesTable.itemName], sl[SaleLinesTable.unitName],
                wl[CustomerWaybillLinesTable.quantity], Ref.location(wl[CustomerWaybillLinesTable.fromLocationId]), wl[CustomerWaybillLinesTable.outstandingAfter])
        }
        return WaybillView(
            id = id, ref = w[CustomerWaybillsTable.ref], sale = Ref.sale(saleId), saleRef = saleRefOf(saleId), invoiceNumber = w[CustomerWaybillsTable.invoiceNumber],
            location = Ref.location(w[CustomerWaybillsTable.locationId]), customerName = customerNameOf(saleId), releaseMethod = w[CustomerWaybillsTable.releaseMethod],
            releaseDate = w[CustomerWaybillsTable.releaseDate], driver = ref(w[CustomerWaybillsTable.driverId], Ref::driver), driverName = w[CustomerWaybillsTable.driverName],
            vehicleNumber = w[CustomerWaybillsTable.vehicleNumber], collectedBy = w[CustomerWaybillsTable.collectedBy], deliveryAddress = w[CustomerWaybillsTable.deliveryAddress],
            status = w[CustomerWaybillsTable.status], receivedBy = w[CustomerWaybillsTable.receivedBy], receivedAt = w[CustomerWaybillsTable.receivedAt],
            acknowledgementNote = w[CustomerWaybillsTable.acknowledgementNote], cancelledReason = w[CustomerWaybillsTable.cancelledReason],
            issuedBy = UserRef(w[CustomerWaybillsTable.issuedByUserId]), lines = lines,
        )
    }

    private fun sellingAreaOf(locationId: UUID): UUID =
        LocationsTable.select(LocationsTable.defaultSellingLocationId).where { LocationsTable.id eq locationId }.single()[LocationsTable.defaultSellingLocationId] ?: locationId
}
