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
import com.zifiq.history.dto.LinkedRef
import com.zifiq.history.dto.StaffMember
import com.zifiq.history.dto.StaffViolationPanel
import com.zifiq.history.dto.StaffViolationStats
import com.zifiq.history.dto.ViolationConcludeRequest
import com.zifiq.history.dto.ViolationDetail
import com.zifiq.history.dto.ViolationEventView
import com.zifiq.history.dto.ViolationNoteRequest
import com.zifiq.history.dto.ViolationRaiseRequest
import com.zifiq.history.dto.ViolationReopenRequest
import com.zifiq.history.dto.ViolationRow
import com.zifiq.history.dto.ViolationUpdateRequest
import com.zifiq.history.model.ViolationRecordEventsTable
import com.zifiq.history.model.ViolationRecordsTable
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Violations / Fraud History (docs/10 §4.4): confidential records raised against a member of staff,
 * with an append-only investigation trail. A record is closed with an outcome and a written
 * conclusion by someone other than the person who raised it (a two-person rule; an Administrator is
 * exempt). A caller never sees a record where they are the subject — filtered out of every query.
 * Events carry no description text, only the ref/category/severity.
 */
@Service
class ViolationService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
    private val sequences: DocumentSequenceService,
    private val notify: Notify,
) {
    fun raise(request: ViolationRaiseRequest, source: String = "MANUAL", auditAlertId: UUID? = null): ViolationDetail = tx.execute {
        val caller = support.userId()
        val subject = request.subjectUserId ?: throw ApiErrors.validationFailed(mapOf("subjectUserId" to "required"))
        if (subject == caller) throw ApiErrors.cannotRaiseAgainstSelf()
        if (!isMember(subject)) throw ApiErrors.validationFailed(mapOf("subjectUserId" to "not a member of this tenant"))
        val category = request.category!!.trim().uppercase()
        if (category !in CATEGORIES) throw ApiErrors.validationFailed(mapOf("category" to "unknown category"))
        val severity = request.severity!!.trim().uppercase()
        if (severity !in SEVERITIES) throw ApiErrors.validationFailed(mapOf("severity" to "LOW, MEDIUM or HIGH"))
        request.locationId?.let { support.assertAccess(it) }
        val ref = sequences.next("VN")
        val now = Instant.now()
        val id = ViolationRecordsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[ViolationRecordsTable.ref] = ref
            it[subjectUserId] = subject
            it[locationId] = request.locationId
            it[ViolationRecordsTable.category] = category
            it[description] = request.description!!.trim()
            it[amountInvolved] = request.amountInvolved
            it[ViolationRecordsTable.severity] = severity
            it[status] = "UNDER_INVESTIGATION"
            it[occurredOn] = request.occurredOn
            it[raisedByUserId] = caller
            it[raisedAt] = now
            it[sourceKind] = source
            it[ViolationRecordsTable.auditAlertId] = auditAlertId
            it[linkedRefs] = request.linkedRefs.map { l -> mapOf("type" to l.type, "ref" to l.ref) }
        }.value
        addEvent(id, "RAISED", null, severity, null)
        emitAndNotify(id, ref, category, severity, request.locationId, "raised", "history.violation.raised")
        detail(id)
    }

    fun addNote(id: UUID, request: ViolationNoteRequest): ViolationDetail = tx.execute {
        recordForUpdate(id)
        addEvent(id, "NOTE", null, null, request.note!!.trim())
        detail(id)
    }

    fun update(id: UUID, request: ViolationUpdateRequest): ViolationDetail = tx.execute {
        val r = recordForUpdate(id)
        if (r[ViolationRecordsTable.status] != "UNDER_INVESTIGATION") throw ApiErrors.violationAlreadyConcluded()
        val note = request.note!!.trim()
        request.severity?.trim()?.uppercase()?.let { sev ->
            if (sev !in SEVERITIES) throw ApiErrors.validationFailed(mapOf("severity" to "LOW, MEDIUM or HIGH"))
            val from = r[ViolationRecordsTable.severity]
            ViolationRecordsTable.update({ ViolationRecordsTable.id eq id }) { it[severity] = sev }
            addEvent(id, "SEVERITY_CHANGED", from, sev, note)
        }
        if (request.amountInvolved != null) {
            val from = r[ViolationRecordsTable.amountInvolved]
            ViolationRecordsTable.update({ ViolationRecordsTable.id eq id }) { it[amountInvolved] = request.amountInvolved }
            addEvent(id, "AMOUNT_CHANGED", from?.toPlainString(), request.amountInvolved.toPlainString(), note)
        }
        detail(id)
    }

    fun conclude(id: UUID, request: ViolationConcludeRequest): ViolationDetail = tx.execute {
        val r = recordForUpdate(id)
        if (r[ViolationRecordsTable.status] != "UNDER_INVESTIGATION") throw ApiErrors.violationAlreadyConcluded()
        val outcome = request.outcome!!.trim().uppercase()
        if (outcome !in OUTCOMES) throw ApiErrors.validationFailed(mapOf("outcome" to "SUBSTANTIATED, RESOLVED or DISMISSED"))
        val caller = support.userId()
        if (r[ViolationRecordsTable.raisedByUserId].value == caller && !(support.can(MANAGE) && support.isAdministrator())) {
            throw ApiErrors.concluderMustDiffer()
        }
        ViolationRecordsTable.update({ ViolationRecordsTable.id eq id }) {
            it[status] = outcome
            it[conclusion] = request.conclusion!!.trim()
            it[concludedByUserId] = caller
            it[concludedAt] = Instant.now()
        }
        addEvent(id, "CONCLUDED", "UNDER_INVESTIGATION", outcome, request.conclusion!!.trim())
        emitAndNotify(id, r[ViolationRecordsTable.ref], r[ViolationRecordsTable.category], r[ViolationRecordsTable.severity],
            r[ViolationRecordsTable.locationId], "concluded $outcome", "history.violation.concluded")
        detail(id)
    }

    fun reopen(id: UUID, request: ViolationReopenRequest): ViolationDetail = tx.execute {
        val r = recordForUpdate(id)
        if (r[ViolationRecordsTable.status] == "UNDER_INVESTIGATION") throw ApiErrors.validationFailed(mapOf("status" to "this record is already under investigation"))
        // The previous conclusion stays in the events trail; the record goes back to open.
        ViolationRecordsTable.update({ ViolationRecordsTable.id eq id }) {
            it[status] = "UNDER_INVESTIGATION"
            it[conclusion] = null
            it[concludedByUserId] = null
            it[concludedAt] = null
        }
        addEvent(id, "REOPENED", r[ViolationRecordsTable.status], "UNDER_INVESTIGATION", request.reason!!.trim())
        emitAndNotify(id, r[ViolationRecordsTable.ref], r[ViolationRecordsTable.category], r[ViolationRecordsTable.severity],
            r[ViolationRecordsTable.locationId], "reopened", "history.violation.reopened")
        detail(id)
    }

    fun list(request: ListSearchRequest): PageResult<ViolationRow> = tx.execute {
        val context = support.filterContext()
        val parsed = search.parse(request.q)
        val condition = listOfNotNull(
            ViolationRecordsTable.tenantId eq support.tenantId(),
            ViolationRecordsTable.subjectUserId neq support.userId(),              // self-exclusion (§3)
            support.visibleLocations()?.let { visibleCondition(it) },
            search.condition(parsed),
            filters.fromParams(request.params, context),
            filters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) search.rank(parsed) + filters.sort(emptyList(), context) else filters.sort(request.sortParams, context)
        val page = Paging.fetch(ViolationRecordsTable.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        page.withItems(page.items.map(::toRow)).copy(meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filters.metadata(support.filterContext()) }

    fun detail(id: UUID): ViolationDetail = tx.execute { toDetail(record(id)) }

    fun staffPanel(userId: UUID): StaffViolationPanel = tx.execute {
        if (userId == support.userId()) throw ApiErrors.accessDenied()
        val records = ViolationRecordsTable.selectAll()
            .where { (ViolationRecordsTable.tenantId eq support.tenantId()) and (ViolationRecordsTable.subjectUserId eq userId) }
            .orderBy(ViolationRecordsTable.raisedAt to SortOrder.DESC)
            .map { it }
        val location = records.firstOrNull()?.get(ViolationRecordsTable.locationId)
        val onFile = records.size
        val underInvestigation = records.count { it[ViolationRecordsTable.status] == "UNDER_INVESTIGATION" }
        val substantiated = records.count { it[ViolationRecordsTable.status] == "SUBSTANTIATED" }
        val cleared = records.count { it[ViolationRecordsTable.status] in setOf("DISMISSED", "RESOLVED") }
        val amount = records.filter { it[ViolationRecordsTable.status] in setOf("UNDER_INVESTIGATION", "SUBSTANTIATED") }
            .fold(BigDecimal.ZERO) { acc, r -> acc + (r[ViolationRecordsTable.amountInvolved] ?: BigDecimal.ZERO) }
        StaffViolationPanel(
            staff = StaffMember(UserRef(userId), ref(location, Ref::location)),
            stats = StaffViolationStats(onFile, underInvestigation, substantiated, amount, cleared),
            records = records.map(::toDetail),
        )
    }

    // ---- helpers ----

    private fun isMember(userId: UUID): Boolean =
        TenantMembershipsTable.selectAll().where { (TenantMembershipsTable.tenantId eq support.tenantId()) and (TenantMembershipsTable.userId eq userId) }.any()

    private fun visibleCondition(visible: Set<UUID>) =
        (ViolationRecordsTable.locationId inList visible) or ViolationRecordsTable.locationId.isNull()

    private fun addEvent(recordId: UUID, kind: String, from: String?, to: String?, note: String?) {
        ViolationRecordEventsTable.insert {
            it[tenantId] = support.tenantId()
            it[violationRecordId] = recordId
            it[ViolationRecordEventsTable.kind] = kind
            it[fromValue] = from
            it[toValue] = to
            it[ViolationRecordEventsTable.note] = note
            it[byUserId] = support.userId()
            it[at] = Instant.now()
        }
    }

    private fun emitAndNotify(id: UUID, ref: String, category: String, severity: String, locationId: UUID?, verb: String, eventType: String) {
        support.record(eventType, "violation_record", id, ref, "$ref $verb ($category, $severity)",
            mapOf("ref" to ref, "category" to category, "severity" to severity), locationId)
        notify.holdersOf("history.violations.read", locationId, NotificationCategory.ALERT, "Violation $verb — $ref",
            "$ref ($category, $severity)", linkType = "violation", linkRef = ref, exclude = support.userId())
    }

    private fun record(id: UUID): ResultRow {
        val r = ViolationRecordsTable.selectAll().where { (ViolationRecordsTable.id eq id) and (ViolationRecordsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.violationNotFound()
        if (r[ViolationRecordsTable.subjectUserId].value == support.userId()) throw ApiErrors.violationNotFound()
        support.visibleLocations()?.let { visible ->
            val loc = r[ViolationRecordsTable.locationId]
            if (loc != null && loc !in visible) throw ApiErrors.violationNotFound()
        }
        return r
    }

    private fun recordForUpdate(id: UUID): ResultRow {
        val r = record(id)
        ViolationRecordsTable.selectAll().where { ViolationRecordsTable.id eq id }.forUpdate().firstOrNull()
        return r
    }

    private fun eventsOf(recordId: UUID): List<ViolationEventView> =
        ViolationRecordEventsTable.selectAll().where { ViolationRecordEventsTable.violationRecordId eq recordId }
            .orderBy(ViolationRecordEventsTable.at to SortOrder.ASC)
            .map {
                ViolationEventView(
                    kind = it[ViolationRecordEventsTable.kind], fromValue = it[ViolationRecordEventsTable.fromValue], toValue = it[ViolationRecordEventsTable.toValue],
                    note = it[ViolationRecordEventsTable.note], by = UserRef(it[ViolationRecordEventsTable.byUserId].value), at = it[ViolationRecordEventsTable.at],
                )
            }

    private fun toRow(r: ResultRow) = ViolationRow(
        id = r[ViolationRecordsTable.id].value, ref = r[ViolationRecordsTable.ref], dateRaised = r[ViolationRecordsTable.raisedAt],
        subject = UserRef(r[ViolationRecordsTable.subjectUserId].value), location = ref(r[ViolationRecordsTable.locationId], Ref::location),
        category = r[ViolationRecordsTable.category], description = r[ViolationRecordsTable.description], amountInvolved = r[ViolationRecordsTable.amountInvolved],
        severity = r[ViolationRecordsTable.severity], raisedBy = UserRef(r[ViolationRecordsTable.raisedByUserId].value), outcome = r[ViolationRecordsTable.status],
    )

    private fun toDetail(r: ResultRow) = ViolationDetail(
        id = r[ViolationRecordsTable.id].value, ref = r[ViolationRecordsTable.ref], subject = UserRef(r[ViolationRecordsTable.subjectUserId].value),
        location = ref(r[ViolationRecordsTable.locationId], Ref::location), category = r[ViolationRecordsTable.category], description = r[ViolationRecordsTable.description],
        amountInvolved = r[ViolationRecordsTable.amountInvolved], severity = r[ViolationRecordsTable.severity], status = r[ViolationRecordsTable.status],
        source = r[ViolationRecordsTable.sourceKind], occurredOn = r[ViolationRecordsTable.occurredOn],
        linkedRefs = r[ViolationRecordsTable.linkedRefs].map { LinkedRef(it["type"]?.toString() ?: "", it["ref"]?.toString() ?: "") },
        raisedBy = UserRef(r[ViolationRecordsTable.raisedByUserId].value), raisedAt = r[ViolationRecordsTable.raisedAt],
        conclusion = r[ViolationRecordsTable.conclusion], concludedBy = UserRef.of(r[ViolationRecordsTable.concludedByUserId]), concludedAt = r[ViolationRecordsTable.concludedAt],
        events = eventsOf(r[ViolationRecordsTable.id].value),
    )

    private val filters = FilterSpec(
        fields = listOf(
            FilterField("subjectUserId", "Subject", FilterType.UUID, ViolationRecordsTable.subjectUserId, aliases = setOf("subject")),
            FilterField("locationId", "Location", FilterType.LOCATION, ViolationRecordsTable.locationId),
            FilterField("status", "Outcome", FilterType.ENUM, ViolationRecordsTable.status,
                listOf("UNDER_INVESTIGATION", "SUBSTANTIATED", "RESOLVED", "DISMISSED").map { FilterOption(it, it.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) }, facetable = true),
            FilterField("category", "Category", FilterType.ENUM, ViolationRecordsTable.category, CATEGORIES.map { FilterOption(it, it.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) }, facetable = true),
            FilterField("severity", "Severity", FilterType.ENUM, ViolationRecordsTable.severity, SEVERITIES.map { FilterOption(it, it.lowercase().replaceFirstChar { c -> c.uppercase() }) }, facetable = true),
            FilterField("source", "Source", FilterType.ENUM, ViolationRecordsTable.sourceKind, listOf(FilterOption("MANUAL", "Manual"), FilterOption("ALERT", "From an alert"))),
            FilterField("raisedBy", "Raised by", FilterType.UUID, ViolationRecordsTable.raisedByUserId),
            FilterField("amountInvolved", "Amount", FilterType.MONEY, ViolationRecordsTable.amountInvolved, sortable = true),
            FilterField("raisedAt", "Date raised", FilterType.DATETIME, ViolationRecordsTable.raisedAt, sortable = true),
            FilterField("occurredOn", "Occurred on", FilterType.DATE, ViolationRecordsTable.occurredOn, sortable = true),
        ),
        primaryDateKey = "raisedAt",
        defaultSort = listOf("raisedAt" to SortOrder.DESC),
        tiebreaker = ViolationRecordsTable.id,
    )

    private val search = SearchSpec(
        fields = listOf(
            SearchField(ViolationRecordsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(RawExpression<String>({ +"violation_records.search_vector" }), SearchKind.FULLTEXT, "Description"),
        ),
        qualifiers = filters,
    )

    companion object {
        const val MANAGE = "history.violations.manage"
        val CATEGORIES = setOf(
            "DEALER_FUNDS_TAMPERING", "UNAUTHORISED_WITHDRAWAL", "CASH_SHORTAGE", "STOCK_DISCREPANCY",
            "UNAUTHORISED_DISCOUNT", "RECEIPT_MANIPULATION", "DATA_MANIPULATION", "POLICY_BREACH", "OTHER",
        )
        val SEVERITIES = setOf("LOW", "MEDIUM", "HIGH")
        val OUTCOMES = setOf("SUBSTANTIATED", "RESOLVED", "DISMISSED")
    }
}
