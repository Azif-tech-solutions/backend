package com.zifiq.cashier.service

import com.zifiq.cashier.dto.InstantDiscountRequest
import com.zifiq.cashier.dto.InstantDiscountRow
import com.zifiq.cashier.dto.InstantDiscountView
import com.zifiq.cashier.dto.RejectRequest
import com.zifiq.cashier.model.InstantDiscountsTable
import com.zifiq.cashier.model.SaleLinesTable
import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.allOf
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Filters for the instant-discount list (docs/09 §4.3). */
data class InstantDiscountFilter(val status: String? = null, val reason: String? = null, val saleId: UUID? = null)

/**
 * Instant Discount on a live sale (docs/09 §4.3): an Administrator reduces an open sale's price; a
 * reduction above the ceiling needs Management approval before it takes effect. An applied discount
 * is allocated across the lines (so VAT and the invoice are right) and re-allocated whenever the
 * cart changes — if the new base can't bear it, it lapses. Caller's transaction, through the
 * tenant-scoped executor. (There is no live push stream — SSE is banned by the CVE-2026-47890
 * exception; the till polls the sale's version, which every decision here bumps.)
 */
@Service
class SaleDiscounts(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
    private val views: SaleViews,
    private val sequences: DocumentSequenceService,
    private val notify: Notify,
) {
    fun apply(request: InstantDiscountRequest): InstantDiscountView = tx.execute {
        val sale = views.visibleRow(request.saleId!!, forUpdate = true)
        if (sale[SalesTable.status] != "OPEN") throw ApiErrors.saleNotOpen(sale[SalesTable.status])
        if (SaleLinesTable.select(SaleLinesTable.id).where { SaleLinesTable.saleId eq sale[SalesTable.id].value }.empty()) {
            throw ApiErrors.validationFailed(mapOf("lines" to "add items before discounting"))
        }
        if (liveFor(sale[SalesTable.id].value) != null) throw ApiErrors.discountAlreadyActive()
        val mode = request.mode!!.trim().uppercase()
        if (mode !in setOf("PERCENT", "AMOUNT")) throw ApiErrors.validationFailed(mapOf("mode" to "PERCENT or AMOUNT"))
        val base = sale[SalesTable.grossAmount] - sale[SalesTable.lineDiscountTotal]
        val amount = discountAmount(mode, request.value!!, base)
        if (amount > base) throw ApiErrors.discountExceedsTransaction()
        val pctOfBase = if (base.signum() == 0) BigDecimal.ZERO else (amount * HUNDRED / base).setScale(4, RoundingMode.HALF_UP)
        val ceiling = support.settings().decimal(CashierSettings.INSTANT_DISCOUNT_CEILING_PCT)
        val autoApprove = pctOfBase <= ceiling || support.can(APPROVE)
        val ref = sequences.next("IDS")
        val id = InstantDiscountsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[InstantDiscountsTable.ref] = ref
            it[saleId] = sale[SalesTable.id].value
            it[InstantDiscountsTable.mode] = mode
            it[value] = request.value.setScale(4, RoundingMode.HALF_UP)
            it[discountAmount] = amount
            it[baseAmount] = base
            it[InstantDiscountsTable.pctOfBase] = pctOfBase
            it[reason] = request.reason!!.trim().uppercase()
            it[note] = request.note?.trim()?.ifEmpty { null }
            it[status] = if (autoApprove) "APPLIED" else "PENDING_APPROVAL"
            it[appliedByUserId] = support.userId()
            it[approvedByUserId] = if (autoApprove && support.can(APPROVE)) support.userId() else null
            it[decidedAt] = if (autoApprove) Instant.now() else null
            it[createdAt] = Instant.now()
        }.value
        val locationId = sale[SalesTable.locationId].value
        if (autoApprove) {
            reapply(sale[SalesTable.id].value)
        } else {
            bumpVersion(sale[SalesTable.id].value)
            notify.holdersOf(APPROVE, locationId, NotificationCategory.ALERT, "Discount needs approval — $ref",
                "${Money.naira(amount)} (${pctOfBase.stripTrailingZeros().toPlainString()}%) on ${sale[SalesTable.customerName]}'s sale.",
                "sale", sale[SalesTable.id].value.toString())
        }
        view(row(id))
    }

    fun approve(id: UUID): InstantDiscountView = tx.execute {
        val discount = pendingForUpdate(id)
        InstantDiscountsTable.update({ InstantDiscountsTable.id eq id }) {
            it[status] = "APPLIED"
            it[approvedByUserId] = support.userId()
            it[decidedAt] = Instant.now()
        }
        reapply(discount[InstantDiscountsTable.saleId])
        view(row(id))
    }

    fun reject(id: UUID, request: RejectRequest): InstantDiscountView = tx.execute {
        val discount = pendingForUpdate(id)
        InstantDiscountsTable.update({ InstantDiscountsTable.id eq id }) {
            it[status] = "REJECTED"
            it[approvedByUserId] = support.userId()
            it[decidedAt] = Instant.now()
            it[rejectionReason] = request.reason!!.trim().take(300)
        }
        bumpVersion(discount[InstantDiscountsTable.saleId])
        view(row(id))
    }

    fun withdraw(id: UUID): InstantDiscountView = tx.execute {
        val discount = InstantDiscountsTable.selectAll().where { (InstantDiscountsTable.id eq id) and (InstantDiscountsTable.tenantId eq support.tenantId()) }.forUpdate()
            .firstOrNull() ?: throw ApiErrors.validationFailed(mapOf("id" to "discount not found"))
        if (discount[InstantDiscountsTable.status] !in setOf("PENDING_APPROVAL", "APPLIED")) throw ApiErrors.alreadyDecided("discount")
        val sale = views.visibleRow(discount[InstantDiscountsTable.saleId], forUpdate = true)
        if (sale[SalesTable.status] != "OPEN") throw ApiErrors.saleNotOpen(sale[SalesTable.status])
        InstantDiscountsTable.update({ InstantDiscountsTable.id eq id }) { it[status] = "WITHDRAWN"; it[decidedAt] = Instant.now() }
        reapply(discount[InstantDiscountsTable.saleId])
        view(row(id))
    }

    fun list(filter: InstantDiscountFilter, paging: PageRequest): PageResult<InstantDiscountRow> = tx.execute {
        val condition = listOfNotNull(
            InstantDiscountsTable.tenantId eq support.tenantId(),
            filter.status?.let { InstantDiscountsTable.status eq it.uppercase() },
            filter.reason?.let { InstantDiscountsTable.reason eq it.uppercase() },
            filter.saleId?.let { InstantDiscountsTable.saleId eq it },
        ).allOf()!!
        val page = Paging.fetch(InstantDiscountsTable.selectAll().where(condition)
            .orderBy(InstantDiscountsTable.createdAt to SortOrder.DESC, InstantDiscountsTable.id to SortOrder.DESC), paging) { it }
        page.withItems(page.items.map(::rowView))
    }

    // ---- integration points ----

    /**
     * Recomputes the sale with its live APPLIED discount allocated across the lines, or lapses the
     * discount when the base can no longer bear it. Called after any cart change and on approve/withdraw.
     * [SalePricing.write] has already written the lines with a zero instant-discount share.
     */
    fun reapply(saleId: UUID) {
        // Start from no instant discount, so a withdrawn/lapsed one is fully undone on the lines too.
        val base = resetLines(saleId)
        val discount = InstantDiscountsTable.selectAll().where { (InstantDiscountsTable.saleId eq saleId) and (InstantDiscountsTable.status eq "APPLIED") }.forUpdate().firstOrNull()
        if (discount == null) {
            bumpVersion(saleId)
            return
        }
        val amount = if (discount[InstantDiscountsTable.mode] == "PERCENT") Money.round(base * discount[InstantDiscountsTable.value] / HUNDRED)
        else Money.round(discount[InstantDiscountsTable.value])
        if (base.signum() <= 0 || amount > base) {
            InstantDiscountsTable.update({ InstantDiscountsTable.id eq discount[InstantDiscountsTable.id].value }) { it[status] = "LAPSED"; it[decidedAt] = Instant.now() }
            notify.holdersOf(APPLY, locationOf(saleId), NotificationCategory.ALERT, "Discount lapsed — ${discount[InstantDiscountsTable.ref]}",
                "The cart changed and no longer supports the discount.", "sale", saleId.toString())
            bumpVersion(saleId)
            return
        }
        allocate(saleId, amount)
        InstantDiscountsTable.update({ InstantDiscountsTable.id eq discount[InstantDiscountsTable.id].value }) {
            it[discountAmount] = amount
            it[baseAmount] = base
            it[pctOfBase] = if (base.signum() == 0) BigDecimal.ZERO else (amount * HUNDRED / base).setScale(4, RoundingMode.HALF_UP)
        }
    }

    /** Clears any instant-discount allocation from the lines and header (no version bump); returns the base. */
    private fun resetLines(saleId: UUID): BigDecimal {
        val lines = SaleLinesTable.selectAll().where { SaleLinesTable.saleId eq saleId }.toList()
        var net = BigDecimal.ZERO
        var vat = BigDecimal.ZERO
        lines.forEach { line ->
            val lineNet = Money.round(line[SaleLinesTable.quantity] * line[SaleLinesTable.unitPrice] - line[SaleLinesTable.lineDiscount])
            val lineVat = Money.round(lineNet * line[SaleLinesTable.vatRate] / HUNDRED)
            net += lineNet
            vat += lineVat
            SaleLinesTable.update({ SaleLinesTable.id eq line[SaleLinesTable.id].value }) {
                it[instantDiscountShare] = BigDecimal.ZERO
                it[SaleLinesTable.lineNet] = lineNet
                it[vatAmount] = lineVat
                it[lineTotal] = lineNet + lineVat
            }
        }
        SalesTable.update({ SalesTable.id eq saleId }) {
            it[instantDiscountTotal] = BigDecimal.ZERO.setScale(2)
            it[netAmount] = Money.round(net)
            it[vatAmount] = Money.round(vat)
            it[total] = Money.round(net + vat)
        }
        val sale = SalesTable.select(SalesTable.grossAmount, SalesTable.lineDiscountTotal).where { SalesTable.id eq saleId }.single()
        return sale[SalesTable.grossAmount] - sale[SalesTable.lineDiscountTotal]
    }

    private fun locationOf(saleId: UUID): UUID = SalesTable.select(SalesTable.locationId).where { SalesTable.id eq saleId }.single()[SalesTable.locationId].value

    /** On completion: a live PENDING discount blocks checkout. */
    fun requireNoPending(saleId: UUID) {
        if (InstantDiscountsTable.select(InstantDiscountsTable.id).where { (InstantDiscountsTable.saleId eq saleId) and (InstantDiscountsTable.status eq "PENDING_APPROVAL") }.any()) {
            throw ApiErrors.discountPendingApproval()
        }
    }

    /** On void/abandon: any live discount lapses. */
    fun lapseLive(saleId: UUID) {
        InstantDiscountsTable.update({ (InstantDiscountsTable.saleId eq saleId) and (InstantDiscountsTable.status inList listOf("PENDING_APPROVAL", "APPLIED")) }) {
            it[status] = "LAPSED"
            it[decidedAt] = Instant.now()
        }
    }

    // ---- internals ----

    private fun allocate(saleId: UUID, amount: BigDecimal) {
        val lines = SaleLinesTable.selectAll().where { SaleLinesTable.saleId eq saleId }.orderBy(SaleLinesTable.lineNo).toList()
        val weights = lines.map { it[SaleLinesTable.quantity] * it[SaleLinesTable.unitPrice] - it[SaleLinesTable.lineDiscount] }
        val totalWeight = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        var allocated = BigDecimal.ZERO
        val shares = lines.indices.map { i ->
            val share = if (totalWeight.signum() == 0) BigDecimal.ZERO else Money.round(amount * weights[i] / totalWeight)
            allocated += share
            share
        }.toMutableList()
        // Rounding remainder goes to the largest-weight line.
        val remainder = amount - allocated
        if (remainder.signum() != 0 && shares.isNotEmpty()) {
            val biggest = weights.indices.maxByOrNull { weights[it] }!!
            shares[biggest] = shares[biggest] + remainder
        }
        var net = BigDecimal.ZERO
        var vat = BigDecimal.ZERO
        lines.forEachIndexed { i, line ->
            val lineValue = line[SaleLinesTable.quantity] * line[SaleLinesTable.unitPrice]
            val lineNet = Money.round(lineValue - line[SaleLinesTable.lineDiscount] - shares[i])
            val lineVat = Money.round(lineNet * line[SaleLinesTable.vatRate] / HUNDRED)
            net += lineNet
            vat += lineVat
            SaleLinesTable.update({ SaleLinesTable.id eq line[SaleLinesTable.id].value }) {
                it[instantDiscountShare] = shares[i]
                it[SaleLinesTable.lineNet] = lineNet
                it[vatAmount] = lineVat
                it[lineTotal] = lineNet + lineVat
            }
        }
        val sale = SalesTable.selectAll().where { SalesTable.id eq saleId }.single()
        SalesTable.update({ SalesTable.id eq saleId }) {
            it[instantDiscountTotal] = amount
            it[netAmount] = Money.round(net)
            it[vatAmount] = Money.round(vat)
            it[total] = Money.round(net + vat)
            it[version] = sale[SalesTable.version] + 1
            it[updatedAt] = Instant.now()
        }
    }

    private fun discountAmount(mode: String, value: BigDecimal, base: BigDecimal): BigDecimal =
        if (mode == "PERCENT") {
            if (value > HUNDRED) throw ApiErrors.discountExceedsTransaction()
            Money.round(base * value / HUNDRED)
        } else Money.round(value)

    private fun liveFor(saleId: UUID): ResultRow? =
        InstantDiscountsTable.selectAll().where { (InstantDiscountsTable.saleId eq saleId) and (InstantDiscountsTable.status inList listOf("PENDING_APPROVAL", "APPLIED")) }.firstOrNull()

    private fun bumpVersion(saleId: UUID) {
        val v = SalesTable.select(SalesTable.version).where { SalesTable.id eq saleId }.single()[SalesTable.version]
        SalesTable.update({ SalesTable.id eq saleId }) { it[version] = v + 1; it[updatedAt] = Instant.now() }
    }

    private fun pendingForUpdate(id: UUID): ResultRow {
        val discount = InstantDiscountsTable.selectAll().where { (InstantDiscountsTable.id eq id) and (InstantDiscountsTable.tenantId eq support.tenantId()) }.forUpdate()
            .firstOrNull() ?: throw ApiErrors.validationFailed(mapOf("id" to "discount not found"))
        if (discount[InstantDiscountsTable.status] != "PENDING_APPROVAL") throw ApiErrors.alreadyDecided("discount")
        views.visibleRow(discount[InstantDiscountsTable.saleId])
        return discount
    }

    private fun row(id: UUID): ResultRow = InstantDiscountsTable.selectAll().where { InstantDiscountsTable.id eq id }.single()

    private fun view(d: ResultRow) = InstantDiscountView(
        id = d[InstantDiscountsTable.id].value, ref = d[InstantDiscountsTable.ref], sale = Ref.sale(d[InstantDiscountsTable.saleId]), mode = d[InstantDiscountsTable.mode],
        value = d[InstantDiscountsTable.value], discountAmount = d[InstantDiscountsTable.discountAmount], baseAmount = d[InstantDiscountsTable.baseAmount],
        pctOfBase = d[InstantDiscountsTable.pctOfBase], reason = d[InstantDiscountsTable.reason], note = d[InstantDiscountsTable.note], status = d[InstantDiscountsTable.status],
        appliedBy = UserRef(d[InstantDiscountsTable.appliedByUserId]), approvedBy = UserRef.of(d[InstantDiscountsTable.approvedByUserId]),
        rejectionReason = d[InstantDiscountsTable.rejectionReason],
    )

    private fun rowView(d: ResultRow) = InstantDiscountRow(
        id = d[InstantDiscountsTable.id].value, ref = d[InstantDiscountsTable.ref], sale = Ref.sale(d[InstantDiscountsTable.saleId]), discountAmount = d[InstantDiscountsTable.discountAmount],
        pctOfBase = d[InstantDiscountsTable.pctOfBase], reason = d[InstantDiscountsTable.reason], status = d[InstantDiscountsTable.status],
        appliedBy = UserRef(d[InstantDiscountsTable.appliedByUserId]), approvedBy = UserRef.of(d[InstantDiscountsTable.approvedByUserId]), createdAt = d[InstantDiscountsTable.createdAt],
    )

    companion object {
        const val APPLY = "cashier.discount.apply"
        const val APPROVE = "cashier.discount.approve"
        private val HUNDRED = BigDecimal(100)
    }
}
