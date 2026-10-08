package com.zifiq.customers.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.search.RawOp
import com.zifiq.customers.dto.DealerCategoryRequest
import com.zifiq.customers.dto.DealerCategoryStatusRequest
import com.zifiq.customers.dto.DealerCategorySummary
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.model.DealerCategoriesTable
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.tenancy.TenantProvisioner
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Dealer categories (Platinum/Gold/Silver/Bronze by default) — docs/04 §4.9. */
@Service
class DealerCategoryService(
    private val support: CustomerSupport,
    private val tx: TenantScopedTransaction,
    private val domainEvents: DomainEvents,
) : TenantProvisioner {
    fun list(status: String?, q: String?): List<DealerCategorySummary> = tx.execute {
        val counts = dealerCounts()
        var condition = DealerCategoriesTable.tenantId eq support.tenantId()
        status?.let { condition = condition and (DealerCategoriesTable.status eq it.uppercase()) }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            condition = condition and RawOp {
                +"f_unaccent(lower("; +DealerCategoriesTable.name; +")) LIKE '%' || f_unaccent(lower("
                registerArgument(org.jetbrains.exposed.sql.TextColumnType(), com.zifiq.common.search.escapeLike(text)); +")) || '%'"
            }
        }
        DealerCategoriesTable.selectAll().where(condition)
            .orderBy(DealerCategoriesTable.sortOrder to SortOrder.DESC, DealerCategoriesTable.name to SortOrder.ASC)
            .map { toSummary(it, counts[it[DealerCategoriesTable.id].value] ?: 0) }
    }

    fun create(request: DealerCategoryRequest): DealerCategorySummary = tx.execute {
        val name = request.name.trim()
        ensureNameFree(name, null)
        val id = DealerCategoriesTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[DealerCategoriesTable.name] = name
            it[discountPct] = request.discountPct!!
            it[suggestedCreditLimit] = request.suggestedCreditLimit!!
            it[sortOrder] = request.sortOrder
            it[status] = "ACTIVE"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        record(id, "customers.dealer-category.created", "Dealer category $name created")
        get(id)
    }

    /** A new suggested limit applies to new/reclassified dealers only; existing limits stay as they are. */
    fun update(id: UUID, request: DealerCategoryRequest): DealerCategorySummary = tx.execute {
        row(id)
        val name = request.name.trim()
        ensureNameFree(name, id)
        DealerCategoriesTable.update({ (DealerCategoriesTable.id eq id) and (DealerCategoriesTable.tenantId eq support.tenantId()) }) {
            it[DealerCategoriesTable.name] = name
            it[discountPct] = request.discountPct!!
            it[suggestedCreditLimit] = request.suggestedCreditLimit!!
            it[sortOrder] = request.sortOrder
        }
        record(id, "customers.dealer-category.updated", "Dealer category $name updated")
        get(id)
    }

    fun changeStatus(id: UUID, request: DealerCategoryStatusRequest): DealerCategorySummary = tx.execute {
        val row = row(id)
        val to = request.status!!
        if (to == "INACTIVE") {
            val inUse = dealerCounts()[id] ?: 0
            if (inUse > 0) throw ApiErrors.categoryInUse(inUse)
        }
        DealerCategoriesTable.update({ DealerCategoriesTable.id eq id }) { it[status] = to }
        record(id, "customers.dealer-category.status-changed", "Dealer category ${row[DealerCategoriesTable.name]} ${to.lowercase()}")
        get(id)
    }

    /** Seeds the default categories for a new tenant (same set V8 gave existing tenants). */
    override fun provision(tenantId: UUID, createdByUserId: UUID) {
        val now = Instant.now()
        DealerCategoriesTable.batchInsert(DEFAULTS, shouldReturnGeneratedValues = false) { (name, discount, limit, order) ->
            this[DealerCategoriesTable.tenantId] = org.jetbrains.exposed.dao.id.EntityID(tenantId, TenantsTable)
            this[DealerCategoriesTable.name] = name
            this[DealerCategoriesTable.discountPct] = BigDecimal(discount)
            this[DealerCategoriesTable.suggestedCreditLimit] = BigDecimal(limit)
            this[DealerCategoriesTable.sortOrder] = order
            this[DealerCategoriesTable.status] = "ACTIVE"
            this[DealerCategoriesTable.createdByUserId] = createdByUserId
            this[DealerCategoriesTable.createdAt] = now
        }
    }

    private fun get(id: UUID) = toSummary(row(id), dealerCounts()[id] ?: 0)

    private fun row(id: UUID) = DealerCategoriesTable.selectAll()
        .where { (DealerCategoriesTable.id eq id) and (DealerCategoriesTable.tenantId eq support.tenantId()) }
        .firstOrNull() ?: throw ApiErrors.dealerCategoryNotFound()

    /** Live (not REMOVED, not SUSPENDED) dealers per category. */
    private fun dealerCounts(): Map<UUID, Long> {
        val count = CustomersTable.id.count()
        return CustomersTable.select(CustomersTable.dealerCategoryId, count)
            .where {
                (CustomersTable.tenantId eq support.tenantId()) and (CustomersTable.type eq CustomerType.AUTHORISED_DEALER.name) and
                    (CustomersTable.status neq CustomerStatus.REMOVED.name)
            }
            .groupBy(CustomersTable.dealerCategoryId)
            .mapNotNull { row -> row[CustomersTable.dealerCategoryId]?.let { it to row[count] } }
            .toMap()
    }

    private fun ensureNameFree(name: String, existingId: UUID?) {
        val clash = !DealerCategoriesTable.selectAll().where {
            (DealerCategoriesTable.tenantId eq support.tenantId()) and (DealerCategoriesTable.name.lowerCase() eq name.lowercase()) and
                (if (existingId != null) DealerCategoriesTable.id neq existingId else DealerCategoriesTable.id.isNotNull())
        }.empty()
        if (clash) throw ApiErrors.duplicateName(name)
    }

    private fun record(id: UUID, type: String, summary: String) {
        domainEvents.record(type = type, aggregateType = "dealer-category", aggregateId = id, aggregateRef = null, summary = summary)
    }

    private fun toSummary(row: org.jetbrains.exposed.sql.ResultRow, dealerCount: Long) = DealerCategorySummary(
        id = row[DealerCategoriesTable.id].value,
        name = row[DealerCategoriesTable.name],
        discountPct = row[DealerCategoriesTable.discountPct],
        suggestedCreditLimit = row[DealerCategoriesTable.suggestedCreditLimit],
        sortOrder = row[DealerCategoriesTable.sortOrder],
        status = row[DealerCategoriesTable.status],
        dealerCount = dealerCount,
    )

    private companion object {
        data class Default(val name: String, val discount: String, val limit: String, val order: Int)

        val DEFAULTS = listOf(
            Default("Platinum", "5.00", "3000000.00", 4),
            Default("Gold", "3.50", "1500000.00", 3),
            Default("Silver", "2.00", "500000.00", 2),
            Default("Bronze", "0.00", "200000.00", 1),
        )
    }
}
