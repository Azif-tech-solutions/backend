package com.zifiq.history.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.history.dto.AlertDismissRequest
import com.zifiq.history.dto.AuditAlertRow
import com.zifiq.history.dto.ViolationDetail
import com.zifiq.history.dto.ViolationRaiseRequest
import com.zifiq.history.model.AuditAlertsTable
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
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

/**
 * Audit alerts (docs/10 §4.4): proposals a detector raises from another module's events. A person
 * decides whether an alert becomes a violation record (PRD rule 13) or is dismissed. Deduped by
 * `dedupeKey` so the same trigger never alerts twice.
 */
@Service
class AuditAlertService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
    private val violations: ViolationService,
    private val notify: Notify,
) {
    /**
     * Raise an OPEN alert, or do nothing if one already exists for [dedupeKey]. Runs in the caller's
     * (detector's) transaction. Returns the new alert id, or null when it was a duplicate.
     */
    fun raise(ruleCode: String, subjectUserId: UUID?, locationId: UUID?, summary: String, amount: BigDecimal?, evidence: Map<String, Any?>, dedupeKey: String): UUID? {
        val existing = AuditAlertsTable.selectAll()
            .where { (AuditAlertsTable.tenantId eq support.tenantId()) and (AuditAlertsTable.dedupeKey eq dedupeKey) }
            .any()
        if (existing) return null
        val id = AuditAlertsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[AuditAlertsTable.ruleCode] = ruleCode
            it[AuditAlertsTable.subjectUserId] = subjectUserId
            it[AuditAlertsTable.locationId] = locationId
            it[AuditAlertsTable.summary] = summary.take(300)
            it[AuditAlertsTable.amount] = amount
            it[AuditAlertsTable.evidence] = evidence
            it[status] = "OPEN"
            it[AuditAlertsTable.dedupeKey] = dedupeKey.take(120)
            it[createdAt] = Instant.now()
        }.value
        support.record("history.alert.created", "audit_alert", id, null, summary.take(300), mapOf("ruleCode" to ruleCode), locationId)
        notify.holdersOf("history.violations.read", locationId, NotificationCategory.ALERT, "Audit alert — $ruleCode", summary.take(300), linkType = "audit-alert", exclude = subjectUserId)
        return id
    }

    fun list(request: ListSearchRequest): PageResult<AuditAlertRow> = tx.execute {
        val context = support.filterContext()
        val parsed = search.parse(request.q)
        // Default to OPEN alerts unless a status filter is given.
        val params = if (request.params.keys.any { it == "status" }) request.params else request.params + ("status" to listOf("OPEN"))
        val condition = listOfNotNull(
            AuditAlertsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { AuditAlertsTable.locationId inList it },
            search.condition(parsed),
            filters.fromParams(params, context),
            filters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) search.rank(parsed) + filters.sort(emptyList(), context) else filters.sort(request.sortParams, context)
        val page = Paging.fetch(AuditAlertsTable.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        page.withItems(page.items.map(::toRow)).copy(meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filters.metadata(support.filterContext()) }

    fun dismiss(id: UUID, request: AlertDismissRequest): AuditAlertRow = tx.execute {
        val alert = open(id)
        AuditAlertsTable.update({ AuditAlertsTable.id eq id }) {
            it[status] = "DISMISSED"
            it[dismissReason] = request.reason!!.trim()
            it[decidedByUserId] = support.userId()
            it[decidedAt] = Instant.now()
        }
        support.record("history.alert.dismissed", "audit_alert", id, null, "Alert dismissed: ${request.reason!!.trim()}", emptyMap(), alert[AuditAlertsTable.locationId])
        row(id)
    }

    /** Open a violation record pre-filled from the alert; the body overrides the prefill. */
    fun openRecord(id: UUID, request: ViolationRaiseRequest): ViolationDetail = tx.execute {
        val alert = open(id)
        val evidenceRefs = (alert[AuditAlertsTable.evidence]["refs"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map { com.zifiq.history.dto.LinkedRefInput(it["type"]?.toString(), it["ref"]?.toString()) }
        val prefilled = request.copy(
            subjectUserId = request.subjectUserId ?: alert[AuditAlertsTable.subjectUserId],
            locationId = request.locationId ?: alert[AuditAlertsTable.locationId],
            linkedRefs = request.linkedRefs.ifEmpty { evidenceRefs },
        )
        val detail = violations.raise(prefilled, source = "ALERT", auditAlertId = id)
        AuditAlertsTable.update({ AuditAlertsTable.id eq id }) {
            it[status] = "OPENED_AS_RECORD"
            it[openedRecordId] = detail.id
            it[decidedByUserId] = support.userId()
            it[decidedAt] = Instant.now()
        }
        support.record("history.alert.opened", "audit_alert", id, detail.ref, "Alert opened as ${detail.ref}", mapOf("recordRef" to detail.ref), alert[AuditAlertsTable.locationId])
        detail
    }

    private fun open(id: UUID): ResultRow {
        val alert = AuditAlertsTable.selectAll().where { (AuditAlertsTable.id eq id) and (AuditAlertsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.alertNotFound()
        if (alert[AuditAlertsTable.status] != "OPEN") throw ApiErrors.alertNotOpen()
        return alert
    }

    private fun row(id: UUID): AuditAlertRow =
        toRow(AuditAlertsTable.selectAll().where { AuditAlertsTable.id eq id }.single())

    private fun toRow(r: ResultRow) = AuditAlertRow(
        id = r[AuditAlertsTable.id].value, ruleCode = r[AuditAlertsTable.ruleCode],
        subject = UserRef.of(r[AuditAlertsTable.subjectUserId]), location = ref(r[AuditAlertsTable.locationId], Ref::location),
        summary = r[AuditAlertsTable.summary], amount = r[AuditAlertsTable.amount], status = r[AuditAlertsTable.status],
        evidence = r[AuditAlertsTable.evidence], createdAt = r[AuditAlertsTable.createdAt],
    )

    private val filters = FilterSpec(
        fields = listOf(
            FilterField("status", "Status", FilterType.ENUM, AuditAlertsTable.status,
                listOf("OPEN", "OPENED_AS_RECORD", "DISMISSED").map { FilterOption(it, it.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) }, facetable = true),
            FilterField("ruleCode", "Rule", FilterType.TEXT, AuditAlertsTable.ruleCode, facetable = true),
            FilterField("subjectUserId", "Subject", FilterType.UUID, AuditAlertsTable.subjectUserId, aliases = setOf("subject")),
            FilterField("locationId", "Location", FilterType.LOCATION, AuditAlertsTable.locationId),
            FilterField("amount", "Amount", FilterType.MONEY, AuditAlertsTable.amount, sortable = true),
            FilterField("createdAt", "When", FilterType.DATETIME, AuditAlertsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "createdAt",
        defaultSort = listOf("createdAt" to SortOrder.DESC),
        tiebreaker = AuditAlertsTable.id,
    )

    private val search = SearchSpec(
        fields = listOf(SearchField(AuditAlertsTable.summary, SearchKind.TRIGRAM, "Summary")),
        qualifiers = filters,
    )
}
