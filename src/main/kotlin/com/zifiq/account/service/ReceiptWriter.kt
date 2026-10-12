package com.zifiq.account.service

import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.PaymentReceiptsTable
import com.zifiq.account.repository.LedgerPosting
import com.zifiq.account.spi.Channel
import com.zifiq.account.spi.ConfirmationStatus
import com.zifiq.account.spi.ReceiptRef
import com.zifiq.account.spi.ReceiptRequest
import com.zifiq.account.spi.RefundRequest
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component

/**
 * What happens to a document when Accounts approves, declines or voids the receipt that paid for
 * it — implemented by the module-internal owners of receipt-backed documents (wallet fundings).
 * Runs in the caller's transaction.
 */
interface ReceiptSourceHandler {
    val sourceTypes: Set<String>
    fun approved(receipt: ResultRow)
    fun declined(receipt: ResultRow, note: String)
    fun voided(receipt: ResultRow, reason: String)
}

/**
 * Writes `payment_receipts` and applies the channel rules (docs/07 §2.7): CASH posts to the till at
 * once; POS, FUND_TRANSFER and CHEQUE wait for Accounts and post to the bank on approval; WALLET and
 * DEPOSIT were paid from the Customers ledger by the caller. Every receipt is registered as a
 * RECEIPT document. Caller's transaction.
 */
