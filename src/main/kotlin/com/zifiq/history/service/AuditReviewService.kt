package com.zifiq.history.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
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
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.history.dto.AuditReviewLookupRow
import com.zifiq.history.dto.AuditReviewRow
import com.zifiq.history.dto.AuditReviewUpsertRequest
import com.zifiq.history.model.AuditReviewsTable
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.tenancy.TenantScopedTransaction
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
 * Audit reviews (docs/10 §4.11): a reviewer marks any record UNDER_REVIEW / REVIEWED / FLAGGED.
 * One state per (recordType, recordId); the trail of changes is the `history.review.updated` events.
 */
@Service
class AuditReviewService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
) {
    fun upsert(request: AuditReviewUpsertRequest): AuditReviewRow = tx.execute {
        val recordType = request.recordType!!.trim().uppercase()
        if (recordType !in RECORD_TYPES) throw ApiErrors.validationFailed(mapOf("recordType" to "unknown record type"))
        val status = request.status!!.trim().uppercase()
        if (status !in STATUSES) throw ApiErrors.validationFailed(mapOf("status" to "UNDER_REVIEW, REVIEWED or FLAGGED"))
        val note = request.note?.trim()?.ifEmpty { null }
        if (status == "FLAGGED" && note == null) throw ApiErrors.validationFailed(mapOf("note" to "a flagged record needs a note"))
        val ref = request.recordRef!!.trim()
        val now = Instant.now()
        val existing = AuditReviewsTable.selectAll().where {
            (AuditReviewsTable.tenantId eq support.tenantId()) and (AuditReviewsTable.recordType eq recordType) and (AuditReviewsTable.recordId eq request.recordId!!)
        }.firstOrNull()
        val id = if (existing != null) {
            AuditReviewsTable.update({ AuditReviewsTable.id eq existing[AuditReviewsTable.id] }) {
                it[AuditReviewsTable.status] = status
                it[AuditReviewsTable.note] = note
                it[AuditReviewsTable.recordRef] = ref
                it[reviewerUserId] = support.userId()
                it[updatedAt] = now
            }
            existing[AuditReviewsTable.id].value
        } else {
            AuditReviewsTable.insertAndGetId {
                it[tenantId] = support.tenantId()
                it[AuditReviewsTable.recordType] = recordType
                it[recordId] = request.recordId!!
                it[AuditReviewsTable.recordRef] = ref
                it[AuditReviewsTable.status] = status
                it[AuditReviewsTable.note] = note
                it[reviewerUserId] = support.userId()
                it[updatedAt] = now
                it[createdAt] = now
            }.value
        }
        support.record("history.review.updated", "audit_review", id, ref, "$ref ($recordType) marked ${statusLabel(status)}",
            mapOf("recordType" to recordType, "recordRef" to ref, "status" to status))
        row(id)
    }

    fun list(request: ListSearchRequest): PageResult<AuditReviewRow> = tx.execute {
        val context = support.filterContext()
        val parsed = search.parse(request.q)
        val condition = listOfNotNull(
            AuditReviewsTable.tenantId eq support.tenantId(),
            search.condition(parsed),
            filters.fromParams(request.params, context),
            filters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) search.rank(parsed) + filters.sort(emptyList(), context) else filters.sort(request.sortParams, context)
        val page = Paging.fetch(AuditReviewsTable.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        page.withItems(page.items.map(::toRow)).copy(meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun filterMetadata(): FilterMetadata = tx.execute { filters.metadata(support.filterContext()) }

    /** Batch lookup for report rows and detail screens: the review badge for records the caller already holds. */
    fun lookup(recordType: String, recordIds: List<UUID>): List<AuditReviewLookupRow> = tx.execute {
        if (recordIds.isEmpty()) return@execute emptyList()
        val type = recordType.trim().uppercase()
        AuditReviewsTable.selectAll().where {
            (AuditReviewsTable.tenantId eq support.tenantId()) and (AuditReviewsTable.recordType eq type) and (AuditReviewsTable.recordId inList recordIds)
        }.map {
            AuditReviewLookupRow(
                recordId = it[AuditReviewsTable.recordId],
                status = it[AuditReviewsTable.status],
                note = it[AuditReviewsTable.note],
                reviewer = UserRef(it[AuditReviewsTable.reviewerUserId].value),
                updatedAt = it[AuditReviewsTable.updatedAt],
            )
        }
    }

    private fun row(id: UUID): AuditReviewRow =
        toRow(AuditReviewsTable.selectAll().where { AuditReviewsTable.id eq id }.single())

    private fun toRow(r: ResultRow) = AuditReviewRow(
        id = r[AuditReviewsTable.id].value,
        recordType = r[AuditReviewsTable.recordType],
        recordId = r[AuditReviewsTable.recordId],
        recordRef = r[AuditReviewsTable.recordRef],
        status = r[AuditReviewsTable.status],
        note = r[AuditReviewsTable.note],
        reviewer = UserRef(r[AuditReviewsTable.reviewerUserId].value),
        updatedAt = r[AuditReviewsTable.updatedAt],
        createdAt = r[AuditReviewsTable.createdAt],
    )

    private val filters = FilterSpec(
        fields = listOf(
            FilterField("recordType", "Record type", FilterType.ENUM, AuditReviewsTable.recordType, RECORD_TYPES.map { FilterOption(it, it.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) }, facetable = true),
            FilterField("status", "Status", FilterType.ENUM, AuditReviewsTable.status, STATUSES.map { FilterOption(it, statusLabel(it)) }, facetable = true),
            FilterField("reviewerUserId", "Reviewer", FilterType.UUID, AuditReviewsTable.reviewerUserId, aliases = setOf("reviewer")),
            FilterField("updatedAt", "Updated", FilterType.DATETIME, AuditReviewsTable.updatedAt, sortable = true),
            FilterField("createdAt", "Created", FilterType.DATETIME, AuditReviewsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "updatedAt",
        defaultSort = listOf("updatedAt" to SortOrder.DESC),
        tiebreaker = AuditReviewsTable.id,
    )

    private val search = SearchSpec(
        fields = listOf(SearchField(AuditReviewsTable.recordRef, SearchKind.REFERENCE, "Record reference")),
        qualifiers = filters,
    )

    companion object {
        val RECORD_TYPES = setOf(
            "WALLET_WITHDRAWAL", "RUNNING_COST", "PETTY_CASH_PAYMENT", "PAYMENT_RECEIPT", "QUANTITY_ADJUSTMENT",
            "STOCK_TRANSFER", "CUSTOMER_RETURN", "INSTANT_DISCOUNT", "SALE", "INVOICE", "CREDIT_NOTE",
        )
        val STATUSES = setOf("UNDER_REVIEW", "REVIEWED", "FLAGGED")

        fun statusLabel(status: String) = when (status) {
            "UNDER_REVIEW" -> "Under review"
            "REVIEWED" -> "Reviewed"
            "FLAGGED" -> "Flagged"
            else -> status
        }
    }
}
