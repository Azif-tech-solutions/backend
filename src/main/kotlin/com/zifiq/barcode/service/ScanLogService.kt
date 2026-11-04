package com.zifiq.barcode.service

import com.zifiq.barcode.dto.ScanLogRow
import com.zifiq.barcode.dto.ScanLogStats
import com.zifiq.barcode.dto.UnrecognisedCodeRow
import com.zifiq.barcode.dto.UnrecognisedTop
import com.zifiq.barcode.model.ScanEventsTable
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.directory.Names
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * The scan log (docs/11 §4.4): recent scans across every scan-enabled screen, with match-rate stats
 * and the repeatedly-unrecognised codes worth registering. A user without `barcode.scan-log.read`
 * sees only their own scans. Every query is index-backed (scanned_at / outcome / code indexes).
 */
@Service
class ScanLogService(
    private val support: BarcodeSupport,
    private val tx: TenantScopedTransaction,
) {
    data class Filter(
        val outcome: String? = null,
        val context: String? = null,
        val userId: UUID? = null,
        val locationId: UUID? = null,
        val stockItemId: UUID? = null,
        val code: String? = null,
        val from: LocalDate? = null,
        val to: LocalDate? = null,
        val mine: Boolean = false,
    )

    fun list(filter: Filter, paging: PageRequest): PageResult<ScanLogRow> = tx.execute {
        val cond = condition(filter)
        val page = Paging.fetch(
            ScanEventsTable.selectAll().where(cond).orderBy(ScanEventsTable.scannedAt to SortOrder.DESC, ScanEventsTable.id to SortOrder.DESC),
            paging,
        ) { it }
        val itemNames = itemLabels(page.items.mapNotNull { it[ScanEventsTable.stockItemId] })
        page.withItems(page.items.map { r ->
            val sid = r[ScanEventsTable.stockItemId]
            ScanLogRow(
                time = r[ScanEventsTable.scannedAt], code = r[ScanEventsTable.code], result = r[ScanEventsTable.outcome],
                resolvedTo = sid?.let { itemNames[it] } ?: "—", scannedIn = contextLabel(r[ScanEventsTable.context]),
                scannedBy = UserRef.of(r[ScanEventsTable.userId].value), location = ref(r[ScanEventsTable.locationId], Ref::location),
                note = r[ScanEventsTable.blockReason]?.let { reasonText(it) },
            )
        }).copy(summary = statsMap(stats(filter)))
    }

    private fun statsMap(s: ScanLogStats): Map<String, Any?> = mapOf(
        "scans" to s.scans, "matchRate" to s.matchRate, "notRecognised" to s.notRecognised,
        "blocked" to s.blocked, "misread" to s.misread,
        "topUnrecognised" to s.topUnrecognised.map { mapOf("code" to it.code, "count" to it.count) },
    )

    fun stats(filter: Filter): ScanLogStats = tx.execute {
        val cond = condition(filter)
        val outcomeCount = ScanEventsTable.outcome
        val c = ScanEventsTable.id.count()
        val byOutcome = ScanEventsTable.select(outcomeCount, c).where(cond).groupBy(outcomeCount)
            .associate { it[outcomeCount] to it[c] }
        val scans = byOutcome.values.sum()
        val matched = byOutcome["MATCHED"] ?: 0L
        val top = ScanEventsTable.select(ScanEventsTable.code, c)
            .where(cond and (ScanEventsTable.outcome eq "NOT_RECOGNISED")).groupBy(ScanEventsTable.code)
            .orderBy(c to SortOrder.DESC).limit(10)
            .map { UnrecognisedTop(it[ScanEventsTable.code], it[c]) }
        ScanLogStats(
            scans = scans,
            matchRate = if (scans == 0L) BigDecimal.ZERO else BigDecimal(matched * 100).divide(BigDecimal(scans), 1, RoundingMode.HALF_UP),
            notRecognised = byOutcome["NOT_RECOGNISED"] ?: 0L, blocked = byOutcome["BLOCKED"] ?: 0L, misread = byOutcome["MISREAD"] ?: 0L,
            topUnrecognised = top,
        )
    }

    fun unrecognised(from: LocalDate?, to: LocalDate?): List<UnrecognisedCodeRow> = tx.execute {
        val cond = condition(Filter(outcome = "NOT_RECOGNISED", from = from, to = to, mine = false))
        val c = ScanEventsTable.id.count()
        val first = ScanEventsTable.scannedAt.min()
        val last = ScanEventsTable.scannedAt.max()
        val canRegister = support.can("inventory.stock.manage")
        ScanEventsTable.select(ScanEventsTable.code, c, first, last).where(cond).groupBy(ScanEventsTable.code)
            .orderBy(c to SortOrder.DESC).limit(200)
            .map { r ->
                val code = r[ScanEventsTable.code]
                val contexts = ScanEventsTable.select(ScanEventsTable.context)
                    .where(cond and (ScanEventsTable.code eq code)).withDistinct()
                    .map { contextLabel(it[ScanEventsTable.context]) }
                UnrecognisedCodeRow(code, r[c], r[first]!!, r[last]!!, contexts, canRegister)
            }
    }

    private fun condition(filter: Filter): Op<Boolean> {
        val mine = filter.mine || !support.can("barcode.scan-log.read")
        var cond: Op<Boolean> = ScanEventsTable.tenantId eq support.tenantId()
        if (mine) cond = cond and (ScanEventsTable.userId eq support.userId())
        else filter.userId?.let { cond = cond and (ScanEventsTable.userId eq it) }
        filter.outcome?.let { cond = cond and (ScanEventsTable.outcome eq it) }
        filter.context?.let { cond = cond and (ScanEventsTable.context eq it) }
        filter.locationId?.let { cond = cond and (ScanEventsTable.locationId eq it) }
        filter.stockItemId?.let { cond = cond and (ScanEventsTable.stockItemId eq it) }
        filter.code?.trim()?.takeIf { it.isNotEmpty() }?.let { cond = cond and (ScanEventsTable.code eq it) }
        filter.from?.let { cond = cond and (ScanEventsTable.scannedAt greaterEq it.atStartOfDay(ZoneOffset.UTC).toInstant()) }
        filter.to?.let { cond = cond and (ScanEventsTable.scannedAt less it.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()) }
        return cond
    }

    private fun itemLabels(ids: List<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return StockItemsTable.select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name)
            .where { StockItemsTable.id inList ids.distinct() }
            .associate { it[StockItemsTable.id].value to "${it[StockItemsTable.code]} · ${it[StockItemsTable.name]}" }
    }

    private fun contextLabel(context: String): String = context.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun reasonText(reason: String): String = reason.lowercase().replace('_', ' ')
}
