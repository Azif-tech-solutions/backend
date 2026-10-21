package com.zifiq.cashier.service

import com.zifiq.account.spi.VatSetting
import com.zifiq.cashier.dto.SaleCustomerRequest
import com.zifiq.cashier.dto.SaleLinesRequest
import com.zifiq.cashier.dto.SaleRow
import com.zifiq.cashier.dto.SaleStartRequest
import com.zifiq.cashier.dto.SaleVersionRequest
import com.zifiq.cashier.dto.SaleView
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalePaymentsTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.cashier.model.TillSessionsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.allOf
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Filters for the sales list (docs/09 §4.2 Read). */
data class SaleFilter(
    val locationId: UUID? = null,
    val cashierUserId: UUID? = null,
    val customerId: UUID? = null,
    val channel: String? = null,
    /** Default COMPLETED. */
    val status: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** A TXN or invoice number. */
    val ref: String? = null,
)

/**
 * The POS cart (docs/09 §4.2): a sale is a server-side row while OPEN, built on the cashier's open
 * till session at its showroom. It holds no stock and moves no money until completion
 * ([SaleCheckout]). Every change carries the version the client saw (409 SALE_CHANGED otherwise) and
 * bumps it, and only the sale's cashier may change it.
 */
@Service
class SaleService(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val tills: TillSessionService,
    private val pricing: SalePricing,
    private val views: SaleViews,
    private val customers: CustomerDirectory,
) {
    fun start(request: SaleStartRequest): SaleView = tx.execute {
        val session = tills.requireOwnOpen()
        val locationId = session[TillSessionsTable.locationId].value
        val customer = request.customerId?.let(::sellableCustomer)
        val vat = pricing.vatAt(locationId, support.today())
        val now = Instant.now()
        val id = SalesTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[channel] = "POS"
            it[status] = "OPEN"
            it[version] = 0
            it[SalesTable.locationId] = locationId
            it[tillSessionId] = session[TillSessionsTable.id].value
            it[cashierUserId] = support.userId()
            customerColumns(it, customer)
            it[priceBasis] = if (customer.isDealer()) "DEALER" else "RETAIL"
            it[grossAmount] = BigDecimal.ZERO
            it[lineDiscountTotal] = BigDecimal.ZERO
            it[instantDiscountTotal] = BigDecimal.ZERO
            it[netAmount] = BigDecimal.ZERO
            it[vatEnabled] = vat.enabled
            it[vatRate] = vat.rate
            it[vatAmount] = BigDecimal.ZERO
            it[total] = BigDecimal.ZERO
            it[paidNow] = BigDecimal.ZERO
            it[onAccount] = BigDecimal.ZERO
            it[supplyStatus] = "NOT_APPLICABLE"
            it[startedAt] = now
            it[updatedAt] = now
        }.value
        views.view(id)
    }

    /** Re-prices every line for the new customer (dealer ↔ retail). */
    fun setCustomer(id: UUID, request: SaleCustomerRequest): SaleView = tx.execute {
        val sale = editable(id, request.version!!)
        val customer = request.customerId?.let(::sellableCustomer)
        SalesTable.update({ SalesTable.id eq id }) { customerColumns(it, customer) }
        reprice(sale, currentInputs(id), customer.isDealer())
        views.view(id)
    }

    /** Replaces the cart's lines. Quantities beyond what the showroom has are kept but flagged (the hard check is at completion). */
    fun setLines(id: UUID, request: SaleLinesRequest): SaleView = tx.execute {
        val sale = editable(id, request.version!!)
        val inputs = request.lines.map { it.stockItemId!! to it.quantity!! }
        reprice(sale, inputs, sale[SalesTable.priceBasis] == "DEALER")
        views.view(id)
    }

    /** "Clear sale": no stock or money was ever touched. */
    fun abandon(id: UUID, request: SaleVersionRequest): SaleView = tx.execute {
        editable(id, request.version!!)
        SalesTable.update({ SalesTable.id eq id }) {
            it[status] = "ABANDONED"
            it[abandonedAt] = Instant.now()
            it[version] = version + 1
            it[updatedAt] = Instant.now()
        }
        views.view(id)
    }

    fun get(id: UUID): SaleView = tx.execute { views.view(views.visibleRow(id)) }

    fun list(filter: SaleFilter, paging: PageRequest): PageResult<SaleRow> = tx.execute {
        val status = filter.status?.uppercase() ?: "COMPLETED"
        val ref = filter.ref?.trim()?.uppercase()?.ifEmpty { null }
        val condition = listOfNotNull(
            SalesTable.tenantId eq support.tenantId(),
            SalesTable.status eq status,
            support.visibleLocations()?.let { SalesTable.locationId inList it },
            if (support.ownOnly()) SalesTable.cashierUserId eq support.userId() else filter.cashierUserId?.let { SalesTable.cashierUserId eq it },
            filter.locationId?.let { SalesTable.locationId eq it },
            filter.customerId?.let { SalesTable.customerId eq it },
            filter.channel?.let { SalesTable.channel eq it.uppercase() },
            filter.from?.let { SalesTable.saleDate greaterEq it },
            filter.to?.let { SalesTable.saleDate lessEq it },
            ref?.let { (SalesTable.ref eq it) or (SalesTable.invoiceNumber eq it) },
        ).allOf()!!
        val order = if (status == "OPEN") arrayOf(SalesTable.startedAt to SortOrder.DESC, SalesTable.id to SortOrder.DESC)
        else arrayOf(SalesTable.completedAt to SortOrder.DESC, SalesTable.id to SortOrder.DESC)
        val page = Paging.fetch(SalesTable.selectAll().where(condition).orderBy(*order), paging) { it }
        rows(page.items).let(page::withItems)
    }

    /** List rows for a page of sales: methods, item counts and tills in three grouped reads. */
    private fun rows(sales: List<ResultRow>): List<SaleRow> {
        if (sales.isEmpty()) return emptyList()
        val ids = sales.map { it[SalesTable.id].value }
        val methods = SalePaymentsTable.select(SalePaymentsTable.saleId, SalePaymentsTable.method)
            .where { SalePaymentsTable.saleId inList ids }.withDistinct()
            .groupBy({ it[SalePaymentsTable.saleId].value }, { it[SalePaymentsTable.method] })
        val count = SaleLinesTable.id.count()
        val units = SaleLinesTable.quantity.sum()
        val lines = SaleLinesTable.select(SaleLinesTable.saleId, count, units).where { SaleLinesTable.saleId inList ids }.groupBy(SaleLinesTable.saleId)
            .associate { it[SaleLinesTable.saleId].value to (it[count].toInt() to (it[units] ?: BigDecimal.ZERO)) }
        val sessionIds = sales.mapNotNull { it[SalesTable.tillSessionId] }.toSet()
        val tills = if (sessionIds.isEmpty()) emptyMap()
        else TillSessionsTable.select(TillSessionsTable.id, TillSessionsTable.cashAccountId).where { TillSessionsTable.id inList sessionIds }
            .associate { it[TillSessionsTable.id].value to it[TillSessionsTable.cashAccountId] }
        return sales.map { sale ->
            val id = sale[SalesTable.id].value
            val (items, qty) = lines[id] ?: (0 to BigDecimal.ZERO)
            views.row(sale, methods[id].orEmpty().sorted(), items, qty, sale[SalesTable.tillSessionId]?.let(tills::get))
        }
    }

    /** The open sale, locked, at the version the cashier saw; only its cashier may change it. */
    internal fun editable(id: UUID, version: Int): ResultRow {
        val sale = views.visibleRow(id, forUpdate = true)
        support.requireOwner(sale[SalesTable.cashierUserId])
        if (sale[SalesTable.status] != "OPEN") throw ApiErrors.saleNotOpen(sale[SalesTable.status])
        if (sale[SalesTable.version] != version) throw ApiErrors.saleChanged(sale[SalesTable.version])
        return sale
    }

    /** Prices [inputs] at today's price list and the showroom's VAT, and writes them as the cart. */
    internal fun reprice(sale: ResultRow, inputs: List<Pair<UUID, BigDecimal>>, dealer: Boolean) {
        val (lines, vat) = priceToday(sale, inputs, dealer)
        writeCart(sale, lines, vat, dealer)
    }

    internal fun priceToday(sale: ResultRow, inputs: List<Pair<UUID, BigDecimal>>, dealer: Boolean): Pair<List<PricedSaleLine>, VatSetting> {
        val locationId = sale[SalesTable.locationId].value
        val vat = pricing.vatAt(locationId, support.today())
        return pricing.price(locationId, dealer, inputs, support.today(), vat) to vat
    }

    internal fun writeCart(sale: ResultRow, lines: List<PricedSaleLine>, vat: VatSetting, dealer: Boolean) =
        pricing.write(sale[SalesTable.id].value, support.tenantId(), lines, vat, dealer)

    internal fun currentInputs(id: UUID): List<Pair<UUID, BigDecimal>> =
        SaleLinesTable.select(SaleLinesTable.stockItemId, SaleLinesTable.quantity).where { SaleLinesTable.saleId eq id }.orderBy(SaleLinesTable.lineNo)
            .map { it[SaleLinesTable.stockItemId] to it[SaleLinesTable.quantity] }

    /** A customer who can buy now: ACTIVE or WATCH (409 CUSTOMER_NOT_ACTIVE otherwise). */
    internal fun sellableCustomer(id: UUID): CustomerRef {
        val customer = customers.get(id)
        if (customer.status != CustomerStatus.ACTIVE && customer.status != CustomerStatus.WATCH) throw ApiErrors.customerNotActive(customer.status.name.lowercase())
        return customer
    }

    private fun customerColumns(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, customer: CustomerRef?) {
        it[SalesTable.customerId] = customer?.id
        it[SalesTable.customerCode] = customer?.code
        it[SalesTable.customerName] = customer?.name?.take(200) ?: WALK_IN
        it[SalesTable.customerType] = customer?.type?.name
    }

    private fun CustomerRef?.isDealer() = this?.type == CustomerType.AUTHORISED_DEALER

    companion object {
        const val WALK_IN = "Walk-in customer"
    }
}
