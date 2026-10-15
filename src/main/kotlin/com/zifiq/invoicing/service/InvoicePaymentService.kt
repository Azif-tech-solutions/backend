package com.zifiq.invoicing.service

import com.zifiq.account.spi.Channel
import com.zifiq.account.spi.MoneyLedger
import com.zifiq.account.spi.ReceiptRequest
import com.zifiq.common.errors.ApiErrors
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.invoicing.dto.RecordPaymentRequest
import com.zifiq.invoicing.model.InvoicePaymentsTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.invoicing.spi.PaymentLink
import com.zifiq.platform.events.DomainEventRecord
import com.zifiq.platform.events.EventConsumer
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Payments against invoices (docs/08 §4.6). Every payment is an Account receipt — Account applies
 * the channel rules (cash posts now; POS, transfer and cheque wait for confirmation) — and the
 * invoice keeps a link to it. `amount_paid` is always recomputed from the links that are still
 * ACTIVE, never adjusted by hand, so a declined or voided receipt brings the balance back.
 */
@Service
class InvoicePaymentService(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
    private val moneyLedger: MoneyLedger,
    private val wallets: WalletLedger,
    private val notify: Notify,
) {
    data class Recorded(val paymentId: UUID, val receiptId: UUID, val receiptRef: String, val confirmationStatus: String)

    /** Records a payment the customer made against the invoice (HTTP). */
    fun record(invoiceId: UUID, request: RecordPaymentRequest): Recorded = tx.execute {
        val invoice = openForPayment(invoiceId, scoped = true)
        val amount = InvoiceSupport.money(request.amount!!)
        checkAmount(invoice, amount)
        val paidAt = request.paidAt ?: Instant.now()
        if (paidAt.isAfter(Instant.now().plusSeconds(300))) throw ApiErrors.validationFailed(mapOf("paidAt" to "can't be in the future"))
        val channel = Channel.valueOf(request.method!!)
        val number = invoice[InvoicesTable.number]!!
        val customerId = invoice[InvoicesTable.customerId]
        val locationId = invoice[InvoicesTable.locationId].value

        // Wallet and deposit: Customers debits the balance first (never below zero, PRD rule 5).
        val ledgerEntryId = if (channel == Channel.WALLET || channel == Channel.DEPOSIT) {
            customerId ?: throw ApiErrors.validationFailed(mapOf("method" to "a walk-in buyer has no wallet or deposit"))
            wallets.debit(
                LedgerPosting(
                    customerId = customerId, balanceType = if (channel == Channel.WALLET) BalanceType.WALLET else BalanceType.DEPOSIT,
                    entryType = LedgerEntryType.PURCHASE_DEBIT, amount = amount, reference = number, description = "Payment of $number",
                    paymentMethod = channel.name, sourceModule = InvoiceSupport.SOURCE_MODULE, sourceType = SOURCE_TYPE, sourceId = invoiceId, locationId = locationId,
                ),
            ).id
        } else null

        val receipt = moneyLedger.recordReceipt(
            ReceiptRequest(
                locationId = locationId, cashAccountId = request.cashAccountId, customerId = customerId, customerName = invoice[InvoicesTable.buyerName],
                channel = channel, amount = amount, sourceModule = InvoiceSupport.SOURCE_MODULE, sourceType = SOURCE_TYPE, sourceId = invoiceId, sourceRef = number,
                receivingBankAccountId = request.receivingBankAccountId, terminalRef = request.terminalRef?.trim()?.ifEmpty { null },
                payerReference = request.payerReference?.trim()?.ifEmpty { null }, customerLedgerEntryId = ledgerEntryId,
                description = "Payment of $number", entryDate = LocalDate.ofInstant(paidAt, support.zone()),
            ),
        )
        val paymentId = insert(invoiceId, channel.name, amount, receipt.id, receipt.ref, ledgerEntryId, paidAt, "RECORDED", support.userId())
        afterPayment(invoice, amount, receipt.ref, channel.name)
        Recorded(paymentId, receipt.id, receipt.ref, receipt.confirmationStatus.name)
    }

    /** spi: a POS checkout payment Sales & POS already receipted (caller's transaction). */
    fun attach(invoiceId: UUID, link: PaymentLink) {
        val invoice = openForPayment(invoiceId, scoped = false)
        val amount = InvoiceSupport.money(link.amount)
        checkAmount(invoice, amount)
        val method = runCatching { Channel.valueOf(link.method) }.getOrElse { throw ApiErrors.validationFailed(mapOf("method" to "unknown payment method ${link.method}")) }
        insert(invoiceId, method.name, amount, link.receiptId, link.receiptRef, link.customerLedgerEntryId, link.paidAt, "POS_CHECKOUT", support.userId())
        afterPayment(invoice, amount, link.receiptRef, method.name)
    }

    /**
     * Reverses a payment recorded here by mistake: Account voids its receipt (undoing what it posted),
     * a wallet/deposit debit is credited back, and every link to the receipt is marked VOIDED.
     * Payments taken at a POS checkout are reversed by voiding the sale (doc 09).
     */
    fun reverse(invoiceId: UUID, paymentId: UUID, reason: String): UUID = tx.execute {
        val invoice = invoiceForUpdate(invoiceId)
        support.assertVisible(invoice[InvoicesTable.locationId].value)
        support.assertAccess(invoice[InvoicesTable.locationId].value)
        val payment = InvoicePaymentsTable.selectAll()
            .where { (InvoicePaymentsTable.id eq paymentId) and (InvoicePaymentsTable.invoiceId eq invoiceId) and (InvoicePaymentsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.invoicePaymentNotFound()
        if (payment[InvoicePaymentsTable.receiptStatus] != "ACTIVE") throw ApiErrors.alreadyReversed()
        if (payment[InvoicePaymentsTable.paymentSource] == "POS_CHECKOUT") throw ApiErrors.reverseInSourceModule("cashier")
        val receiptId = payment[InvoicePaymentsTable.paymentReceiptId]
        val original = InvoicePaymentsTable.selectAll()
            .where { (InvoicePaymentsTable.paymentReceiptId eq receiptId) and (InvoicePaymentsTable.paymentSource eq "POS_CHECKOUT") }.any()
        if (original) throw ApiErrors.reverseInSourceModule("cashier")
        payment[InvoicePaymentsTable.customerLedgerEntryId]?.let { wallets.reverse(it, reason.trim(), InvoiceSupport.SOURCE_MODULE) }
        moneyLedger.voidReceipt(receiptId, reason.trim(), InvoiceSupport.SOURCE_MODULE)
        markReceipt(receiptId, "VOIDED", reason.trim())
        invoiceId
    }

    /** Sets every link to [receiptId] to [status] and recomputes those invoices; returns the invoices that changed. Idempotent. */
    fun markReceipt(receiptId: UUID, status: String, reason: String?): List<UUID> {
        val links = InvoicePaymentsTable.selectAll()
            .where { (InvoicePaymentsTable.tenantId eq support.tenantId()) and (InvoicePaymentsTable.paymentReceiptId eq receiptId) and (InvoicePaymentsTable.receiptStatus eq "ACTIVE") }
            .toList()
        if (links.isEmpty()) return emptyList()
        InvoicePaymentsTable.update({ InvoicePaymentsTable.id inList links.map { it[InvoicePaymentsTable.id].value } }) {
            it[receiptStatus] = status
            it[reversalReason] = reason?.take(300)
        }
        val invoiceIds = links.map { it[InvoicePaymentsTable.invoiceId].value }.distinct()
        invoiceIds.forEach(::recompute)
        val first = links.first()
        val amount = first[InvoicePaymentsTable.amount]
        val ref = first[InvoicePaymentsTable.receiptRef]
        invoiceIds.forEach { id ->
            val invoice = row(id)
            val number = invoice[InvoicesTable.number] ?: return@forEach
            val balance = balanceOf(invoice)
            val locationId = invoice[InvoicesTable.locationId].value
            val word = if (status == "DECLINED") "declined" else "voided"
            val eventId = support.record("invoicing.payment.reversed", id, number,
                "$ref $word — $number balance ${InvoiceSupport.naira(balance)} again",
                mapOf("receiptId" to receiptId, "receiptRef" to ref, "amount" to amount, "status" to status), locationId)
            if (invoice[InvoicesTable.status] == "ISSUED") {
                notify.holdersOf(
                    permission = "invoicing.receivables.read", locationId = locationId, category = NotificationCategory.ALERT,
                    title = "Payment $word — $number", body = "$ref (${InvoiceSupport.naira(amount)}) $word: $number balance ${InvoiceSupport.naira(balance)} again.",
                    linkType = "invoice", linkRef = id.toString(), sourceEventId = eventId, exclude = runCatching { support.userId() }.getOrNull(),
                )
            }
        }
        return invoiceIds
    }

    /** Void-and-reissue: the old invoice's active payments follow the same receipts onto the replacement. */
    fun transfer(fromInvoiceId: UUID, toInvoiceId: UUID) {
        val active = InvoicePaymentsTable.selectAll()
            .where { (InvoicePaymentsTable.invoiceId eq fromInvoiceId) and (InvoicePaymentsTable.receiptStatus eq "ACTIVE") }.toList()
        active.forEach { p ->
            insert(toInvoiceId, p[InvoicePaymentsTable.method], p[InvoicePaymentsTable.amount], p[InvoicePaymentsTable.paymentReceiptId],
                p[InvoicePaymentsTable.receiptRef], p[InvoicePaymentsTable.customerLedgerEntryId], p[InvoicePaymentsTable.paidAt], "REISSUE_TRANSFER", support.userId())
        }
        recompute(toInvoiceId)
    }

    /** A sale void (doc 09): its receipts were already voided by the caller, so the links follow. */
    fun voidAll(invoiceId: UUID, reason: String) {
        InvoicePaymentsTable.update({ (InvoicePaymentsTable.invoiceId eq invoiceId) and (InvoicePaymentsTable.receiptStatus eq "ACTIVE") }) {
            it[receiptStatus] = "VOIDED"
            it[reversalReason] = reason.take(300)
        }
        recompute(invoiceId)
    }

    fun activePaid(invoiceId: UUID): BigDecimal =
        InvoicePaymentsTable.select(InvoicePaymentsTable.amount)
            .where { (InvoicePaymentsTable.invoiceId eq invoiceId) and (InvoicePaymentsTable.receiptStatus eq "ACTIVE") }
            .fold(BigDecimal.ZERO) { sum, it -> sum + it[InvoicePaymentsTable.amount] }.setScale(2)

    /** amount_paid = Σ ACTIVE links; last_payment_at = the latest of them. */
    fun recompute(invoiceId: UUID) {
        val active = InvoicePaymentsTable.select(InvoicePaymentsTable.amount, InvoicePaymentsTable.paidAt)
            .where { (InvoicePaymentsTable.invoiceId eq invoiceId) and (InvoicePaymentsTable.receiptStatus eq "ACTIVE") }.toList()
        InvoicesTable.update({ InvoicesTable.id eq invoiceId }) {
            it[amountPaid] = active.fold(BigDecimal.ZERO) { sum, p -> sum + p[InvoicePaymentsTable.amount] }.setScale(2)
            it[lastPaymentAt] = active.maxOfOrNull { p -> p[InvoicePaymentsTable.paidAt] }
        }
    }

    // ---- Internals (caller's transaction) ----

    private fun openForPayment(invoiceId: UUID, scoped: Boolean): ResultRow {
        val invoice = invoiceForUpdate(invoiceId)
        if (scoped) {
            support.assertVisible(invoice[InvoicesTable.locationId].value)
            support.assertAccess(invoice[InvoicesTable.locationId].value)
        }
        if (invoice[InvoicesTable.status] != "ISSUED") throw ApiErrors.invoiceNotIssued()
        if (balanceOf(invoice) <= support.tolerance()) throw ApiErrors.invoiceAlreadyPaid()
        return invoice
    }

    /** An invoice is never overpaid (rule I11). */
    private fun checkAmount(invoice: ResultRow, amount: BigDecimal) {
        val balance = balanceOf(invoice)
        if (amount > balance) throw ApiErrors.overpayment(balance)
    }

    private fun afterPayment(invoice: ResultRow, amount: BigDecimal, receiptRef: String, method: String) {
        val id = invoice[InvoicesTable.id].value
        recompute(id)
        val updated = row(id)
        val number = updated[InvoicesTable.number]!!
        val balance = balanceOf(updated)
        val locationId = updated[InvoicesTable.locationId].value
        support.record("invoicing.payment.recorded", id, number,
            "$receiptRef: ${InvoiceSupport.naira(amount)} ${InvoiceQueryService.methodLabel(method).lowercase()} against $number, balance ${InvoiceSupport.naira(balance)}",
            mapOf("amount" to amount, "method" to method, "receiptRef" to receiptRef, "balance" to balance), locationId)
        if (balance <= support.tolerance()) {
            support.record("invoicing.invoice.paid", id, number, "$number paid in full", mapOf("total" to updated[InvoicesTable.taxInclusiveAmount]), locationId)
        }
    }

    private fun insert(invoiceId: UUID, method: String, amount: BigDecimal, receiptId: UUID, receiptRef: String, ledgerEntryId: UUID?, paidAt: Instant, source: String, userId: UUID): UUID =
        InvoicePaymentsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[InvoicePaymentsTable.invoiceId] = invoiceId
            it[InvoicePaymentsTable.method] = method
            it[InvoicePaymentsTable.amount] = amount
            it[paymentReceiptId] = receiptId
            it[InvoicePaymentsTable.receiptRef] = receiptRef
            it[receiptStatus] = "ACTIVE"
            it[customerLedgerEntryId] = ledgerEntryId
            it[nrsPaymentMeansCode] = NRS_PAYMENT_MEANS[method]
            it[InvoicePaymentsTable.paidAt] = paidAt
            it[recordedByUserId] = userId
            it[paymentSource] = source
            it[createdAt] = Instant.now()
        }.value

    private fun invoiceForUpdate(id: UUID): ResultRow =
        InvoicesTable.selectAll().where { (InvoicesTable.id eq id) and (InvoicesTable.tenantId eq support.tenantId()) }.forUpdate().firstOrNull()
            ?: throw ApiErrors.invoiceNotFound()

    private fun row(id: UUID): ResultRow = InvoicesTable.selectAll().where { InvoicesTable.id eq id }.single()

    private fun balanceOf(row: ResultRow): BigDecimal =
        (row[InvoicesTable.taxInclusiveAmount] - row[InvoicesTable.amountPaid] - row[InvoicesTable.amountCredited]).setScale(2)

    companion object {
        const val SOURCE_TYPE = "INVOICE_PAYMENT"

        /** NRS payment_means_code (docs/08 §4.12): POS has no code in the V6 list; WALLET/DEPOSIT are internal settlement, not payment means. */
        val NRS_PAYMENT_MEANS = mapOf("CASH" to "10", "CHEQUE" to "20", "FUND_TRANSFER" to "30")
    }
}

/**
 * Receipt reconciliation (docs/08 §4.6, docs/03 §7.6): when Accounts declines a POS / transfer /
 * cheque receipt, or a receipt is voided, the invoice payments that point at it follow and the
 * balance comes back. Idempotent: a receipt already mirrored is skipped.
 */
@Component
class ReceiptReconciler(private val payments: InvoicePaymentService) : EventConsumer {
    override val name = "invoicing.receipt-reconciler"
    override val eventTypes = setOf("account.receipt.declined", "account.receipt.voided")

    override fun handle(event: DomainEventRecord) {
        val status = if (event.eventType == "account.receipt.declined") "DECLINED" else "VOIDED"
        payments.markReceipt(event.aggregateId, status, event.summary.substringAfter(": ", event.summary).take(300))
    }
}
