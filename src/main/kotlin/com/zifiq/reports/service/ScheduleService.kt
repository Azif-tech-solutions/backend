package com.zifiq.reports.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.reporting.ReportProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import com.zifiq.reports.dto.ScheduleRequest
import com.zifiq.reports.dto.ScheduleView
import com.zifiq.reports.model.ReportSchedulesTable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Scheduled reports (docs/13 §4.7): a schedule produces an export on a cadence, owned by the user who
 * created it (`run_as_user_id`) and delivered to tenant members who could run the report themselves.
 * The owner's permissions, location scope and cost visibility decide the data — a schedule can't leak
 * a report to someone who couldn't open it, so every recipient is checked at save time.
 */
@Service
class ScheduleService(
    private val support: ReportsSupport,
    private val tx: TenantScopedTransaction,
    private val permissions: PermissionAuthorizer,
    private val exports: ExportService,
    providers: List<ReportProvider>,
) {
    private val byKey = providers.associateBy { it.key }

    fun create(req: ScheduleRequest): ScheduleView = tx.execute {
        val (provider, fmt, freq) = validate(req)
        val owner = support.userId()
        val id = UUID.randomUUID()
        val next = computeNext(Instant.now(), freq, req.dayOfWeek, req.dayOfMonth, req.sendAt ?: DEFAULT_SEND_AT)
        ReportSchedulesTable.insert {
            it[ReportSchedulesTable.id] = id; it[tenantId] = support.tenantId(); it[name] = req.name!!.trim(); it[reportKey] = provider.key
            it[params] = req.params; it[format] = fmt; it[frequency] = freq; it[dayOfWeek] = req.dayOfWeek; it[dayOfMonth] = req.dayOfMonth
            it[sendAt] = req.sendAt ?: DEFAULT_SEND_AT; it[recipients] = req.recipients.map(UUID::toString); it[runAsUserId] = owner
            it[status] = "ACTIVE"; it[nextRunAt] = next; it[createdByUserId] = owner; it[createdAt] = Instant.now()
        }
        view(ReportSchedulesTable.selectAll().where { ReportSchedulesTable.id eq id }.single())
    }

    fun update(id: UUID, req: ScheduleRequest): ScheduleView = tx.execute {
        val existing = load(id)
        val (provider, fmt, freq) = validate(req)
        val next = computeNext(Instant.now(), freq, req.dayOfWeek, req.dayOfMonth, req.sendAt ?: DEFAULT_SEND_AT)
        ReportSchedulesTable.update({ ReportSchedulesTable.id eq id }) {
            it[name] = req.name!!.trim(); it[reportKey] = provider.key; it[params] = req.params; it[format] = fmt; it[frequency] = freq
            it[dayOfWeek] = req.dayOfWeek; it[dayOfMonth] = req.dayOfMonth; it[sendAt] = req.sendAt ?: DEFAULT_SEND_AT
            it[recipients] = req.recipients.map(UUID::toString); it[nextRunAt] = next
        }
        existing // ensures it existed
        view(ReportSchedulesTable.selectAll().where { ReportSchedulesTable.id eq id }.single())
    }

    fun list(): List<ScheduleView> = tx.execute {
        ReportSchedulesTable.selectAll().where { ReportSchedulesTable.tenantId eq support.tenantId() }
            .orderBy(ReportSchedulesTable.createdAt to SortOrder.DESC).map(::view)
    }

    fun get(id: UUID): ScheduleView = tx.execute { view(load(id)) }

    fun pause(id: UUID): ScheduleView = setStatus(id, "PAUSED")
    fun resume(id: UUID): ScheduleView = setStatus(id, "ACTIVE")

    private fun setStatus(id: UUID, status: String): ScheduleView = tx.execute {
        load(id)
        ReportSchedulesTable.update({ ReportSchedulesTable.id eq id }) { it[ReportSchedulesTable.status] = status }
        view(ReportSchedulesTable.selectAll().where { ReportSchedulesTable.id eq id }.single())
    }

    /** Enqueues this schedule's export now (manual run); produced in the background as the owner. */
    fun runNow(id: UUID): String = tx.execute {
        val row = load(id)
        exports.enqueueScheduled(row[ReportSchedulesTable.reportKey], resolveParams(row[ReportSchedulesTable.params], row[ReportSchedulesTable.frequency], LocalDate.now(support.zone())), row[ReportSchedulesTable.format], id)
    }

    // ---- runner support (called by ScheduleRunner) ----

    data class Due(val id: UUID, val ownerId: UUID, val reportKey: String, val format: String, val params: Map<String, Any?>, val frequency: String, val name: String)

    /** Claims due schedules for the tenant, advancing each so it isn't re-fired; runs in the worker's tenant tx. */
    fun claimDue(now: Instant): List<Due> = ReportSchedulesTable.selectAll()
        .where { (ReportSchedulesTable.tenantId eq support.tenantId()) and (ReportSchedulesTable.status eq "ACTIVE") and (ReportSchedulesTable.nextRunAt less now) }
        .map {
            Due(it[ReportSchedulesTable.id].value, it[ReportSchedulesTable.runAsUserId].value, it[ReportSchedulesTable.reportKey], it[ReportSchedulesTable.format], it[ReportSchedulesTable.params], it[ReportSchedulesTable.frequency], it[ReportSchedulesTable.name])
        }
        .onEach { due ->
            ReportSchedulesTable.update({ ReportSchedulesTable.id eq due.id }) { it[lastRunAt] = now; it[nextRunAt] = nextAfter(due.id, now) }
        }

    /** Fires one due schedule as its owner (the runner has impersonated them); pauses it if the owner can no longer run it. */
    fun fire(due: Due): Boolean = tx.execute {
        try {
            exports.enqueueScheduled(due.reportKey, resolveParams(due.params, due.frequency, LocalDate.now(support.zone())), due.format, due.id)
            true
        } catch (e: Exception) {
            ReportSchedulesTable.update({ ReportSchedulesTable.id eq due.id }) { it[status] = "PAUSED" }
            false
        }
    }

    // ---- internals ----

    private fun validate(req: ScheduleRequest): Triple<ReportProvider, String, String> {
        val provider = byKey[req.reportKey?.trim()] ?: throw ApiErrors.reportNotFound()
        if (req.name.isNullOrBlank()) throw ApiErrors.validationFailed(mapOf("name" to "Required"))
        if (!provider.canRun()) throw ApiErrors.accessDenied()
        val fmt = (req.format ?: "").trim().uppercase()
        if (fmt !in setOf("CSV", "XLSX", "PDF")) throw ApiErrors.formatNotSupported(fmt)
        if (fmt == "PDF" && !provider.supportsPdf) throw ApiErrors.formatNotSupported(fmt)
        val freq = (req.frequency ?: "").trim().uppercase()
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY")) throw ApiErrors.validationFailed(mapOf("frequency" to "Must be DAILY, WEEKLY or MONTHLY"))
        if (freq == "WEEKLY" && req.dayOfWeek !in 1..7) throw ApiErrors.validationFailed(mapOf("dayOfWeek" to "Required (1=Monday..7=Sunday) for a weekly schedule"))
        if (freq == "MONTHLY" && req.dayOfMonth !in 1..28) throw ApiErrors.validationFailed(mapOf("dayOfMonth" to "Required (1..28) for a monthly schedule"))
        // Every recipient must be an active tenant member who could run the report themselves.
        val members = TenantMembershipsTable.select(TenantMembershipsTable.userId)
            .where { (TenantMembershipsTable.tenantId eq support.tenantId()) and (TenantMembershipsTable.userId inList req.recipients) }
            .map { it[TenantMembershipsTable.userId].value }.toSet()
        req.recipients.forEach { rid ->
            if (rid !in members) throw ApiErrors.recipientNotPermitted(rid)
            if (provider.permission != null && !permissions.hasPermission(rid, provider.permission!!)) throw ApiErrors.recipientNotPermitted(rid)
        }
        return Triple(provider, fmt, freq)
    }

    private fun load(id: UUID): ResultRow = ReportSchedulesTable.selectAll()
        .where { (ReportSchedulesTable.id eq id) and (ReportSchedulesTable.tenantId eq support.tenantId()) }
        .firstOrNull() ?: throw ApiErrors.scheduleNotFound()

    private fun nextAfter(id: UUID, now: Instant): Instant {
        val row = ReportSchedulesTable.selectAll().where { ReportSchedulesTable.id eq id }.single()
        return computeNext(now, row[ReportSchedulesTable.frequency], row[ReportSchedulesTable.dayOfWeek], row[ReportSchedulesTable.dayOfMonth], row[ReportSchedulesTable.sendAt])
    }

    /** The next send instant strictly after [now], in the tenant's time zone. */
    private fun computeNext(now: Instant, frequency: String, dayOfWeek: Int?, dayOfMonth: Int?, sendAt: LocalTime): Instant {
        val zone = support.zone()
        val nowZoned = now.atZone(zone)
        var candidateDate = nowZoned.toLocalDate()
        repeat(400) {
            val matches = when (frequency) {
                "DAILY" -> true
                "WEEKLY" -> candidateDate.dayOfWeek.value == (dayOfWeek ?: 1)
                else -> candidateDate.dayOfMonth == (dayOfMonth ?: 1)
            }
            if (matches) {
                val at = ZonedDateTime.of(candidateDate, sendAt, zone)
                if (at.toInstant().isAfter(now)) return at.toInstant()
            }
            candidateDate = candidateDate.plusDays(1)
        }
        return now.plus(1, ChronoUnit.DAYS)
    }

    /** Replaces the relative-date placeholders in string params with the previous complete period (docs/13 §2.3). */
    private fun resolveParams(params: Map<String, Any?>, frequency: String, runDate: LocalDate): Map<String, Any?> {
        val (start, end) = when (frequency) {
            "WEEKLY" -> {
                val lastSunday = runDate.minusDays(1).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY))
                lastSunday.minusDays(6) to lastSunday
            }
            "MONTHLY" -> {
                val lastMonth = runDate.minusMonths(1)
                lastMonth.withDayOfMonth(1) to lastMonth.with(TemporalAdjusters.lastDayOfMonth())
            }
            else -> runDate.minusDays(1) to runDate.minusDays(1)
        }
        val subs = mapOf("{{periodStart}}" to start.toString(), "{{periodEnd}}" to end.toString(), "{{today}}" to runDate.toString())
        return params.mapValues { (_, v) -> if (v is String) subs[v] ?: v else v }
    }

    private fun view(r: ResultRow) = ScheduleView(
        id = r[ReportSchedulesTable.id].value, name = r[ReportSchedulesTable.name], reportKey = r[ReportSchedulesTable.reportKey],
        format = r[ReportSchedulesTable.format], frequency = r[ReportSchedulesTable.frequency], dayOfWeek = r[ReportSchedulesTable.dayOfWeek],
        dayOfMonth = r[ReportSchedulesTable.dayOfMonth], sendAt = r[ReportSchedulesTable.sendAt],
        recipients = r[ReportSchedulesTable.recipients].mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.map { UserRef.of(it) },
        owner = UserRef.of(r[ReportSchedulesTable.runAsUserId].value), status = r[ReportSchedulesTable.status],
        lastRunAt = r[ReportSchedulesTable.lastRunAt], nextRunAt = r[ReportSchedulesTable.nextRunAt], createdAt = r[ReportSchedulesTable.createdAt],
    )

    companion object {
        private val DEFAULT_SEND_AT: LocalTime = LocalTime.of(7, 0)
    }
}
