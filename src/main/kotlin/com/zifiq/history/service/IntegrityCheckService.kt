package com.zifiq.history.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.history.dto.IntegrityFindingRow
import com.zifiq.history.dto.IntegrityRunDetail
import com.zifiq.history.dto.IntegrityRunRow
import com.zifiq.history.model.IntegrityCheckFindingsTable
import com.zifiq.history.model.IntegrityCheckRunsTable
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import com.zifiq.inventory.model.StockReservationsTable
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Integrity checks (docs/10 §4.10): a run that asserts the books hang together and records any
 * breaches. At most one run at a time per tenant. CRITICAL findings notify holders of
 * history.integrity.read and emit audit.integrity.finding.
 *
 * Implemented this phase (inventory, the OPEN module — read directly): STOCK_BALANCE_MATCHES_LEDGER,
 * STOCK_NOT_NEGATIVE, RESERVATION_WITHIN_STOCK. The financial checks (WALLET_CHAIN, WALLET_NEGATIVE,
 * WITHDRAWAL_OTP_MISSING, CASH/BANK_ACCOUNT_MATCHES_LEDGER, INVOICE_TOTALS, INVOICE_SEQUENCE_GAPLESS,
 * SALE_SETTLED, RECEIPT_POSTED) each need a read method on their owning (non-open) module and land
 * as those SPI methods are added.
 */
