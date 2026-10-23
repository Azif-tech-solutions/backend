package com.zifiq.cashier.service

import com.zifiq.account.spi.CashAccounts
import com.zifiq.account.spi.Channel
import com.zifiq.account.spi.Receipts
import com.zifiq.cashier.dto.SaleCustomerView
import com.zifiq.cashier.dto.SaleInstantDiscount
import com.zifiq.cashier.dto.SaleLineView
import com.zifiq.cashier.dto.SalePaymentView
import com.zifiq.cashier.dto.SaleRow
import com.zifiq.cashier.dto.SaleTotalsView
import com.zifiq.cashier.dto.SaleView
import com.zifiq.cashier.model.InstantDiscountsTable
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalePaymentsTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.cashier.model.TillSessionsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerType
import com.zifiq.inventory.service.StockAvailability
import com.zifiq.invoicing.spi.Receivables
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/**
 * Reads a sale back as the till, the reports and the receipt see it (docs/09 §4.2). One place builds
 * the view, so every endpoint returns the same shape. Caller's transaction.
 */
@Component
class SaleViews(
    private val support: CashierSupport,
    private val customers: CustomerDirectory,
    private val receivables: Receivables,
    private val receipts: Receipts,
    private val cashAccounts: CashAccounts,
    private val availability: StockAvailability,
) {
    /** The sale as the caller may see it; out of scope (or another cashier's, for a cashier) is "not found". */
    fun visibleRow(id: UUID, forUpdate: Boolean = false): ResultRow {
        val row = SalesTable.selectAll().where { (SalesTable.id eq id) and (SalesTable.tenantId eq support.tenantId()) }
            .let { if (forUpdate) it.forUpdate() else it }
            .firstOrNull() ?: throw ApiErrors.saleNotFound()
        support.assertVisible(row[SalesTable.locationId].value, row[SalesTable.cashierUserId]) { ApiErrors.saleNotFound() }
        return row
    }

    fun view(id: UUID): SaleView = view(SalesTable.selectAll().where { SalesTable.id eq id }.single())

    fun view(sale: ResultRow): SaleView {
        val id = sale[SalesTable.id].value
        val status = sale[SalesTable.status]
        val open = status == "OPEN"
        val lines = SaleLinesTable.selectAll().where { SaleLinesTable.saleId eq id }.orderBy(SaleLinesTable.lineNo).toList()
        val available = if (open) availability.available(sale[SalesTable.locationId].value, lines.map { it[SaleLinesTable.stockItemId] }) else emptyMap()
        val payments = SalePaymentsTable.selectAll().where { SalePaymentsTable.saleId eq id }.orderBy(SalePaymentsTable.createdAt to SortOrder.ASC).toList()
        val receiptStatus = if (payments.isEmpty()) emptyMap() else receipts.bySource(CashierSupport.SOURCE_MODULE, listOf(id)).associate { it.id to it.confirmationStatus.name }
        val customer = sale[SalesTable.customerId]?.let { customerView(it, open) }
        val tillAccountId = sale[SalesTable.tillSessionId]?.let { session ->
            TillSessionsTable.select(TillSessionsTable.cashAccountId).where { TillSessionsTable.id eq session }.single()[TillSessionsTable.cashAccountId]
        }
        val completedAt = sale[SalesTable.completedAt]
        val liveDiscount = InstantDiscountsTable.selectAll().where { (InstantDiscountsTable.saleId eq id) and (InstantDiscountsTable.status inList listOf("PENDING_APPROVAL", "APPLIED")) }.firstOrNull()
        return SaleView(
            id = id, ref = sale[SalesTable.ref], status = status, version = sale[SalesTable.version], channel = sale[SalesTable.channel],
            till = ref(tillAccountId, Ref::cashAccount), location = Ref.location(sale[SalesTable.locationId].value),
            cashier = UserRef(sale[SalesTable.cashierUserId]), customer = customer, customerName = sale[SalesTable.customerName],
            priceBasis = sale[SalesTable.priceBasis],
            lines = lines.map { line ->
                val itemId = line[SaleLinesTable.stockItemId]
                val have = available[itemId]
                // Two lines never share an item (the cart merges them), so a line's need is its own quantity.
                SaleLineView(
                    lineNo = line[SaleLinesTable.lineNo], stockItem = Ref.stockItem(itemId), code = line[SaleLinesTable.itemCode],
                    name = line[SaleLinesTable.itemName], unit = line[SaleLinesTable.unitName], category = line[SaleLinesTable.categoryName],
                    quantity = line[SaleLinesTable.quantity], unitPrice = line[SaleLinesTable.unitPrice], priceBasis = line[SaleLinesTable.priceBasis],
                    lineDiscount = line[SaleLinesTable.lineDiscount], instantDiscountShare = line[SaleLinesTable.instantDiscountShare],
                    lineNet = line[SaleLinesTable.lineNet], vatRate = line[SaleLinesTable.vatRate], vatAmount = line[SaleLinesTable.vatAmount],
                    lineTotal = line[SaleLinesTable.lineTotal], available = have, insufficient = have != null && have < line[SaleLinesTable.quantity],
                    supplied = line[SaleLinesTable.suppliedQty], outstanding = line[SaleLinesTable.quantity] - line[SaleLinesTable.suppliedQty],
                )
            },
            totals = SaleTotalsView(
                gross = sale[SalesTable.grossAmount], lineDiscounts = sale[SalesTable.lineDiscountTotal], instantDiscount = sale[SalesTable.instantDiscountTotal],
                net = sale[SalesTable.netAmount], vatEnabled = sale[SalesTable.vatEnabled], vatRate = sale[SalesTable.vatRate], vat = sale[SalesTable.vatAmount],
                total = sale[SalesTable.total],
            ),
            payments = payments.map { p ->
                val receiptId = p[SalePaymentsTable.paymentReceiptId]
                SalePaymentView(
                    method = p[SalePaymentsTable.method], methodLabel = Channel.labelOf(p[SalePaymentsTable.method]), amount = p[SalePaymentsTable.amount],
                    receipt = Ref.receipt(receiptId), receiptRef = p[SalePaymentsTable.receiptRef], confirmationStatus = receiptStatus[receiptId] ?: "NOT_REQUIRED",
                    terminalRef = p[SalePaymentsTable.terminalRef], payerReference = p[SalePaymentsTable.payerReference],
                )
            },
            paidNow = sale[SalesTable.paidNow], onAccount = sale[SalesTable.onAccount], cashTendered = sale[SalesTable.cashTendered],
            changeGiven = sale[SalesTable.changeGiven],
            allowedMethods = if (open) allowedMethods(customer, tillAccountId) else emptyList(),
            instantDiscount = liveDiscount?.let { SaleInstantDiscount(it[InstantDiscountsTable.id].value, it[InstantDiscountsTable.ref], it[InstantDiscountsTable.discountAmount], it[InstantDiscountsTable.pctOfBase], it[InstantDiscountsTable.reason], it[InstantDiscountsTable.status]) },
            invoice = ref(sale[SalesTable.invoiceId], Ref::invoice), invoiceNumber = sale[SalesTable.invoiceNumber],
            correspondent = ref(sale[SalesTable.correspondentId], Ref::correspondent), supplyStatus = sale[SalesTable.supplyStatus], saleDate = sale[SalesTable.saleDate],
            startedAt = sale[SalesTable.startedAt], completedAt = completedAt,
            creditOverrideReason = sale[SalesTable.creditOverrideReason], creditOverrideBy = UserRef.of(sale[SalesTable.creditOverrideByUserId]),
            voidedAt = sale[SalesTable.voidedAt], voidedBy = UserRef.of(sale[SalesTable.voidedByUserId]), voidReason = sale[SalesTable.voidReason],
            voidableUntil = if (status == "COMPLETED" && completedAt != null) voidableUntil(completedAt) else null,
        )
    }

    fun voidableUntil(completedAt: Instant): Instant = completedAt + Duration.ofHours(support.settings().int(CashierSettings.VOID_WINDOW_HOURS).toLong())

    /** A list row; [methods] and [units] come from one grouped read for the whole page. */
    fun row(sale: ResultRow, methods: List<String>, items: Int, units: BigDecimal, tillAccountId: UUID?) = SaleRow(
        id = sale[SalesTable.id].value, ref = sale[SalesTable.ref], status = sale[SalesTable.status], channel = sale[SalesTable.channel],
        date = sale[SalesTable.saleDate], completedAt = sale[SalesTable.completedAt], location = Ref.location(sale[SalesTable.locationId].value),
        till = ref(tillAccountId, Ref::cashAccount), cashier = UserRef(sale[SalesTable.cashierUserId]),
        customer = ref(sale[SalesTable.customerId], Ref::customer), customerName = sale[SalesTable.customerName], customerType = sale[SalesTable.customerType],
        items = items, units = units, net = sale[SalesTable.netAmount], vat = sale[SalesTable.vatAmount], total = sale[SalesTable.total],
        methods = methods.map(Channel::labelOf), onAccount = sale[SalesTable.onAccount],
        invoice = ref(sale[SalesTable.invoiceId], Ref::invoice), invoiceNumber = sale[SalesTable.invoiceNumber],
    )

    /** The customer as the till shows them: balances and credit position (one primary-key read each). */
    fun customerView(customerId: UUID, withCredit: Boolean = true): SaleCustomerView {
        val c = customers.get(customerId)
        val outstanding = if (withCredit) receivables.outstandingFor(customerId) else BigDecimal.ZERO.setScale(2)
        val creditAvailable = (c.creditLimit - outstanding).max(BigDecimal.ZERO).setScale(2)
        return SaleCustomerView(
            customer = Ref.customer(c.id), type = c.type.name, category = c.dealerCategoryName,
            correspondent = ref(c.correspondentId?.takeIf { c.correspondentAssigned }, Ref::correspondent),
            walletBalance = c.walletBalance, depositBalance = c.depositBalance, creditLimit = c.creditLimit,
            outstanding = outstanding, creditAvailable = creditAvailable, overLimit = c.creditLimit.signum() > 0 && outstanding > c.creditLimit,
        )
    }

    /**
     * Payment methods for this customer at this till (docs/09 §1.2-5): cash through the till, card,
     * transfer; the wallet for anyone with a balance; a deposit for special customers; on account for
     * a registered, non-temporary customer with a credit limit.
     */
    fun allowedMethods(customer: SaleCustomerView?, tillAccountId: UUID?): List<String> {
        val methods = mutableListOf<String>()
        if (tillAccountId != null) methods += "CASH"
        methods += listOf("POS", "FUND_TRANSFER")
        if (customer != null) {
            val ref = customers.get(customer.customer.id)
            if (ref.walletBalance.signum() > 0) methods += "WALLET"
            if (ref.type == CustomerType.SPECIAL_CUSTOMER && ref.depositBalance.signum() > 0) methods += "DEPOSIT"
            if (!ref.isTemporary && ref.creditLimit.signum() > 0 && ref.status.name == "ACTIVE") methods += "ON_ACCOUNT"
        }
        return methods
    }

    fun cashAccount(id: UUID) = cashAccounts.get(id)
}
