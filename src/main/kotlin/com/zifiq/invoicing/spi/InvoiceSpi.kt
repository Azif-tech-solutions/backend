package com.zifiq.invoicing.spi

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * The Electronic Invoicing module's published interface (docs/08 §5): the only part of `invoicing`
 * other modules may use (Spring Modulith named interface "spi"). Sales & POS (doc 09) assembles the
 * lines and calls [InvoiceIssuer] to issue or draft an invoice; it never writes invoice tables
 * itself. Everything runs in the caller's tenant-scoped transaction (docs/03 §2.4).
 *
 * Payments are Account receipts: an invoice keeps a link to each one and mirrors its status
 * (DECLINED / VOIDED) through the receipt reconciler.
 */

enum class SourceType { POS_SALE, PRO_FORMA }

data class WalkInBuyer(
    val name: String,
    val email: String? = null,
    val phone: String? = null,
    val tin: String? = null,
    val address: String? = null,
    val stateCode: String? = null,
)

data class InvoiceLineRequest(
    val stockItemId: UUID,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val discountAmount: BigDecimal = BigDecimal.ZERO,
    val discountRate: BigDecimal? = null,
)

data class InvoiceRequest(
    val sourceType: SourceType,
    val sourceId: UUID,
    val sourceRef: String,
    val sourceDate: LocalDate,
    val proFormaRef: String? = null,
    val locationId: UUID,
    /** Exactly one of [customerId] / [walkInBuyer] is set. */
    val customerId: UUID? = null,
    val walkInBuyer: WalkInBuyer? = null,
    val issueDate: LocalDate? = null,
    val paymentTermsDays: Int? = null,
    val paymentTermsNote: String? = null,
    val lines: List<InvoiceLineRequest>,
    val correspondentId: UUID? = null,
)

data class InvoiceRef(val id: UUID, val number: String?, val status: String, val total: BigDecimal, val balance: BigDecimal)

data class OpenInvoiceRef(val id: UUID, val number: String, val issueDate: LocalDate, val dueDate: LocalDate?, val total: BigDecimal, val balance: BigDecimal)

data class InvoiceLineView(
    val lineNo: Int,
    val stockItemId: UUID,
    val itemCode: String,
    val itemName: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineNet: BigDecimal,
    val vatAmount: BigDecimal,
    val lineTotal: BigDecimal,
)

/**
 * A payment Sales & POS has already receipted through Account's MoneyLedger (doc 09 checkout).
 * [method] is the receipt's channel (CASH, POS, FUND_TRANSFER, CHEQUE, WALLET, DEPOSIT);
 * [customerLedgerEntryId] is the wallet/deposit debit for WALLET / DEPOSIT.
 */
data class PaymentLink(
    val receiptId: UUID,
    val receiptRef: String,
    val method: String,
    val amount: BigDecimal,
    val paidAt: Instant,
    val customerLedgerEntryId: UUID? = null,
)

/** One credit-note line for output-VAT deductions (doc 10). */
data class CreditNoteLineView(
    val date: LocalDate,
    val creditNoteNumber: String,
    val invoiceNumber: String,
    val locationId: UUID,
    val buyerName: String,
    val buyerTin: String?,
    val lineNet: BigDecimal,
    val vatRate: BigDecimal,
    val vatAmount: BigDecimal,
)

interface InvoiceIssuer {
    /** Issues immediately (number assigned): POS checkout, walk-in on request. */
    fun issue(req: InvoiceRequest): InvoiceRef

    /** Saves a draft (pro-forma conversion); no number until it is issued. */
    fun createDraft(req: InvoiceRequest): InvoiceRef

    /** Links a payment the caller already receipted (POS checkout) to an issued invoice; never more than its balance. */
    fun attachPayment(invoiceId: UUID, payment: PaymentLink)

    /**
     * A sale void in doc 09: the caller has already voided every receipt, so this voids the invoice
     * and its payments without the INVOICE_HAS_PAYMENTS guard.
     */
    fun voidForSource(sourceType: String, sourceId: UUID, reason: String)

    /** Pro-forma conversion abandoned before issue (doc 09 §4.4): discards the draft. */
    fun discardDraftForSource(sourceType: String, sourceId: UUID)

    /** Voids the live invoice for the source and issues a corrected replacement linked to it. */
    fun reissueForSource(sourceType: String, sourceId: UUID, corrected: InvoiceRequest, reason: String): InvoiceRef
}

interface Receivables {
    fun outstandingFor(customerId: UUID): BigDecimal

    fun openInvoicesFor(customerId: UUID): List<OpenInvoiceRef>
}

interface InvoiceQueries {
    fun byNumber(number: String): InvoiceRef?

    fun bySource(sourceType: String, sourceId: UUID): InvoiceRef?

    fun linesOf(invoiceId: UUID): List<InvoiceLineView>

    /** Credit-note lines issued between [from] and [to] (inclusive), optionally at one location. */
    fun creditNoteLines(locationId: UUID?, from: LocalDate, to: LocalDate): List<CreditNoteLineView>

    /**
     * Issued documents that snapshot [stockItemId]'s name and would keep the original after a
     * data-quality rename: lines of non-draft invoices plus credit-note lines (docs/10 §4.8).
     */
    fun issuedLineCount(stockItemId: UUID): Int
}

interface InvoiceWaybillLinks {
    fun link(invoiceId: UUID, waybillId: UUID, waybillRef: String, waybillDate: LocalDate)
}
