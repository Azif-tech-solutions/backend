package com.zifiq.customers.service

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.Expression
import com.zifiq.common.paging.PagingPolicy
import com.zifiq.common.paging.Paging
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.phone.PhoneNumbers
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.customers.dto.CorrespondentAssignRequest
import com.zifiq.customers.dto.CorrespondentDeassignRequest
import com.zifiq.customers.dto.CorrespondentDetail
import com.zifiq.customers.dto.CorrespondentHistoryRow
import com.zifiq.customers.dto.CorrespondentRequest
import com.zifiq.customers.dto.CorrespondentSummary
import com.zifiq.customers.dto.CorrespondentUpdateRequest
import com.zifiq.customers.dto.CustomerCorrespondentRequest
import com.zifiq.customers.dto.CustomerDetail
import com.zifiq.customers.model.CorrespondentAssignmentHistoryTable
import com.zifiq.customers.model.CorrespondentsTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.spi.CorrespondentRef
import com.zifiq.customers.spi.Correspondents
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Correspondents (sales representatives) and their assignment lifecycle — docs/04 §4.10, §5.4. */
@Service
class CorrespondentService(
    private val support: CustomerSupport,
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val customers: CustomerService,
    private val locationAccess: LocationAccessService,
    private val sequences: DocumentSequenceService,
    private val moduleSettings: ModuleSettings,
    private val domainEvents: DomainEvents,
    private val notify: Notify,
) : Correspondents {
    private val joined = CorrespondentsTable
        .join(LocationsTable, JoinType.LEFT, additionalConstraint = { RawOp { +CorrespondentsTable.locationId; +" = "; +LocationsTable.id } })

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(CorrespondentsTable.code, SearchKind.REFERENCE, "Code"),
            SearchField(CorrespondentsTable.name, SearchKind.TRIGRAM, "Name"),
            SearchField(CorrespondentsTable.phoneE164, SearchKind.PHONE, "Phone"),
            SearchField(CorrespondentsTable.email, SearchKind.CODE, "E-mail"),
            SearchField(com.zifiq.common.search.RawExpression<String>({ +"correspondents.search_vector" }), SearchKind.FULLTEXT, "Responsibilities"),
        ),
    )

    // ---- Register / edit ----

    fun create(request: CorrespondentRequest): CorrespondentDetail = tx.execute {
        val phoneE164 = PhoneNumbers.normalise(request.phone) ?: throw ApiErrors.phoneRequired()
        request.userId?.let { validateUser(it, null) }
        val assignNow = request.region != null && request.locationId != null
        if ((request.region != null) != (request.locationId != null)) {
            throw ApiErrors.validationFailed(mapOf("region" to "region and locationId are given together to register and assign in one step"))
        }
        if (assignNow) validateAssignment(request.region!!, request.locationId!!)

        val today = LocalDate.now(moduleSettings.zone())
        val code = sequences.next("CR")
        val id = CorrespondentsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[CorrespondentsTable.code] = code
            it[name] = request.name.trim()
            it[phone] = request.phone.trim()
            it[CorrespondentsTable.phoneE164] = phoneE164
            it[email] = request.email?.trim()?.lowercase()?.ifEmpty { null }
            it[userId] = request.userId
            it[responsibilities] = request.responsibilities?.trim()?.ifEmpty { null }
            it[region] = if (assignNow) request.region else null
            it[locationId] = if (assignNow) request.locationId else null
            it[status] = if (assignNow) "ASSIGNED" else "UNASSIGNED"
            it[assignedAt] = if (assignNow) today else null
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        record(id, code, "customers.correspondent.registered", "Correspondent $code registered — ${request.name.trim()}", request.locationId)
        if (assignNow) {
            history(id, "ASSIGNED", request.region, request.locationId, today, null)
            record(id, code, "customers.correspondent.assigned", "Correspondent $code assigned to ${request.region}", request.locationId)
        }
        notifyLinkedUser(id, "You have been registered as correspondent $code")
        detail(id)
    }

    fun update(id: UUID, request: CorrespondentUpdateRequest): CorrespondentDetail = tx.execute {
        val row = row(id)
        val phoneE164 = PhoneNumbers.normalise(request.phone) ?: throw ApiErrors.phoneRequired()
        request.userId?.let { validateUser(it, id) }
        CorrespondentsTable.update({ CorrespondentsTable.id eq id }) {
            it[name] = request.name.trim()
            it[phone] = request.phone.trim()
            it[CorrespondentsTable.phoneE164] = phoneE164
            it[email] = request.email?.trim()?.lowercase()?.ifEmpty { null }
            it[userId] = request.userId
            it[responsibilities] = request.responsibilities?.trim()?.ifEmpty { null }
        }
        record(id, row[CorrespondentsTable.code], "customers.correspondent.updated", "Correspondent ${row[CorrespondentsTable.code]} updated", row[CorrespondentsTable.locationId])
        detail(id)
    }

    // ---- Lifecycle: UNASSIGNED/DE_ASSIGNED → ASSIGNED ⇄ (REASSIGNED) → DE_ASSIGNED ----

    fun assign(id: UUID, request: CorrespondentAssignRequest): CorrespondentDetail =
        transition(id, from = setOf("UNASSIGNED", "DE_ASSIGNED"), action = "ASSIGNED", request.region, request.locationId, request.effectiveFrom, request.note)

    fun reassign(id: UUID, request: CorrespondentAssignRequest): CorrespondentDetail =
        transition(id, from = setOf("ASSIGNED"), action = "REASSIGNED", request.region, request.locationId, request.effectiveFrom, request.note)

    /** Linked customers keep the link (history stays attributable) but the rep earns no new credit (rule C7). */
    fun deassign(id: UUID, request: CorrespondentDeassignRequest): CorrespondentDetail {
        val detail = transition(id, from = setOf("ASSIGNED"), action = "DE_ASSIGNED", null, null, request.effectiveFrom, request.note)
        val affected = tx.execute {
            CustomersTable.selectAll().where {
                (CustomersTable.tenantId eq support.tenantId()) and (CustomersTable.correspondentId eq id) and
                    (CustomersTable.status neq CustomerStatus.REMOVED.name)
            }.count()
        }
        return detail.copy(affectedCustomers = affected)
    }

    private fun transition(
        id: UUID,
        from: Set<String>,
        action: String,
        region: String?,
        locationId: UUID?,
        effectiveFrom: LocalDate?,
        note: String?,
    ): CorrespondentDetail = tx.execute {
        val row = row(id, forUpdate = true)
        val current = row[CorrespondentsTable.status]
        val target = if (action == "DE_ASSIGNED") "DE_ASSIGNED" else "ASSIGNED"
        if (current !in from) throw ApiErrors.invalidStatusTransition(current, action)
        if (action != "DE_ASSIGNED") validateAssignment(region!!, locationId!!)
        val effective = effectiveFrom ?: LocalDate.now(moduleSettings.zone())

        CorrespondentsTable.update({ CorrespondentsTable.id eq id }) {
            it[status] = target
            if (action == "DE_ASSIGNED") {
                it[CorrespondentsTable.region] = null
                it[CorrespondentsTable.locationId] = null
                it[assignedAt] = null
            } else {
                it[CorrespondentsTable.region] = region
                it[CorrespondentsTable.locationId] = locationId
                it[assignedAt] = effective
            }
        }
        val historyRegion = if (action == "DE_ASSIGNED") row[CorrespondentsTable.region] else region
        val historyLocation = if (action == "DE_ASSIGNED") row[CorrespondentsTable.locationId] else locationId
        history(id, action, historyRegion, historyLocation, effective, note?.trim()?.ifEmpty { null })

        val code = row[CorrespondentsTable.code]
        val (eventType, text) = when (action) {
            "ASSIGNED" -> "customers.correspondent.assigned" to "assigned to $region"
            "REASSIGNED" -> "customers.correspondent.reassigned" to "reassigned to $region"
            else -> "customers.correspondent.de-assigned" to "de-assigned"
        }
        record(id, code, eventType, "Correspondent $code $text", historyLocation)
        notifyLinkedUser(id, "You have been $text (effective $effective)")
        detail(id)
    }

    /** §4.10: link or unlink a customer's correspondent; the target must be ASSIGNED. */
    fun linkCustomer(customerId: UUID, request: CustomerCorrespondentRequest): CustomerDetail = tx.execute {
        val customer = support.row(customerId, forUpdate = true)
        customers.assertVisible(customer)
        if (customer[CustomersTable.status] == CustomerStatus.REMOVED.name) throw ApiErrors.customerNotActive(CustomerStatus.REMOVED.name)
        request.correspondentId?.let { support.requireAssignedCorrespondent(it) }
        val previous = customer[CustomersTable.correspondentId]
        if (previous != request.correspondentId) {
            CustomersTable.update({ CustomersTable.id eq customerId }) {
                it[correspondentId] = request.correspondentId
                it[updatedAt] = Instant.now()
            }
            val code = customer[CustomersTable.code]
            domainEvents.record(
                type = "customers.customer.correspondent-changed",
                aggregateType = "customer",
                aggregateId = customerId,
                aggregateRef = code,
                summary = if (request.correspondentId == null) "Correspondent unlinked from $code" else "Correspondent linked to $code",
                payload = mapOf("from" to previous, "to" to request.correspondentId),
                locationId = customer[CustomersTable.locationId],
            )
        }
        support.toDetail(support.row(customerId))
    }

    // ---- Reads ----

    /**
     * The register, one page at a time; filters, sorting and counts all in SQL (the counts are columns
     * kept by a trigger, V14_2). Single-location users see correspondents reporting to their location
     * plus unassigned ones (who report nowhere yet).
     */
    fun list(
        q: String?, statuses: Set<String>, regions: Set<String>, locationId: UUID?, userLinked: Boolean?, hasCustomers: Boolean?, sort: String?,
        paging: PageRequest,
    ): PageResult<CorrespondentSummary> = tx.execute {
        val parsed = searchSpec.parse(q)
        val parts = mutableListOf<Op<Boolean>>(CorrespondentsTable.tenantId eq support.tenantId())
        locationAccess.resolveFilter(locationId)?.let { scoped ->
            parts += if (locationId != null) CorrespondentsTable.locationId eq scoped
            else RawOp { +"("; +CorrespondentsTable.locationId; +" IS NULL OR "; +CorrespondentsTable.locationId; +" = "; registerArgument(org.jetbrains.exposed.sql.UUIDColumnType(), scoped); +")" }
        }
        if (statuses.isNotEmpty()) parts += CorrespondentsTable.status inList statuses.map { it.uppercase() }
        if (regions.isNotEmpty()) parts += CorrespondentsTable.region inList regions
        userLinked?.let { parts += if (it) RawOp { +CorrespondentsTable.userId; +" IS NOT NULL" } else RawOp { +CorrespondentsTable.userId; +" IS NULL" } }
        hasCustomers?.let { parts += if (it) CorrespondentsTable.customerCount greater 0 else CorrespondentsTable.customerCount eq 0 }
        searchSpec.condition(parsed)?.let { parts += it }
        val order: List<Pair<Expression<*>, SortOrder>> = when (sort?.substringBefore(',')) {
            null, "", "name" -> searchSpec.rank(parsed) + (CorrespondentsTable.name to SortOrder.ASC)
            "customerCount" -> listOf(CorrespondentsTable.customerCount to SortOrder.DESC, CorrespondentsTable.name to SortOrder.ASC)
            "dealerCount" -> listOf(CorrespondentsTable.dealerCount to SortOrder.DESC, CorrespondentsTable.name to SortOrder.ASC)
            "assignedAt" -> listOf(CorrespondentsTable.assignedAt to SortOrder.DESC_NULLS_LAST, CorrespondentsTable.name to SortOrder.ASC)
            else -> throw ApiErrors.invalidSortField(sort, listOf("name", "customerCount", "dealerCount", "assignedAt"))
        }
        val query = joined.selectAll().where(parts.allOf()!!).orderBy(*(order + (CorrespondentsTable.id to SortOrder.ASC)).toTypedArray())
        Paging.fetch(query, paging, ::toSummary)
    }

    fun getDetail(id: UUID): CorrespondentDetail = tx.execute { detail(id) }

    /** Picker: `{ id, code, name, region, status }` rows, assigned only by default. */
    fun lookup(q: String?, assignedOnly: Boolean): List<CorrespondentSummary> =
        list(q, if (assignedOnly) setOf("ASSIGNED") else emptySet(), emptySet(), null, null, null, null, PageRequest.of(0, PagingPolicy.LOOKUP_MAX)).items

    // ---- spi ----

    override fun get(id: UUID): CorrespondentRef = toRef(row(id))

    override fun names(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else CorrespondentsTable.select(CorrespondentsTable.id, CorrespondentsTable.name)
            .where { (CorrespondentsTable.tenantId eq tenantContext.requireTenantId()) and (CorrespondentsTable.id inList ids.toSet()) }
            .associate { it[CorrespondentsTable.id].value to it[CorrespondentsTable.name] }

    override fun creditableFor(customerId: UUID): CorrespondentRef? {
        val correspondentId = CustomersTable.select(CustomersTable.correspondentId)
            .where { (CustomersTable.id eq customerId) and (CustomersTable.tenantId eq tenantContext.requireTenantId()) }
            .firstOrNull()?.get(CustomersTable.correspondentId) ?: return null
        return toRef(row(correspondentId)).takeIf { it.status == "ASSIGNED" }
    }

    override fun listAssigned(locationId: UUID?): List<CorrespondentRef> {
        var condition = (CorrespondentsTable.tenantId eq tenantContext.requireTenantId()) and (CorrespondentsTable.status eq "ASSIGNED")
        locationId?.let { condition = condition and (CorrespondentsTable.locationId eq it) }
        return CorrespondentsTable.selectAll().where(condition).orderBy(CorrespondentsTable.name to SortOrder.ASC).map(::toRef)
    }

    // ---- Internals ----

    private fun validateAssignment(region: String, locationId: UUID) {
        val regions = moduleSettings.stringList(CustomersSettings.CORRESPONDENT_REGIONS)
        if (region !in regions) throw ApiErrors.invalidRegion(regions)
        support.validateLocation(locationId)
        locationAccess.assertAccess(locationId)
    }

    private fun validateUser(userId: UUID, existingId: UUID?) {
        support.validateMember(userId)
        val taken = !CorrespondentsTable.selectAll().where {
            (CorrespondentsTable.tenantId eq support.tenantId()) and (CorrespondentsTable.userId eq userId) and
                (if (existingId != null) CorrespondentsTable.id neq existingId else CorrespondentsTable.id.isNotNull())
        }.empty()
        if (taken) throw ApiErrors.userAlreadyLinked()
    }

    private fun row(id: UUID, forUpdate: Boolean = false): ResultRow {
        val query = CorrespondentsTable.selectAll()
            .where { (CorrespondentsTable.id eq id) and (CorrespondentsTable.tenantId eq tenantContext.requireTenantId()) }
        if (forUpdate) query.forUpdate()
        return query.firstOrNull() ?: throw ApiErrors.correspondentNotFound()
    }

    private fun detail(id: UUID): CorrespondentDetail {
        val row = joined.selectAll().where { (CorrespondentsTable.id eq id) and (CorrespondentsTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.correspondentNotFound()
        // The register's reach: a session confined to a location sees its correspondents and unassigned ones.
        locationAccess.resolveFilter(null)?.let { scoped ->
            if (row[CorrespondentsTable.locationId] != null && row[CorrespondentsTable.locationId] != scoped) throw ApiErrors.correspondentNotFound()
        }
        val history = CorrespondentAssignmentHistoryTable
            .join(LocationsTable, JoinType.LEFT, additionalConstraint = { RawOp { +CorrespondentAssignmentHistoryTable.locationId; +" = "; +LocationsTable.id } })
            .selectAll()
            .where { CorrespondentAssignmentHistoryTable.correspondentId eq id }
            .orderBy(CorrespondentAssignmentHistoryTable.createdAt to SortOrder.DESC)
            .map {
                CorrespondentHistoryRow(
                    action = it[CorrespondentAssignmentHistoryTable.action],
                    region = it[CorrespondentAssignmentHistoryTable.region],
                    location = ref(it[CorrespondentAssignmentHistoryTable.locationId], Ref::location),
                    effectiveFrom = it[CorrespondentAssignmentHistoryTable.effectiveFrom],
                    note = it[CorrespondentAssignmentHistoryTable.note],
                    performedBy = UserRef(it[CorrespondentAssignmentHistoryTable.performedByUserId]),
                    createdAt = it[CorrespondentAssignmentHistoryTable.createdAt],
                )
            }
        return CorrespondentDetail(toSummary(row), history)
    }

    private fun history(id: UUID, action: String, region: String?, locationId: UUID?, effectiveFrom: LocalDate, note: String?) {
        CorrespondentAssignmentHistoryTable.insert {
            it[tenantId] = support.tenantId()
            it[correspondentId] = id
            it[CorrespondentAssignmentHistoryTable.action] = action
            it[CorrespondentAssignmentHistoryTable.region] = region
            it[CorrespondentAssignmentHistoryTable.locationId] = locationId
            it[CorrespondentAssignmentHistoryTable.effectiveFrom] = effectiveFrom
            it[CorrespondentAssignmentHistoryTable.note] = note
            it[performedByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }
    }

    private fun record(id: UUID, code: String, type: String, summary: String, locationId: UUID?) {
        domainEvents.record(type = type, aggregateType = "correspondent", aggregateId = id, aggregateRef = code, summary = summary, locationId = locationId)
    }

    private fun notifyLinkedUser(id: UUID, body: String) {
        val row = row(id)
        val userId = row[CorrespondentsTable.userId] ?: return
        notify.inApp(
            userIds = listOf(userId),
            category = NotificationCategory.INFO,
            title = "Correspondent ${row[CorrespondentsTable.code]}",
            body = body,
            linkType = "correspondent",
            linkRef = id.toString(),
            exclude = support.userId(),
        )
    }

    private fun toSummary(row: ResultRow): CorrespondentSummary {
        return CorrespondentSummary(
            id = row[CorrespondentsTable.id].value,
            code = row[CorrespondentsTable.code],
            name = row[CorrespondentsTable.name],
            phone = row[CorrespondentsTable.phone],
            email = row[CorrespondentsTable.email],
            region = row[CorrespondentsTable.region],
            location = ref(row[CorrespondentsTable.locationId], Ref::location),
            status = row[CorrespondentsTable.status],
            assignedAt = row[CorrespondentsTable.assignedAt],
            customerCount = row[CorrespondentsTable.customerCount].toLong(),
            dealerCount = row[CorrespondentsTable.dealerCount].toLong(),
            responsibilities = row[CorrespondentsTable.responsibilities],
            userLinked = row[CorrespondentsTable.userId] != null,
        )
    }

    private fun toRef(row: ResultRow) = CorrespondentRef(
        id = row[CorrespondentsTable.id].value,
        code = row[CorrespondentsTable.code],
        name = row[CorrespondentsTable.name],
        phone = row[CorrespondentsTable.phone],
        email = row[CorrespondentsTable.email],
        region = row[CorrespondentsTable.region],
        locationId = row[CorrespondentsTable.locationId],
        status = row[CorrespondentsTable.status],
        userId = row[CorrespondentsTable.userId],
    )
}
