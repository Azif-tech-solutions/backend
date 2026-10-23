package com.zifiq.cashier.service

import com.zifiq.account.spi.CashAccounts
import com.zifiq.account.spi.Channel
import com.zifiq.account.spi.MoneyLedger
import com.zifiq.account.spi.ReceiptRequest
import com.zifiq.cashier.dto.SaleBuyerInput
import com.zifiq.cashier.dto.SaleCompleteRequest
import com.zifiq.cashier.dto.SaleCompletion
import com.zifiq.cashier.dto.SalePaymentInput
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalePaymentsTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.cashier.model.TillSessionsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.Correspondents
import com.zifiq.customers.spi.CustomerActivity
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.StockAvailability
import com.zifiq.inventory.service.StockReservations
import com.zifiq.inventory.service.StockLocations
import com.zifiq.invoicing.spi.InvoiceIssuer
import com.zifiq.invoicing.spi.InvoiceLineRequest
import com.zifiq.invoicing.spi.InvoiceRef
import com.zifiq.invoicing.spi.InvoiceRequest
import com.zifiq.invoicing.spi.PaymentLink
import com.zifiq.invoicing.spi.Receivables
import com.zifiq.invoicing.spi.SourceType
import com.zifiq.invoicing.spi.WalkInBuyer
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Completing a POS sale (docs/09 §4.2 "Complete"), in one transaction so a failure anywhere leaves
 * no trace — no stock moved, no money recorded, no TXN number used: lock the sale and its till
 * session, confirm today's prices, check stock under row locks (two tills never sell the same last
 * units), check credit, then post stock out (`POS_SALE`), debit the wallet/deposit, record one Account
 * receipt per payment, issue the invoice (every registered customer; a walk-in on request) and link
 * the payments to it. Goods are collected at the till in this slice (deliver-later is slice 2).
 */
