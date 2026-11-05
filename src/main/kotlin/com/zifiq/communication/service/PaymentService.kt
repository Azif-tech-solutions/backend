package com.zifiq.communication.service

import com.zifiq.account.spi.CommissionPayoutRequest
import com.zifiq.account.spi.CommissionPayouts
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.communication.dto.BeneficiaryRef
import com.zifiq.communication.dto.CommissionPaymentRow
import com.zifiq.communication.dto.PayAllRequest
import com.zifiq.communication.dto.PayCommissionRequest
import com.zifiq.communication.dto.ReversePaymentRequest
import com.zifiq.communication.model.CommissionPaymentsTable
import com.zifiq.communication.model.CommissionRunBeneficiariesTable
import com.zifiq.communication.model.CommissionRunsTable
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.ZoneId
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Commission payments (docs/12 §4.3): partial payouts against an approved run, moving money through
 * Account (cash/bank via [CommissionPayouts]) or the customer wallet (dealers only, via
 * [WalletLedger]). Outstanding = earned − Σ PAID. A payment can be reversed the same day.
 */
@Service
class PaymentService(
    private val support: CommunicationSupport,
    private val tx: TenantScopedTransaction,
    private val sequences: DocumentSequenceService,
    private val payouts: CommissionPayouts,
    private val wallet: WalletLedger,
) {
    fun pay(request: PayCommissionRequest): CommissionPaymentRow = tx.execute {
        val run = approvedRun(request.runId!!)
        val bt = run[CommissionRunsTable.beneficiaryType]
        val beneficiary = beneficiaryRow(request.runId, request.beneficiaryId!!)
        val amt = Money.round(request.amount!!)
        val outstanding = outstandingFor(request.runId, request.beneficiaryId, beneficiary[CommissionRunBeneficiariesTable.earned])
        if (amt > outstanding) throw ApiErrors.paymentExceedsOutstanding(outstanding)
        val minimum = support.settings().decimal(CommunicationSettings.MINIMUM_PAYOUT)
        if (amt < minimum) throw ApiErrors.paymentBelowMinimum(minimum)
        if (request.method == "WALLET" && bt != "DEALER") throw ApiErrors.walletNotAvailableForPayee()
        payInternal(run, bt, request.beneficiaryId, beneficiary[CommissionRunBeneficiariesTable.beneficiaryName], amt, request.method, request.bankAccountId, request.cashAccountId, request.paidVia, request.note)
    }

    fun payAll(runId: UUID, request: PayAllRequest): List<CommissionPaymentRow> = tx.execute {
        val run = approvedRun(runId)
        val bt = run[CommissionRunsTable.beneficiaryType]
        if (request.method == "WALLET" && bt != "DEALER") throw ApiErrors.walletNotAvailableForPayee()
        val minimum = support.settings().decimal(CommunicationSettings.MINIMUM_PAYOUT)
        CommissionRunBeneficiariesTable.selectAll().where { CommissionRunBeneficiariesTable.runId eq runId }.toList().mapNotNull { b ->
            val bid = b[CommissionRunBeneficiariesTable.beneficiaryId]
            val outstanding = outstandingFor(runId, bid, b[CommissionRunBeneficiariesTable.earned])
            if (outstanding < minimum || outstanding.signum() <= 0) return@mapNotNull null
            payInternal(run, bt, bid, b[CommissionRunBeneficiariesTable.beneficiaryName], outstanding, request.method, request.bankAccountId, request.cashAccountId, null, null)
        }
    }

    fun reverse(paymentId: UUID, request: ReversePaymentRequest): CommissionPaymentRow = tx.execute {
        val p = CommissionPaymentsTable.selectAll().where { (CommissionPaymentsTable.id eq paymentId) and (CommissionPaymentsTable.tenantId eq support.tenantId()) }.forUpdate().firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("id" to "payment not found"))
        if (p[CommissionPaymentsTable.status] != "PAID") throw ApiErrors.paymentNotReversible()
        if (p[CommissionPaymentsTable.paidAt].atZone(zone()).toLocalDate() != support.today()) throw ApiErrors.paymentNotReversible()
        val ref = p[CommissionPaymentsTable.ref]
        when (p[CommissionPaymentsTable.method]) {
            "WALLET" -> p[CommissionPaymentsTable.customerLedgerEntryId]?.let { wallet.reverse(it, request.reason!!.trim(), SOURCE_MODULE) }
            else -> payouts.reversePayout(
                CommissionPayoutRequest(p[CommissionPaymentsTable.method], p[CommissionPaymentsTable.cashAccountId], p[CommissionPaymentsTable.bankAccountId], p[CommissionPaymentsTable.amount], ref, "", paymentId),
                request.reason!!.trim(),
            )
        }
        CommissionPaymentsTable.update({ CommissionPaymentsTable.id eq paymentId }) {
            it[status] = "REVERSED"; it[reversedReason] = request.reason!!.trim(); it[reversedByUserId] = support.userId()
        }
        support.record("communication.commission.payment-reversed", "commission_payment", paymentId, ref, "Commission payment $ref reversed", emptyMap())
        row(paymentId)
    }

    fun list(beneficiaryType: String?, beneficiaryId: UUID?, runId: UUID?, method: String?, paging: PageRequest): PageResult<CommissionPaymentRow> = tx.execute {
        var cond = CommissionPaymentsTable.tenantId eq support.tenantId()
        beneficiaryType?.trim()?.uppercase()?.let { cond = cond and (CommissionPaymentsTable.beneficiaryType eq it) }
        beneficiaryId?.let { cond = cond and (CommissionPaymentsTable.beneficiaryId eq it) }
        runId?.let { cond = cond and (CommissionPaymentsTable.runId eq it) }
        method?.trim()?.uppercase()?.let { cond = cond and (CommissionPaymentsTable.method eq it) }
        Paging.fetch(CommissionPaymentsTable.selectAll().where(cond).orderBy(CommissionPaymentsTable.paidAt to SortOrder.DESC, CommissionPaymentsTable.id to SortOrder.DESC), paging) { toRow(it) }
    }

    // ---- internals ----

    private fun payInternal(
        run: ResultRow, bt: String, beneficiaryId: UUID, beneficiaryName: String, amount: BigDecimal, method: String,
        bankAccountId: UUID?, cashAccountId: UUID?, paidVia: String?, note: String?,
    ): CommissionPaymentRow {
        val ref = sequences.next("CP")
        val paymentId = CommissionPaymentsTable.insertAndGetId {
            it[tenantId] = support.tenantId(); it[CommissionPaymentsTable.ref] = ref; it[runId] = run[CommissionRunsTable.id].value
            it[beneficiaryType] = bt; it[CommissionPaymentsTable.beneficiaryId] = beneficiaryId; it[CommissionPaymentsTable.beneficiaryName] = beneficiaryName
            it[CommissionPaymentsTable.amount] = amount; it[CommissionPaymentsTable.method] = method
            it[CommissionPaymentsTable.cashAccountId] = cashAccountId; it[CommissionPaymentsTable.bankAccountId] = bankAccountId
            it[CommissionPaymentsTable.paidVia] = paidVia?.trim()?.takeIf { v -> v.isNotEmpty() }
            it[paidAt] = java.time.Instant.now(); it[paidByUserId] = support.userId(); it[CommissionPaymentsTable.note] = note?.trim()?.takeIf { v -> v.isNotEmpty() }
            it[status] = "PAID"
        }.value
        if (method == "WALLET") {
            val entry = wallet.credit(
                LedgerPosting(
                    customerId = beneficiaryId, balanceType = BalanceType.WALLET, entryType = LedgerEntryType.COMMISSION_CREDIT, amount = amount,
                    reference = ref, description = "Commission $ref", paymentMethod = null, sourceModule = SOURCE_MODULE, sourceType = "commission_payment",
                    sourceId = paymentId, locationId = null,
                ),
            )
            CommissionPaymentsTable.update({ CommissionPaymentsTable.id eq paymentId }) { it[customerLedgerEntryId] = entry.id }
        } else {
            val posting = payouts.payOut(CommissionPayoutRequest(method, cashAccountId, bankAccountId, amount, ref, "Commission $ref to $beneficiaryName", paymentId))
            CommissionPaymentsTable.update({ CommissionPaymentsTable.id eq paymentId }) {
                it[accountCashTransactionId] = posting.cashTransactionId; it[accountBankTransactionId] = posting.bankTransactionId
            }
        }
        support.record("communication.commission.paid", "commission_payment", paymentId, ref, "Commission $ref: ${Money.naira(amount)} to $beneficiaryName",
            mapOf("amount" to amount, "method" to method))
        return row(paymentId)
    }

    private fun approvedRun(runId: UUID): ResultRow {
        val run = CommissionRunsTable.selectAll().where { (CommissionRunsTable.id eq runId) and (CommissionRunsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("runId" to "run not found"))
        if (run[CommissionRunsTable.status] != "APPROVED") throw ApiErrors.runNotApproved()
        return run
    }

    private fun beneficiaryRow(runId: UUID, beneficiaryId: UUID): ResultRow =
        CommissionRunBeneficiariesTable.selectAll().where { (CommissionRunBeneficiariesTable.runId eq runId) and (CommissionRunBeneficiariesTable.beneficiaryId eq beneficiaryId) }.firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("beneficiaryId" to "not a beneficiary of this run"))

    private fun outstandingFor(runId: UUID, beneficiaryId: UUID, earned: BigDecimal): BigDecimal {
        val paidExpr = CommissionPaymentsTable.amount.sum()
        val paid = CommissionPaymentsTable.select(paidExpr)
            .where { (CommissionPaymentsTable.runId eq runId) and (CommissionPaymentsTable.beneficiaryId eq beneficiaryId) and (CommissionPaymentsTable.status eq "PAID") }
            .firstOrNull()?.get(paidExpr) ?: BigDecimal.ZERO
        return earned - paid
    }

    private fun zone(): ZoneId = support.zone()

    private fun row(id: UUID): CommissionPaymentRow = toRow(CommissionPaymentsTable.selectAll().where { CommissionPaymentsTable.id eq id }.single())

    private fun toRow(r: ResultRow): CommissionPaymentRow {
        val runRef = CommissionRunsTable.select(CommissionRunsTable.ref).where { CommissionRunsTable.id eq r[CommissionPaymentsTable.runId] }.firstOrNull()?.get(CommissionRunsTable.ref) ?: ""
        return CommissionPaymentRow(
            id = r[CommissionPaymentsTable.id].value, ref = r[CommissionPaymentsTable.ref], runRef = runRef,
            beneficiary = BeneficiaryRef(r[CommissionPaymentsTable.beneficiaryId], "", r[CommissionPaymentsTable.beneficiaryName], r[CommissionPaymentsTable.beneficiaryType]),
            amount = r[CommissionPaymentsTable.amount], method = r[CommissionPaymentsTable.method], paidVia = r[CommissionPaymentsTable.paidVia],
            paidAt = r[CommissionPaymentsTable.paidAt], paidBy = UserRef.of(r[CommissionPaymentsTable.paidByUserId].value), status = r[CommissionPaymentsTable.status], note = r[CommissionPaymentsTable.note],
        )
    }

    private companion object {
        const val SOURCE_MODULE = "communication"
    }
}