@Service
class IntegrityCheckService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
    private val notify: Notify,
) {
    private data class Finding(val checkCode: String, val severity: String, val recordType: String, val recordRef: String, val expected: String?, val actual: String?, val message: String)

    fun run(trigger: String): IntegrityRunDetail = tx.execute {
        val running = IntegrityCheckRunsTable.select(IntegrityCheckRunsTable.id)
            .where { (IntegrityCheckRunsTable.tenantId eq support.tenantId()) and (IntegrityCheckRunsTable.status eq "RUNNING") }.any()
        if (running) throw ApiErrors.runInProgress()
        val runId = IntegrityCheckRunsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[startedAt] = Instant.now()
            it[IntegrityCheckRunsTable.trigger] = trigger
            it[triggeredByUserId] = if (trigger == "MANUAL") support.userId() else null
            it[checksRun] = 0
            it[findingsCount] = 0
            it[status] = "RUNNING"
        }.value

        val checks = listOf(::stockBalanceMatchesLedger, ::stockNotNegative, ::reservationWithinStock)
        val findings = checks.flatMap { it() }
        findings.forEach { f ->
            IntegrityCheckFindingsTable.insert {
                it[tenantId] = support.tenantId(); it[IntegrityCheckFindingsTable.runId] = runId
                it[checkCode] = f.checkCode; it[severity] = f.severity; it[recordType] = f.recordType
                it[recordRef] = f.recordRef; it[expected] = f.expected; it[actual] = f.actual; it[message] = f.message
            }
        }
        val critical = findings.count { it.severity == "CRITICAL" }
        IntegrityCheckRunsTable.update({ IntegrityCheckRunsTable.id eq runId }) {
            it[finishedAt] = Instant.now(); it[checksRun] = checks.size; it[findingsCount] = findings.size
            it[status] = if (findings.isEmpty()) "PASSED" else "FINDINGS"
        }
        if (critical > 0) {
            support.record("audit.integrity.finding", "integrity_run", runId, null, "$critical critical integrity finding(s)", mapOf("criticalCount" to critical))
            notify.holdersOf("history.integrity.read", null, NotificationCategory.ALERT, "Integrity check: $critical critical finding(s)",
                "An integrity run found $critical critical issue(s); review them.", linkType = "integrity-run", linkRef = runId.toString())
        }
        detail(runId)
    }

    fun listRuns(paging: PageRequest): PageResult<IntegrityRunRow> = tx.execute {
        Paging.fetch(
            IntegrityCheckRunsTable.selectAll().where { IntegrityCheckRunsTable.tenantId eq support.tenantId() }.orderBy(IntegrityCheckRunsTable.startedAt to SortOrder.DESC),
            paging,
        ) { toRunRow(it) }
    }

    fun detail(runId: UUID): IntegrityRunDetail = tx.execute {
        val run = IntegrityCheckRunsTable.selectAll().where { (IntegrityCheckRunsTable.id eq runId) and (IntegrityCheckRunsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("id" to "run not found"))
        val findings = IntegrityCheckFindingsTable.selectAll().where { IntegrityCheckFindingsTable.runId eq runId }
            .orderBy(IntegrityCheckFindingsTable.severity to SortOrder.DESC)
            .map {
                IntegrityFindingRow(
                    it[IntegrityCheckFindingsTable.checkCode], it[IntegrityCheckFindingsTable.severity], it[IntegrityCheckFindingsTable.recordType],
                    it[IntegrityCheckFindingsTable.recordRef], it[IntegrityCheckFindingsTable.expected], it[IntegrityCheckFindingsTable.actual], it[IntegrityCheckFindingsTable.message],
                )
            }
        IntegrityRunDetail(toRunRow(run), findings)
    }

    // ---- checks (inventory, OPEN) ----

    private fun stockBalanceMatchesLedger(): List<Finding> {
        val inSum = StockLedgerEntriesTable.qtyIn.sum()
        val outSum = StockLedgerEntriesTable.qtyOut.sum()
        val ledger = StockLedgerEntriesTable
            .select(StockLedgerEntriesTable.locationId, StockLedgerEntriesTable.stockItemId, inSum, outSum)
            .where { StockLedgerEntriesTable.tenantId eq support.tenantId() }
            .groupBy(StockLedgerEntriesTable.locationId, StockLedgerEntriesTable.stockItemId)
            .associate {
                (it[StockLedgerEntriesTable.locationId] to it[StockLedgerEntriesTable.stockItemId].value) to
                    ((it[inSum] ?: BigDecimal.ZERO) - (it[outSum] ?: BigDecimal.ZERO))
            }
        return StockBalancesTable.join(StockItemsTable, org.jetbrains.exposed.sql.JoinType.INNER, StockBalancesTable.stockItemId, StockItemsTable.id)
            .select(StockBalancesTable.locationId, StockBalancesTable.stockItemId, StockBalancesTable.quantityOnHand, StockItemsTable.code)
            .where { StockBalancesTable.tenantId eq support.tenantId() }
            .mapNotNull {
                val key = it[StockBalancesTable.locationId] to it[StockBalancesTable.stockItemId].value
                val expected = ledger[key] ?: BigDecimal.ZERO
                val actual = it[StockBalancesTable.quantityOnHand]
                if (expected.compareTo(actual) != 0) {
                    Finding("STOCK_BALANCE_MATCHES_LEDGER", "CRITICAL", "stock_balance", it[StockItemsTable.code],
                        expected.toPlainString(), actual.toPlainString(), "Balance for ${it[StockItemsTable.code]} does not match the ledger")
                } else null
            }
    }

    private fun stockNotNegative(): List<Finding> =
        StockBalancesTable.join(StockItemsTable, org.jetbrains.exposed.sql.JoinType.INNER, StockBalancesTable.stockItemId, StockItemsTable.id)
            .select(StockBalancesTable.quantityOnHand, StockItemsTable.code)
            .where { (StockBalancesTable.tenantId eq support.tenantId()) and (StockBalancesTable.quantityOnHand less BigDecimal.ZERO) }
            .map {
                Finding("STOCK_NOT_NEGATIVE", "CRITICAL", "stock_balance", it[StockItemsTable.code], ">= 0", it[StockBalancesTable.quantityOnHand].toPlainString(),
                    "Negative stock on hand for ${it[StockItemsTable.code]}")
            }

    private fun reservationWithinStock(): List<Finding> {
        val reservedSum = StockReservationsTable.quantityReserved.sum()
        val releasedSum = StockReservationsTable.quantityReleased.sum()
        val open = StockReservationsTable
            .select(StockReservationsTable.locationId, StockReservationsTable.stockItemId, reservedSum, releasedSum)
            .where { (StockReservationsTable.tenantId eq support.tenantId()) and (StockReservationsTable.status eq "OPEN") }
            .groupBy(StockReservationsTable.locationId, StockReservationsTable.stockItemId)
            .associate {
                (it[StockReservationsTable.locationId] to it[StockReservationsTable.stockItemId]) to
                    ((it[reservedSum] ?: BigDecimal.ZERO) - (it[releasedSum] ?: BigDecimal.ZERO))
            }
        if (open.isEmpty()) return emptyList()
        val balances = StockBalancesTable.select(StockBalancesTable.locationId, StockBalancesTable.stockItemId, StockBalancesTable.quantityOnHand)
            .where { StockBalancesTable.tenantId eq support.tenantId() }
            .associate { (it[StockBalancesTable.locationId] to it[StockBalancesTable.stockItemId].value) to it[StockBalancesTable.quantityOnHand] }
        return open.mapNotNull { (key, reserved) ->
            val onHand = balances[key] ?: BigDecimal.ZERO
            if (reserved > onHand) {
                Finding("RESERVATION_WITHIN_STOCK", "WARNING", "stock_reservation", key.second.toString(), onHand.toPlainString(), reserved.toPlainString(),
                    "Open reservations exceed stock on hand")
            } else null
        }
    }

    private fun toRunRow(r: ResultRow) = IntegrityRunRow(
        id = r[IntegrityCheckRunsTable.id].value, startedAt = r[IntegrityCheckRunsTable.startedAt], finishedAt = r[IntegrityCheckRunsTable.finishedAt],
        trigger = r[IntegrityCheckRunsTable.trigger], triggeredBy = UserRef.of(r[IntegrityCheckRunsTable.triggeredByUserId]),
        checksRun = r[IntegrityCheckRunsTable.checksRun], findingsCount = r[IntegrityCheckRunsTable.findingsCount], status = r[IntegrityCheckRunsTable.status],
    )
}