@Service
class SaleCheckout(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val sales: SaleService,
    private val tills: TillSessionService,
    private val views: SaleViews,
    private val customers: CustomerDirectory,
    private val walletLedger: WalletLedger,
    private val correspondents: Correspondents,
    private val activity: CustomerActivity,
    private val receivables: Receivables,
    private val moneyLedger: MoneyLedger,
    private val cashAccounts: CashAccounts,
    private val invoices: InvoiceIssuer,
    private val availability: StockAvailability,
    private val stockLedger: StockLedgerRepository,
    private val reservations: StockReservations,
    private val discounts: SaleDiscounts,
    private val stockLocations: StockLocations,
    private val sequences: DocumentSequenceService,
    private val documents: DocumentRegistry,
) {
    fun complete(id: UUID, request: SaleCompleteRequest): SaleCompletion {
        // Prices published since the cart was built: re-price and stop, so the cashier confirms the new
        // total. Its own transaction, so the re-priced cart is kept when the completion is refused.
        tx.execute { repricedVersion(id, request.version!!) }?.let { throw ApiErrors.pricesChanged(it) }
        return tx.execute { completeLocked(id, request) }
    }

    /** The new version when today's prices differ from the cart's, else null (nothing changed). */
    private fun repricedVersion(id: UUID, version: Int): Int? {
        val sale = sales.editable(id, version)
        // Pro-forma-loaded lines keep their quoted prices while the pro-forma is valid — never re-priced.
        if (SaleLinesTable.select(SaleLinesTable.id).where { (SaleLinesTable.saleId eq id) and (SaleLinesTable.priceBasis eq "PRO_FORMA") }.any()) return null
        val dealer = sale[SalesTable.priceBasis] == "DEALER"
        val (fresh, vat) = sales.priceToday(sale, sales.currentInputs(id), dealer)
        // Compare the list price only; an applied Instant Discount changes line totals, not the price.
        val stored = SaleLinesTable.select(SaleLinesTable.stockItemId, SaleLinesTable.unitPrice)
            .where { SaleLinesTable.saleId eq id }.orderBy(SaleLinesTable.lineNo)
            .map { it[SaleLinesTable.stockItemId] to it[SaleLinesTable.unitPrice] }
        if (fresh.map { it.stockItemId to it.amounts.unitPrice } == stored) return null
        sales.writeCart(sale, fresh, vat, dealer)
        return version + 1
    }

    private fun completeLocked(id: UUID, request: SaleCompleteRequest): SaleCompletion {
        val sale = sales.editable(id, request.version!!)
        val session = tills.lockOpen(sale[SalesTable.tillSessionId]!!)
        discounts.requireNoPending(id)
        val lines = SaleLinesTable.selectAll().where { SaleLinesTable.saleId eq id }.orderBy(SaleLinesTable.lineNo).toList()
        if (lines.isEmpty()) throw ApiErrors.validationFailed(mapOf("lines" to "add at least one item"))
        val locationId = sale[SalesTable.locationId].value
        val customer = sale[SalesTable.customerId]?.let { customers.getForUpdate(it) }
        if (customer != null && customer.status != CustomerStatus.ACTIVE && customer.status != CustomerStatus.WATCH) {
            throw ApiErrors.customerNotActive(customer.status.name.lowercase())
        }
        if (customer != null && request.invoiceBuyer != null) throw ApiErrors.validationFailed(mapOf("invoiceBuyer" to "only for walk-in sales; a registered customer is always invoiced"))
        val total = sale[SalesTable.total]
        val till = cashAccounts.get(session[TillSessionsTable.cashAccountId])!!
        val customerView = customer?.let { views.customerView(it.id, withCredit = false) }
        val allowed = views.allowedMethods(customerView, till.id).toSet()

        // ---- Payments must settle the total exactly (rule S10) ----
        val payments = request.payments.map { normalise(it, allowed) }
        payments.groupBy { it.method }.forEach { (method, list) ->
            if (list.size > 1 && method !in setOf("POS", "FUND_TRANSFER")) throw ApiErrors.validationFailed(mapOf("payments" to "only one $method payment per sale"))
        }
        val onAccount = Money.round(request.onAccount ?: BigDecimal.ZERO)
        if (onAccount.signum() > 0 && "ON_ACCOUNT" !in allowed) {
            throw ApiErrors.paymentMethodNotAllowed("ON_ACCOUNT", "On account needs a registered customer with a credit limit")
        }
        val allocated = payments.fold(onAccount) { s, p -> s + p.amount }
        if ((allocated - total).abs() >= TOLERANCE) throw ApiErrors.paymentNotSettled(total, allocated)

        // ---- Deliver-later plan: how much of each line goes now, and from where (docs/09 §4.2) ----
        val supplyByLine = request.supply.orEmpty().associateBy { it.lineNo }
        val plans = lines.map { line ->
            val qty = line[SaleLinesTable.quantity]
            val ask = supplyByLine[line[SaleLinesTable.lineNo]]
            val supplyNow = (ask?.supplyNow ?: qty).setScale(3, RoundingMode.HALF_UP)
            if (supplyNow.signum() < 0 || supplyNow > qty) throw ApiErrors.validationFailed(mapOf("supply" to "supplyNow for line ${line[SaleLinesTable.lineNo]} must be between 0 and ${qty.stripTrailingZeros().toPlainString()}"))
            val from = (ask?.fromLocationId ?: request.fromLocationId)?.let { stockLocations.requireWithin(locationId, it, "fromLocationId").id } ?: sellingArea(locationId)
            LinePlan(line, supplyNow, from)
        }
        val deferred = plans.any { it.supplyNow < it.line[SaleLinesTable.quantity] }
        // A walk-in leaving goods for later must be reachable (invoice details, with a phone).
        if (deferred && customer == null) {
            val buyer = request.invoiceBuyer
            if (buyer?.name.isNullOrBlank() || buyer?.phone.isNullOrBlank()) throw ApiErrors.walkInDeliveryNeedsContact()
        }
        // Availability (on hand across the showroom minus existing reservations) must cover the whole sale.
        val wanted = lines.groupBy({ it[SaleLinesTable.stockItemId] }, { it[SaleLinesTable.quantity] }).mapValues { (_, q) -> q.fold(BigDecimal.ZERO, BigDecimal::add) }
        val have = availability.available(locationId, wanted.keys, lock = true)
        val availShort = wanted.mapNotNull { (itemId, qty) ->
            if (have.getValue(itemId) < qty) codeOf(lines, itemId) to "requested ${qty.stripTrailingZeros().toPlainString()}, available ${have.getValue(itemId).stripTrailingZeros().toPlainString()}" else null
        }.toMap()
        if (availShort.isNotEmpty()) throw ApiErrors.insufficientStockForSale(availShort)
        // What physically leaves now must be on hand where it leaves from.
        plans.filter { it.supplyNow.signum() > 0 }.groupBy({ it.from to it.line[SaleLinesTable.stockItemId] }, { it.supplyNow })
            .forEach { (key, qs) ->
                val (from, itemId) = key
                if (stockLedger.lockedBalance(support.tenantId(), from, itemId) < qs.fold(BigDecimal.ZERO, BigDecimal::add)) {
                    throw ApiErrors.insufficientStockAtArea(stockLocations.get(from).label)
                }
            }

        // ---- Credit (rule S16) ----
        var overrideReason: String? = null
        if (onAccount.signum() > 0) {
            val outstanding = receivables.outstandingFor(customer!!.id)
            if (outstanding + onAccount > customer.creditLimit) {
                overrideReason = request.creditOverrideReason?.trim()?.ifEmpty { null }
                if (overrideReason == null || !support.can(CREDIT_OVERRIDE)) throw ApiErrors.creditLimitExceeded(customer.creditLimit, outstanding, onAccount)
            }
        }

        // ---- The sale happens ----
        val ref = sequences.next("TXN")
        val today = support.today()
        val now = Instant.now()
        val correspondent = customer?.let { correspondents.creditableFor(it.id) }
        val unitCosts = StockItemsTable.select(StockItemsTable.id, StockItemsTable.landingCost).where { StockItemsTable.id inList wanted.keys }
            .associate { it[StockItemsTable.id].value to it[StockItemsTable.landingCost] }
        plans.forEach { plan ->
            val line = plan.line
            val itemId = line[SaleLinesTable.stockItemId]
            if (plan.supplyNow.signum() > 0) {
                stockLedger.postEntry(
                    tenantId = support.tenantId(), locationId = plan.from, stockItemId = itemId, sourceType = "POS_SALE", sourceReference = ref,
                    qtyOut = plan.supplyNow, unitCost = unitCosts[itemId], remark = "Sold to ${sale[SalesTable.customerName]}",
                    performedByUserId = support.userId(), entryDate = today,
                )
            }
            val deferredQty = line[SaleLinesTable.quantity] - plan.supplyNow
            if (deferredQty.signum() > 0) {
                reservations.reserve(support.tenantId(), locationId, itemId, CashierSupport.SOURCE_MODULE, "SALE_LINE", line[SaleLinesTable.id].value, ref, deferredQty)
            }
            SaleLinesTable.update({ SaleLinesTable.id eq line[SaleLinesTable.id].value }) {
                it[supplyNowQty] = plan.supplyNow
                it[suppliedQty] = plan.supplyNow
                it[SaleLinesTable.fromLocationId] = if (plan.supplyNow.signum() > 0) plan.from else null
            }
        }
        val supplyStatus = when {
            !deferred -> "NOT_APPLICABLE"
            plans.all { it.supplyNow.signum() == 0 } -> "NOT_SUPPLIED"
            else -> "PARTIALLY_SUPPLIED"
        }

        val links = payments.map { payment -> pay(payment, sale, customer, ref, locationId, till.id, till.posTerminalRef) }
        val cash = payments.firstOrNull { it.method == "CASH" }

        val invoice = invoiceFor(sale, lines, customer, request.invoiceBuyer, ref, today, locationId, onAccount, correspondent?.id)
        invoice?.let { inv -> links.forEach { invoices.attachPayment(inv.id, it.copy(paidAt = now)) } }
        customer?.let { activity.recordSale(it.id, total, now) }
        documents.register(ref, DocumentType.SALE_RECEIPT, CashierSupport.SOURCE_MODULE, id, locationId, sale[SalesTable.customerName], customer?.id, total)
        sale[SalesTable.proFormaId]?.let { pfId ->
            com.zifiq.cashier.model.ProFormasTable.update({ (com.zifiq.cashier.model.ProFormasTable.id eq pfId) and (com.zifiq.cashier.model.ProFormasTable.status eq "SENT") }) {
                it[com.zifiq.cashier.model.ProFormasTable.status] = "CONVERTED"
                it[com.zifiq.cashier.model.ProFormasTable.convertedSaleId] = id
                it[com.zifiq.cashier.model.ProFormasTable.updatedAt] = now
            }
        }

        SalesTable.update({ SalesTable.id eq id }) {
            it[SalesTable.ref] = ref
            it[status] = "COMPLETED"
            it[saleDate] = today
            it[completedAt] = now
            it[updatedAt] = now
            it[version] = version + 1
            it[correspondentId] = correspondent?.id
            it[paidNow] = Money.round(payments.fold(BigDecimal.ZERO) { s, p -> s + p.amount })
            it[SalesTable.onAccount] = onAccount
            it[cashTendered] = cash?.tendered
            it[changeGiven] = cash?.let { c -> c.tendered!! - c.amount }
            it[creditOverrideReason] = overrideReason?.take(300)
            it[creditOverrideByUserId] = if (overrideReason != null) support.userId() else null
            it[invoiceId] = invoice?.id
            it[invoiceNumber] = invoice?.number
            it[walkInPhone] = if (customer == null) request.invoiceBuyer?.phone?.trim()?.ifEmpty { null } else null
            it[SalesTable.supplyStatus] = supplyStatus
        }
        support.record("cashier.sale.completed", "sale", id, ref,
            "$ref: ${Money.naira(total)} to ${sale[SalesTable.customerName]}" + (invoice?.number?.let { " ($it)" } ?: ""),
            mapOf(
                "channel" to sale[SalesTable.channel], "customerId" to customer?.id, "correspondentId" to correspondent?.id,
                "net" to sale[SalesTable.netAmount], "total" to total, "onAccount" to onAccount,
                "payments" to links.map { mapOf("method" to it.method, "amount" to it.amount, "receipt" to it.receiptRef) },
            ),
            locationId)
        val view = views.view(id)
        return SaleCompletion(view, invoice?.let { Ref.invoice(it.id) }, "/api/v1/cashier/sales/$id/receipt.pdf", view.changeGiven)
    }

    private data class Payment(val method: String, val amount: BigDecimal, val tendered: BigDecimal?, val terminalRef: String?, val payerReference: String?, val bankAccountId: UUID?)

    private fun normalise(input: SalePaymentInput, allowed: Set<String>): Payment {
        val method = input.method!!.trim().uppercase()
        if (method !in METHODS) throw ApiErrors.validationFailed(mapOf("payments.method" to "one of ${METHODS.joinToString()}"))
        if (method !in allowed) throw ApiErrors.paymentMethodNotAllowed(method, "${Channel.labelOf(method)} isn't available for this customer or till")
        val amount = Money.round(input.amount!!)
        val tendered = if (method == "CASH") Money.round(input.tendered ?: amount) else null
        if (tendered != null && tendered < amount) throw ApiErrors.validationFailed(mapOf("payments.tendered" to "can't be less than the cash amount"))
        if (method == "FUND_TRANSFER" && (input.receivingBankAccountId == null || input.payerReference.isNullOrBlank())) {
            throw ApiErrors.validationFailed(mapOf("payments" to "a fund transfer needs receivingBankAccountId and payerReference"))
        }
        return Payment(method, amount, tendered, input.terminalRef?.trim()?.ifEmpty { null }, input.payerReference?.trim()?.ifEmpty { null }, input.receivingBankAccountId)
    }

    /** Debits the wallet/deposit when it pays, then records the Account receipt and the sale payment. */
    private fun pay(p: Payment, sale: ResultRow, customer: CustomerRef?, ref: String, locationId: UUID, tillId: UUID, tillTerminal: String?): PaymentLink {
        val saleId = sale[SalesTable.id].value
        val ledgerEntry = if (p.method == "WALLET" || p.method == "DEPOSIT") {
            walletLedger.debit(
                LedgerPosting(
                    customerId = customer!!.id, balanceType = BalanceType.valueOf(p.method), entryType = LedgerEntryType.PURCHASE_DEBIT, amount = p.amount,
                    reference = ref, description = "POS sale $ref", paymentMethod = p.method, sourceModule = CashierSupport.SOURCE_MODULE,
                    sourceType = "POS_SALE", sourceId = saleId, locationId = locationId,
                ),
            )
        } else null
        val receipt = moneyLedger.recordReceipt(
            ReceiptRequest(
                locationId = locationId, cashAccountId = if (p.method == "CASH" || p.method == "POS") tillId else null,
                customerId = customer?.id, customerName = sale[SalesTable.customerName], channel = Channel.valueOf(p.method), amount = p.amount,
                sourceModule = CashierSupport.SOURCE_MODULE, sourceType = "POS_SALE", sourceId = saleId, sourceRef = ref,
                receivingBankAccountId = p.bankAccountId, terminalRef = p.terminalRef ?: if (p.method == "POS") tillTerminal else null,
                payerReference = p.payerReference, customerLedgerEntryId = ledgerEntry?.id, description = "POS sale $ref", entryDate = support.today(),
            ),
        )
        SalePaymentsTable.insert {
            it[tenantId] = support.tenantId()
            it[SalePaymentsTable.saleId] = saleId
            it[method] = p.method
            it[amount] = p.amount
            it[paymentReceiptId] = receipt.id
            it[receiptRef] = receipt.ref
            it[customerLedgerEntryId] = ledgerEntry?.id
            it[terminalRef] = p.terminalRef
            it[payerReference] = p.payerReference
            it[receivingBankAccountId] = p.bankAccountId
            it[createdAt] = Instant.now()
        }
        return PaymentLink(receipt.id, receipt.ref, p.method, p.amount, Instant.now(), ledgerEntry?.id)
    }

    /**
     * Every registered customer is invoiced at completion; a walk-in only when they ask (docs/09 §1.2-3).
     * The invoice is priced from the sale's own lines (same arithmetic), so the two totals agree.
     */
    private fun invoiceFor(sale: ResultRow, lines: List<ResultRow>, customer: CustomerRef?, buyer: SaleBuyerInput?, ref: String,
                           today: java.time.LocalDate, locationId: UUID, onAccount: BigDecimal, correspondentId: UUID?): InvoiceRef? {
        if (customer == null && buyer == null) return null
        val invoice = invoices.issue(
            InvoiceRequest(
                sourceType = SourceType.POS_SALE, sourceId = sale[SalesTable.id].value, sourceRef = ref, sourceDate = today, locationId = locationId,
                customerId = customer?.id, walkInBuyer = buyer?.let(::walkIn),
                paymentTermsDays = if (onAccount.signum() > 0) null else 0,
                lines = lines.map { invoiceLine(it) },
                correspondentId = correspondentId,
            ),
        )
        check((invoice.total - sale[SalesTable.total]).abs() < TOLERANCE) { "Invoice ${invoice.number} total ${invoice.total} differs from sale $ref total ${sale[SalesTable.total]}" }
        return invoice
    }

    /** The walk-in buyer block for an invoice. */
    fun walkIn(b: SaleBuyerInput) = WalkInBuyer(b.name!!.trim(), b.email?.trim()?.ifEmpty { null }, b.phone?.trim()?.ifEmpty { null },
        b.tin?.trim()?.ifEmpty { null }, b.address?.trim()?.ifEmpty { null }, b.stateCode?.trim()?.uppercase()?.ifEmpty { null })

    fun invoiceLine(line: ResultRow) = InvoiceLineRequest(
        stockItemId = line[SaleLinesTable.stockItemId], quantity = line[SaleLinesTable.quantity], unitPrice = line[SaleLinesTable.unitPrice],
        discountAmount = line[SaleLinesTable.lineDiscount] + line[SaleLinesTable.instantDiscountShare],
    )

    /** Where goods collected at the till leave from: the showroom's default selling area, else the showroom itself. */
    private fun sellingArea(locationId: UUID): UUID =
        LocationsTable.select(LocationsTable.defaultSellingLocationId).where { LocationsTable.id eq locationId }.single()[LocationsTable.defaultSellingLocationId] ?: locationId

    private fun codeOf(lines: List<ResultRow>, itemId: UUID) = lines.first { it[SaleLinesTable.stockItemId] == itemId }[SaleLinesTable.itemCode]

    private data class LinePlan(val line: ResultRow, val supplyNow: BigDecimal, val from: UUID)

    companion object {
        const val CREDIT_OVERRIDE = "cashier.credit.override"
        val METHODS = setOf("CASH", "POS", "FUND_TRANSFER", "WALLET", "DEPOSIT")
        private val TOLERANCE = BigDecimal("0.005")
    }
}