@Component
class ReceiptWriter(
    private val support: AccountSupport,
    private val moves: MoneyMoves,
    private val sequences: DocumentSequenceService,
    private val documents: DocumentRegistry,
) {
    fun record(request: ReceiptRequest): ReceiptRef {
        val amount = AccountSupport.money(request.amount)
        if (amount.signum() <= 0) throw ApiErrors.validationFailed(mapOf("amount" to "must be positive"))
        request.locationId?.let { support.validateLocation(it) }
        if ((request.sourceType == "OTHER_INCOME") != (request.incomeCategory != null)) {
            throw ApiErrors.validationFailed(mapOf("incomeCategory" to "is required for other income, and only for it"))
        }

        var till: MoneyAccount? = null
        var bankId: UUID? = request.receivingBankAccountId
        val status = when (request.channel) {
            Channel.CASH -> {
                till = tillAt(request.cashAccountId, request.locationId)
                ConfirmationStatus.NOT_REQUIRED
            }
            Channel.POS -> {
                till = request.cashAccountId?.let { tillAt(it, request.locationId) }
                val settlement = till?.id?.let(::settlementAccount)
                bankId = bankId ?: settlement ?: throw ApiErrors.invalidPayingAccount("A card payment needs a till with a settlement bank account, or receivingBankAccountId")
                moves.bank(bankId, checkAccess = false)
                ConfirmationStatus.PENDING
            }
            Channel.FUND_TRANSFER, Channel.CHEQUE -> {
                bankId ?: throw ApiErrors.invalidPayingAccount("A ${request.channel.name.lowercase().replace('_', ' ')} needs receivingBankAccountId")
                moves.bank(bankId, checkAccess = false)
                ConfirmationStatus.PENDING
            }
            Channel.WALLET, Channel.DEPOSIT -> {
                request.customerLedgerEntryId ?: throw ApiErrors.validationFailed(mapOf("customerLedgerEntryId" to "a wallet or deposit payment needs the Customers ledger entry that paid it"))
                ConfirmationStatus.NOT_REQUIRED
            }
        }

        val ref = sequences.next("RCT")
        val id = insert(ref, "RECEIPT", request.locationId, till?.id, request.customerId, request.customerName, request.channel, amount,
            request.sourceModule, request.sourceType, request.sourceId, request.sourceRef, bankId, request.terminalRef, request.payerReference,
            request.customerLedgerEntryId, status, request.incomeCategory, request.description)
        if (request.channel == Channel.CASH) {
            val posted = moves.into(till!!, amount, LedgerPosting(ref, "Receipt ${request.sourceRef}" + (request.customerName?.let { " — $it" } ?: ""), "RECEIPT", id,
                entryDate = request.entryDate ?: support.today(), userId = support.userId()))
            PaymentReceiptsTable.update({ PaymentReceiptsTable.id eq id }) { it[cashTransactionId] = posted.cashTransactionId }
        }
        documents.register(ref, DocumentType.RECEIPT, SOURCE_MODULE, id, request.locationId, request.customerName ?: "Walk-in", request.customerId, amount)
        return ReceiptRef(id, ref, request.channel, amount, status)
    }

    /** A refund is the company paying, so it posts at once: cash out, a bank debit, or (WALLET) nothing — the caller credited the wallet. */
    fun refund(request: RefundRequest): ReceiptRef {
        val amount = AccountSupport.money(request.amount)
        request.locationId?.let { support.validateLocation(it) }
        val ref = sequences.next("RCT")
        when (request.channel) {
            Channel.CASH -> {
                val till = tillAt(request.cashAccountId, request.locationId)
                val id = insert(ref, "REFUND", request.locationId, till.id, request.customerId, request.customerName, request.channel, amount, request.sourceModule,
                    request.sourceType, request.sourceId, request.sourceRef, null, null, null, null, ConfirmationStatus.NOT_REQUIRED, null, request.description)
                val posted = moves.out(till, amount, LedgerPosting(ref, "Refund ${request.sourceRef}", "REFUND", id, entryDate = support.today(), userId = support.userId()))
                PaymentReceiptsTable.update({ PaymentReceiptsTable.id eq id }) { it[cashTransactionId] = posted.cashTransactionId }
                return finish(ref, id, request, amount)
            }
            Channel.FUND_TRANSFER -> {
                val bank = moves.bank(request.payingBankAccountId ?: throw ApiErrors.invalidPayingAccount("A refund by transfer needs payingBankAccountId"))
                val id = insert(ref, "REFUND", request.locationId, null, request.customerId, request.customerName, request.channel, amount, request.sourceModule,
                    request.sourceType, request.sourceId, request.sourceRef, bank.id, null, null, null, ConfirmationStatus.NOT_REQUIRED, null, request.description)
                val bankSource = if (request.sourceType == "DEPOSIT_REFUND") "DEPOSIT_REFUND" else "RECEIPT_REVERSED"
                val posted = moves.out(bank, amount, LedgerPosting(ref, "Refund ${request.sourceRef}", bankSource, id, entryDate = support.today(), userId = support.userId()))
                PaymentReceiptsTable.update({ PaymentReceiptsTable.id eq id }) { it[bankTransactionId] = posted.bankTransactionId }
                return finish(ref, id, request, amount)
            }
            Channel.WALLET -> {
                request.customerLedgerEntryId ?: throw ApiErrors.validationFailed(mapOf("customerLedgerEntryId" to "a refund to the wallet needs the Customers ledger credit"))
                val id = insert(ref, "REFUND", request.locationId, null, request.customerId, request.customerName, request.channel, amount, request.sourceModule,
                    request.sourceType, request.sourceId, request.sourceRef, null, null, null, request.customerLedgerEntryId, ConfirmationStatus.NOT_REQUIRED, null, request.description)
                return finish(ref, id, request, amount)
            }
            else -> throw ApiErrors.refundChannelNotSupported(request.channel.name)
        }
    }

    /** On approval: the money is in the bank now. */
    fun postApproval(receipt: ResultRow) {
        val id = receipt[PaymentReceiptsTable.id].value
        val bank = moves.existing(true, receipt[PaymentReceiptsTable.receivingBankAccountId]!!)
        val posted = moves.into(bank, receipt[PaymentReceiptsTable.amount], LedgerPosting(receipt[PaymentReceiptsTable.ref],
            "${receipt[PaymentReceiptsTable.channel].lowercase().replace('_', ' ')} receipt ${receipt[PaymentReceiptsTable.sourceRef]} confirmed" +
                (receipt[PaymentReceiptsTable.payerReference]?.let { " ($it)" } ?: ""), "RECEIPT_CONFIRMED", id, entryDate = support.today(), userId = support.userId()))
        PaymentReceiptsTable.update({ PaymentReceiptsTable.id eq id }) { it[bankTransactionId] = posted.bankTransactionId }
    }

    /** Undoes whatever a receipt posted (a void): cash back out of the till, or a bank debit. */
    fun undoPosting(receipt: ResultRow, reason: String) {
        val id = receipt[PaymentReceiptsTable.id].value
        val ref = receipt[PaymentReceiptsTable.ref]
        val amount = receipt[PaymentReceiptsTable.amount]
        val text = "Void of $ref: $reason"
        val refund = receipt[PaymentReceiptsTable.kind] == "REFUND"
        receipt[PaymentReceiptsTable.cashTransactionId]?.let {
            val till = moves.existing(false, receipt[PaymentReceiptsTable.cashAccountId]!!)
            val posting = LedgerPosting(ref, text, if (refund) "REFUND" else "RECEIPT", id, entryDate = support.today(), userId = support.userId())
            if (refund) moves.into(till, amount, posting) else moves.out(till, amount, posting)
        }
        receipt[PaymentReceiptsTable.bankTransactionId]?.let {
            val bank = moves.existing(true, receipt[PaymentReceiptsTable.receivingBankAccountId]!!)
            val posting = LedgerPosting(ref, text, "RECEIPT_REVERSED", id, entryDate = support.today(), userId = support.userId())
            if (refund) moves.into(bank, amount, posting) else moves.out(bank, amount, posting)
        }
    }

    // ---- Helpers ----

    /** CASH and POS go through a till or vault at the receipt's location (head office's when there is none). */
    private fun tillAt(cashAccountId: UUID?, locationId: UUID?): MoneyAccount {
        val id = cashAccountId ?: throw ApiErrors.invalidPayingAccount("Cash needs cashAccountId (a till or vault)")
        val till = moves.cash(id, setOf("TILL", "VAULT"))
        if (till.locationId != locationId) throw ApiErrors.invalidPayingAccount("${till.code} is not at this location")
        return till
    }

    private fun settlementAccount(cashAccountId: UUID): UUID? =
        CashAccountsTable.select(CashAccountsTable.posSettlementBankAccountId).where { CashAccountsTable.id eq cashAccountId }.single()[CashAccountsTable.posSettlementBankAccountId]

    private fun finish(ref: String, id: UUID, request: RefundRequest, amount: BigDecimal): ReceiptRef {
        documents.register(ref, DocumentType.RECEIPT, SOURCE_MODULE, id, request.locationId, request.customerName ?: "Walk-in", request.customerId, amount)
        return ReceiptRef(id, ref, request.channel, amount, ConfirmationStatus.NOT_REQUIRED)
    }

    private fun insert(
        ref: String, kind: String, locationId: UUID?, cashAccountId: UUID?, customerId: UUID?, customerName: String?, channel: Channel, amount: BigDecimal,
        sourceModule: String, sourceType: String, sourceId: UUID, sourceRef: String, bankId: UUID?, terminalRef: String?, payerReference: String?,
        ledgerEntryId: UUID?, status: ConfirmationStatus, incomeCategory: String?, description: String?,
    ): UUID = PaymentReceiptsTable.insertAndGetId {
        it[tenantId] = EntityID(support.tenantId(), TenantsTable)
        it[PaymentReceiptsTable.ref] = ref
        it[PaymentReceiptsTable.kind] = kind
        it[PaymentReceiptsTable.locationId] = locationId
        it[PaymentReceiptsTable.cashAccountId] = cashAccountId
        it[cashierUserId] = support.userId()
        it[PaymentReceiptsTable.customerId] = customerId
        it[PaymentReceiptsTable.customerName] = customerName?.trim()?.take(200)
        it[PaymentReceiptsTable.channel] = channel.name
        it[PaymentReceiptsTable.amount] = amount
        it[PaymentReceiptsTable.sourceModule] = sourceModule
        it[PaymentReceiptsTable.sourceType] = sourceType
        it[PaymentReceiptsTable.sourceId] = sourceId
        it[PaymentReceiptsTable.sourceRef] = sourceRef
        it[PaymentReceiptsTable.incomeCategory] = incomeCategory
        it[PaymentReceiptsTable.description] = description?.trim()?.take(300)
        it[receivingBankAccountId] = bankId
        it[PaymentReceiptsTable.terminalRef] = terminalRef?.trim()?.ifEmpty { null }
        it[PaymentReceiptsTable.payerReference] = payerReference?.trim()?.ifEmpty { null }
        it[customerLedgerEntryId] = ledgerEntryId
        it[confirmationStatus] = status.name
        it[PaymentReceiptsTable.status] = "ISSUED"
        it[receivedAt] = Instant.now()
    }.value

    fun row(id: UUID, forUpdate: Boolean = false): ResultRow {
        val query = PaymentReceiptsTable.selectAll().where { (PaymentReceiptsTable.id eq id) and (PaymentReceiptsTable.tenantId eq support.tenantId()) }
        if (forUpdate) query.forUpdate()
        return query.firstOrNull() ?: throw ApiErrors.receiptNotFound()
    }

    companion object {
        const val SOURCE_MODULE = "account"
    }
}
