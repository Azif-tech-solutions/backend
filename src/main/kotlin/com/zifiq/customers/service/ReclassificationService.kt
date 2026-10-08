package com.zifiq.customers.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.customers.dto.ReclassificationReason
import com.zifiq.customers.dto.ReclassificationRequest
import com.zifiq.customers.dto.ReclassificationSummary
import com.zifiq.customers.model.CorrespondentsTable
import com.zifiq.customers.model.CustomerReclassificationsTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.model.DealerCategoriesTable
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/** Promotion / demotion between customer types — docs/04 §4.4. */
@Service
class ReclassificationService(
    private val support: CustomerSupport,
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val database: Database,
    private val customers: CustomerService,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
    private val domainEvents: DomainEvents,
    private val notify: Notify,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val joined = CustomerReclassificationsTable
        .join(CustomersTable, JoinType.INNER, additionalConstraint = { RawOp { +CustomerReclassificationsTable.customerId; +" = "; +CustomersTable.id } })
        .join(DealerCategoriesTable, JoinType.LEFT, additionalConstraint = { RawOp { +CustomerReclassificationsTable.toDealerCategoryId; +" = "; +DealerCategoriesTable.id } })

    private val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("customerId", "Customer", FilterType.UUID, CustomerReclassificationsTable.customerId),
            FilterField("direction", "Direction", FilterType.ENUM, CustomerReclassificationsTable.direction, listOf(FilterOption("PROMOTION", "Promotion"), FilterOption("DEMOTION", "Demotion")), facetable = true),
            FilterField("fromType", "From type", FilterType.ENUM, CustomerReclassificationsTable.fromType, CustomerType.entries.map { FilterOption(it.name, it.label) }),
            FilterField("toType", "To type", FilterType.ENUM, CustomerReclassificationsTable.toType, CustomerType.entries.map { FilterOption(it.name, it.label) }),
            FilterField("reason", "Reason", FilterType.ENUM, CustomerReclassificationsTable.reason, ReclassificationReason.entries.map { FilterOption(it.name, it.name) }, facetable = true),
            FilterField("status", "Status", FilterType.ENUM, CustomerReclassificationsTable.status, listOf("SCHEDULED", "APPLIED", "CANCELLED").map { FilterOption(it, it.lowercase().replaceFirstChar(Char::titlecase)) }, facetable = true),
            FilterField("effectiveFrom", "Effective from", FilterType.DATE, CustomerReclassificationsTable.effectiveFrom, sortable = true),
            FilterField("authorisedBy", "Authorised by", FilterType.UUID, CustomerReclassificationsTable.authorisedByUserId),
            FilterField("createdAt", "Recorded", FilterType.DATETIME, CustomerReclassificationsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "effectiveFrom",
        defaultSort = listOf("effectiveFrom" to SortOrder.DESC),
        tiebreaker = CustomerReclassificationsTable.id,
    )

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(CustomersTable.code, SearchKind.REFERENCE, "Customer code"),
            SearchField(CustomersTable.name, SearchKind.TRIGRAM, "Customer name"),
            SearchField(CustomerReclassificationsTable.note, SearchKind.TRIGRAM, "Note"),
        ),
        qualifiers = filterSpec,
    )

    fun filters(): FilterMetadata = tx.execute { filterSpec.metadata(context()) }

    private fun context() = FilterContext(costVisible = true, zone = moduleSettings.zone())

    fun reclassify(customerId: UUID, request: ReclassificationRequest): ReclassificationSummary = tx.execute {
        val customer = support.row(customerId, forUpdate = true)
        customers.assertVisible(customer)
        if (customer[CustomersTable.isTemporary]) throw ApiErrors.customerIsTemporary()
        val status = CustomerStatus.valueOf(customer[CustomersTable.status])
        if (status == CustomerStatus.REMOVED) throw ApiErrors.customerNotActive(status.name)

        val fromType = CustomerType.valueOf(customer[CustomersTable.type])
        val toType = request.toType!!
        if (fromType == toType) throw ApiErrors.reclassificationSameType()
        val today = LocalDate.now(moduleSettings.zone())
        val effectiveFrom = request.effectiveFrom!!
        if (effectiveFrom.isBefore(today)) throw ApiErrors.validationFailed(mapOf("effectiveFrom" to "must be today or later"))
        val pending = !CustomerReclassificationsTable.selectAll()
            .where { (CustomerReclassificationsTable.customerId eq customerId) and (CustomerReclassificationsTable.status eq "SCHEDULED") }
            .empty()
        if (pending) throw ApiErrors.reclassificationPending()
        checkDeposit(fromType, toType, customer)

        val toCategory = if (toType == CustomerType.AUTHORISED_DEALER) {
            support.activeCategory(request.toDealerCategoryId ?: throw ApiErrors.categoryRequiredForDealer())
        } else {
            if (request.toDealerCategoryId != null) throw ApiErrors.categoryRequiredForDealer()
            null
        }
        if (toType == CustomerType.AUTHORISED_DEALER) ensureDealerNameFree(customer)

        val fromLimit = customer[CustomersTable.creditLimit]
        val toLimit = when {
            toType == CustomerType.NO_CREDIT_WALKIN -> {
                if (request.toCreditLimit != null && request.toCreditLimit.signum() != 0) throw ApiErrors.creditLimitNotAllowed()
                BigDecimal.ZERO.setScale(2)
            }
            request.toCreditLimit != null -> request.toCreditLimit
            toCategory != null -> toCategory[DealerCategoriesTable.suggestedCreditLimit]
            else -> fromLimit
        }
        if (toLimit.compareTo(fromLimit) != 0) support.require("customers.dealers.manage")

        val now = Instant.now()
        val id = CustomerReclassificationsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[CustomerReclassificationsTable.customerId] = customerId
            it[CustomerReclassificationsTable.fromType] = fromType.name
            it[CustomerReclassificationsTable.toType] = toType.name
            it[direction] = if (toType.rank > fromType.rank) "PROMOTION" else "DEMOTION"
            it[reason] = request.reason!!.name
            it[note] = request.note?.trim()?.ifEmpty { null }
            it[fromDealerCategoryId] = customer[CustomersTable.dealerCategoryId]
            it[toDealerCategoryId] = toCategory?.get(DealerCategoriesTable.id)?.value
            it[fromCreditLimit] = fromLimit
            it[toCreditLimit] = toLimit
            it[CustomerReclassificationsTable.effectiveFrom] = effectiveFrom
            it[CustomerReclassificationsTable.status] = "SCHEDULED"
            it[authorisedByUserId] = support.userId()
            it[createdAt] = now
        }.value

        if (effectiveFrom == today) {
            apply(id)
        } else {
            domainEvents.record(
                type = "customers.reclassification.scheduled",
                aggregateType = "customer",
                aggregateId = customerId,
                aggregateRef = customer[CustomersTable.code],
                summary = "${customer[CustomersTable.code]} ${fromType.label} → ${toType.label} scheduled for $effectiveFrom",
                payload = mapOf("reclassificationId" to id, "fromType" to fromType.name, "toType" to toType.name, "effectiveFrom" to effectiveFrom.toString()),
                locationId = customer[CustomersTable.locationId],
            )
        }
        summary(id)
    }

    fun cancel(customerId: UUID, reclassificationId: UUID): ReclassificationSummary = tx.execute {
        customers.assertVisible(support.row(customerId))
        val row = CustomerReclassificationsTable.selectAll()
            .where {
                (CustomerReclassificationsTable.id eq reclassificationId) and (CustomerReclassificationsTable.customerId eq customerId) and
                    (CustomerReclassificationsTable.tenantId eq support.tenantId())
            }
            .forUpdate()
            .firstOrNull() ?: throw ApiErrors.reclassificationNotFound()
        val status = row[CustomerReclassificationsTable.status]
        if (status != "SCHEDULED") throw ApiErrors.invalidStatusTransition(status, "CANCELLED")
        CustomerReclassificationsTable.update({ CustomerReclassificationsTable.id eq reclassificationId }) { it[CustomerReclassificationsTable.status] = "CANCELLED" }
        val summary = summary(reclassificationId)
        domainEvents.record(
            type = "customers.reclassification.cancelled",
            aggregateType = "customer",
            aggregateId = customerId,
            aggregateRef = summary.customerCode,
            summary = "Scheduled reclassification of ${summary.customerCode} to ${summary.toType.label} cancelled",
            payload = mapOf("reclassificationId" to reclassificationId),
        )
        summary
    }

    fun list(request: ListSearchRequest): PageResult<ReclassificationSummary> = tx.execute {
        val context = context()
        val parsed = searchSpec.parse(request.q)
        val parts = listOfNotNull(
            CustomerReclassificationsTable.tenantId eq support.tenantId(),
            locationScope(),
            searchSpec.condition(parsed),
            filterSpec.fromParams(request.params, context),
            filterSpec.fromJson(request.filter, context),
        )
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) searchSpec.rank(parsed) + filterSpec.sort(emptyList(), context)
        else filterSpec.sort(request.sortParams, context)
        val query = joined.selectAll().where(parts.allOf()!!).orderBy(*sort.toTypedArray())
        Paging.fetch(query, PageRequest.of(request.page, request.size), ::toSummary)
            .copy(meta = com.zifiq.common.paging.PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    /** Single-location users see reclassifications of customers homed at their location (rule C11). */
    private fun locationScope(): Op<Boolean>? = locationAccess.resolveFilter(null)?.let { CustomersTable.locationId eq it }

    /**
     * Applies due scheduled reclassifications. Runs hourly so "00:05 in the tenant's timezone" holds
     * for every timezone; each one is applied in its own transaction, so one failure (say a deposit
     * not yet settled) doesn't hold up the rest — it stays SCHEDULED and is retried next run.
     */
    @Scheduled(cron = "\${zifiq.customers.reclassification-cron:0 5 * * * *}")
    fun applyDue() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId -> applyDueFor(tenantId) }
    }

    /** Visible for tests. */
    fun applyDueFor(tenantId: UUID) {
        val due = tx.executeForTenant(tenantId) {
            val today = LocalDate.now(moduleSettings.zone())
            CustomerReclassificationsTable.select(CustomerReclassificationsTable.id)
                .where {
                    (CustomerReclassificationsTable.tenantId eq tenantId) and (CustomerReclassificationsTable.status eq "SCHEDULED") and
                        (CustomerReclassificationsTable.effectiveFrom lessEq today)
                }
                .map { it[CustomerReclassificationsTable.id].value }
        }
        due.forEach { id ->
            runCatching { tx.executeForTenant(tenantId) { apply(id) } }
                .onFailure { logger.warn("Reclassification {} for tenant {} could not be applied yet: {}", id, tenantId, it.message) }
        }
    }

    /** Applies one reclassification in the current transaction. The wallet always carries over (rule C5). */
    private fun apply(id: UUID) {
        val tenantId = tenantContext.requireTenantId()
        val reclass = CustomerReclassificationsTable.selectAll()
            .where { (CustomerReclassificationsTable.id eq id) and (CustomerReclassificationsTable.tenantId eq tenantId) }
            .forUpdate()
            .single()
        if (reclass[CustomerReclassificationsTable.status] != "SCHEDULED") return
        val customerId = reclass[CustomerReclassificationsTable.customerId].value
        val customer = CustomersTable.selectAll()
            .where { (CustomersTable.id eq customerId) and (CustomersTable.tenantId eq tenantId) }
            .forUpdate()
            .single()
        val fromType = CustomerType.valueOf(customer[CustomersTable.type])
        val toType = CustomerType.valueOf(reclass[CustomerReclassificationsTable.toType])
        // Re-checked at apply time: the deposit may have changed since it was scheduled.
        checkDeposit(fromType, toType, customer)

        val now = Instant.now()
        CustomersTable.update({ CustomersTable.id eq customerId }) {
            it[type] = toType.name
            it[dealerCategoryId] = if (toType == CustomerType.AUTHORISED_DEALER) reclass[CustomerReclassificationsTable.toDealerCategoryId] else null
            it[creditLimit] = reclass[CustomerReclassificationsTable.toCreditLimit]
            it[updatedAt] = now
        }
        CustomerReclassificationsTable.update({ CustomerReclassificationsTable.id eq id }) {
            it[status] = "APPLIED"
            it[appliedAt] = now
        }

        val code = customer[CustomersTable.code]
        val direction = reclass[CustomerReclassificationsTable.direction]
        val summary = "$code ${direction.lowercase()}: ${fromType.label} → ${toType.label}"
        domainEvents.record(
            type = "customers.customer.reclassified",
            aggregateType = "customer",
            aggregateId = customerId,
            aggregateRef = code,
            summary = summary,
            payload = mapOf(
                "reclassificationId" to id, "fromType" to fromType.name, "toType" to toType.name, "direction" to direction,
                "walletBalanceCarriedOver" to customer[CustomersTable.walletBalance],
            ),
            locationId = customer[CustomersTable.locationId],
        )
        val correspondentUser = customer[CustomersTable.correspondentId]?.let { correspondentId ->
            CorrespondentsTable.select(CorrespondentsTable.userId).where { CorrespondentsTable.id eq correspondentId }
                .firstOrNull()?.get(CorrespondentsTable.userId)
        }
        if (correspondentUser != null) {
            notify.inApp(
                userIds = listOf(correspondentUser),
                category = NotificationCategory.INFO,
                title = "Customer reclassified — $code",
                body = "${customer[CustomersTable.name]} ($code) is now ${toType.label}",
                linkType = "customer",
                linkRef = customerId.toString(),
            )
        }
    }

    /** A deposit only exists on Special Customers; leaving that type needs it settled first. */
    private fun checkDeposit(fromType: CustomerType, toType: CustomerType, customer: ResultRow) {
        if (fromType == CustomerType.SPECIAL_CUSTOMER && toType != CustomerType.SPECIAL_CUSTOMER && customer[CustomersTable.depositBalance].signum() > 0) {
            throw ApiErrors.depositMustBeSettled()
        }
    }

    private fun ensureDealerNameFree(customer: ResultRow) {
        val name = customer[CustomersTable.name]
        val clash = !CustomersTable.selectAll().where {
            (CustomersTable.tenantId eq customer[CustomersTable.tenantId]) and (CustomersTable.type eq CustomerType.AUTHORISED_DEALER.name) and
                (CustomersTable.status neq CustomerStatus.REMOVED.name) and (CustomersTable.name.lowerCase() eq name.lowercase()) and
                (CustomersTable.id neq customer[CustomersTable.id].value)
        }.empty()
        if (clash) throw ApiErrors.duplicateName(name)
    }

    private fun summary(id: UUID): ReclassificationSummary =
        toSummary(joined.selectAll().where { CustomerReclassificationsTable.id eq id }.single())

    private fun toSummary(row: ResultRow) = ReclassificationSummary(
        id = row[CustomerReclassificationsTable.id].value,
        customerId = row[CustomerReclassificationsTable.customerId].value,
        customerCode = row[CustomersTable.code],
        customerName = row[CustomersTable.name],
        fromType = CustomerType.valueOf(row[CustomerReclassificationsTable.fromType]),
        toType = CustomerType.valueOf(row[CustomerReclassificationsTable.toType]),
        direction = row[CustomerReclassificationsTable.direction],
        reason = row[CustomerReclassificationsTable.reason],
        note = row[CustomerReclassificationsTable.note],
        effectiveFrom = row[CustomerReclassificationsTable.effectiveFrom],
        status = row[CustomerReclassificationsTable.status],
        fromCreditLimit = row[CustomerReclassificationsTable.fromCreditLimit],
        toCreditLimit = row[CustomerReclassificationsTable.toCreditLimit],
        toDealerCategoryName = row.getOrNull(DealerCategoriesTable.name),
        walletBalanceCarriedOver = row[CustomersTable.walletBalance],
        authorisedByUserId = row[CustomerReclassificationsTable.authorisedByUserId],
        appliedAt = row[CustomerReclassificationsTable.appliedAt],
        createdAt = row[CustomerReclassificationsTable.createdAt],
    )
}
