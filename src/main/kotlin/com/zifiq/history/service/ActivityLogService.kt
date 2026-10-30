package com.zifiq.history.service

import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
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
import com.zifiq.common.search.anyOf
import com.zifiq.history.dto.ActivityRow
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.ref
import com.zifiq.platform.events.model.DomainEventsTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notLike
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Activity log (docs/10 §4.7): the business-event trail read from domain_events. `module` filters by
 * event-type prefix; `costAccessOnly` surfaces cost-field access (PRD §9). Events are location-scoped
 * (global, null-location events stay visible), and `history.violation.*` events are hidden from
 * callers without `history.violations.read` so an investigation is not leaked through the log.
 */
@Service
class ActivityLogService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
) {
    fun list(request: ListSearchRequest): PageResult<ActivityRow> = tx.execute {
        val context = support.filterContext()
        val parsed = search.parse(request.q)
        val modules = request.params["module"].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val params = request.params.filterKeys { it != "module" }
        val condition = listOfNotNull(
            DomainEventsTable.tenantId eq support.tenantId(),
            moduleCondition(modules),
            scopeCondition(),
            violationExclusion(),
            search.condition(parsed),
            filters.fromParams(params, context),
            filters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) search.rank(parsed) + filters.sort(emptyList(), context) else filters.sort(request.sortParams, context)
        val page = Paging.fetch(DomainEventsTable.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        page.withItems(page.items.map(::toRow)).copy(meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filters.metadata(support.filterContext()) }

    /** Every event on one record, oldest first — the cross-module "History" tab. */
    fun entityTimeline(aggregateType: String, aggregateId: UUID): List<ActivityRow> = tx.execute {
        val condition = listOfNotNull(
            (DomainEventsTable.tenantId eq support.tenantId()) and (DomainEventsTable.aggregateType eq aggregateType) and (DomainEventsTable.aggregateId eq aggregateId),
            scopeCondition(),
            violationExclusion(),
        ).allOf()!!
        DomainEventsTable.selectAll().where(condition)
            .orderBy(DomainEventsTable.occurredAt to SortOrder.ASC, DomainEventsTable.seq to SortOrder.ASC)
            .map(::toRow)
    }

    private fun moduleCondition(modules: List<String>): Op<Boolean>? =
        modules.map { DomainEventsTable.eventType like "${it.replace("%", "")}.%" }.anyOf()

    private fun scopeCondition(): Op<Boolean>? {
        val visible = support.visibleLocations() ?: return null
        return DomainEventsTable.locationId.isNull() or (DomainEventsTable.locationId inList visible)
    }

    private fun violationExclusion(): Op<Boolean>? =
        if (support.can(VIOLATIONS_READ)) null else DomainEventsTable.eventType notLike "history.violation%"

    private fun toRow(r: ResultRow) = ActivityRow(
        occurredAt = r[DomainEventsTable.occurredAt],
        eventType = r[DomainEventsTable.eventType],
        summary = r[DomainEventsTable.summary],
        ref = r[DomainEventsTable.aggregateRef],
        aggregateType = r[DomainEventsTable.aggregateType],
        location = ref(r[DomainEventsTable.locationId], Ref::location),
        actor = UserRef.of(r[DomainEventsTable.actorUserId]),
        actorLabel = r[DomainEventsTable.actorLabel],
        payload = r[DomainEventsTable.payload],
    )

    private val costAccessExpr = RawExpression<Boolean>({ +"(event_type = 'security.cost-data.viewed')" })

    private val filters = FilterSpec(
        fields = listOf(
            FilterField("eventType", "Event type", FilterType.TEXT, DomainEventsTable.eventType),
            FilterField("aggregateType", "Record type", FilterType.TEXT, DomainEventsTable.aggregateType),
            FilterField("ref", "Reference", FilterType.TEXT, DomainEventsTable.aggregateRef, aliases = setOf("aggregateRef")),
            FilterField("actorUserId", "Actor", FilterType.UUID, DomainEventsTable.actorUserId, aliases = setOf("actor")),
            FilterField("locationId", "Location", FilterType.LOCATION, DomainEventsTable.locationId),
            FilterField("costAccessOnly", "Cost-field access only", FilterType.BOOLEAN, costAccessExpr),
            FilterField("occurredAt", "When", FilterType.DATETIME, DomainEventsTable.occurredAt, sortable = true),
        ),
        primaryDateKey = "occurredAt",
        defaultSort = listOf("occurredAt" to SortOrder.DESC),
        tiebreaker = DomainEventsTable.id,
    )

    private val search = SearchSpec(
        fields = listOf(
            SearchField(DomainEventsTable.aggregateRef, SearchKind.REFERENCE, "Reference"),
            SearchField(RawExpression<String>({ +"domain_events.search_vector" }), SearchKind.FULLTEXT, "Summary"),
        ),
        qualifiers = filters,
    )

    companion object {
        const val VIOLATIONS_READ = "history.violations.read"
    }
}
