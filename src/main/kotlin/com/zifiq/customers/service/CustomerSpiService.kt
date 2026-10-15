package com.zifiq.customers.service

import com.zifiq.customers.spi.LedgerTotal
import com.zifiq.common.sql.Aggregates
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.phone.PhoneNumbers
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.allOf
import com.zifiq.customers.model.CustomerLedgerEntriesTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.CustomerActivity
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerQueries
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerDirection
import com.zifiq.customers.spi.LedgerEntriesFilter
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerEntryView
import com.zifiq.customers.spi.TemporaryCustomers
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * The Customers spi (docs/04 §5) other modules call. Everything runs in the caller's transaction
 * and is deliberately not location-restricted: a customer homed at one showroom can buy, return
 * goods or be invoiced at any other.
 */
@Service
class CustomerSpiService(
    private val support: CustomerSupport,
    private val tenantContext: TenantContext,
    private val listService: CustomerListService,
    private val sequences: DocumentSequenceService,
    private val moduleSettings: ModuleSettings,
    private val domainEvents: DomainEvents,
) : CustomerDirectory, TemporaryCustomers, CustomerActivity, CustomerQueries {

    // ---- CustomerDirectory ----

    override fun get(id: UUID): CustomerRef = support.toRef(support.row(id))

    override fun getForUpdate(id: UUID): CustomerRef = support.toRef(support.row(id, forUpdate = true))

    override fun findActiveByPhone(phone: String): CustomerRef? {
        val normalised = PhoneNumbers.normalise(phone) ?: return null
        return support.query(
            (CustomersTable.tenantId eq tenantContext.requireTenantId()) and (CustomersTable.phoneE164 eq normalised) and
                RawOp { +CustomersTable.status; +" <> 'REMOVED'" },
        )
            // Registered records win over temporary ones, then the longest-standing.
            .orderBy(CustomersTable.isTemporary to SortOrder.ASC, CustomersTable.createdAt to SortOrder.ASC)
            .firstOrNull()?.let(support::toRef)
    }

    override fun search(q: String, limit: Int, types: Set<CustomerType>?): List<CustomerRef> = listService.pickerRefs(q, types, limit)

    // ---- TemporaryCustomers (PRD rule 10) ----

    override fun findOrCreate(name: String, phone: String, locationId: UUID): CustomerRef {
        val normalised = PhoneNumbers.normalise(phone) ?: throw ApiErrors.phoneRequired()
        if (moduleSettings.boolean(CustomersSettings.TEMPORARY_MATCH_BY_PHONE)) {
            val match = support.query(
                (CustomersTable.tenantId eq tenantContext.requireTenantId()) and (CustomersTable.phoneE164 eq normalised) and
                    RawOp {
                        +"(("; +CustomersTable.isTemporary; +" = false AND "; +CustomersTable.type; +" <> 'AUTHORISED_DEALER' AND "
                        +CustomersTable.status; +" <> 'REMOVED') OR ("; +CustomersTable.isTemporary; +" = true AND "; +CustomersTable.status; +" = 'ACTIVE'))"
                    },
            ).orderBy(CustomersTable.isTemporary to SortOrder.ASC, CustomersTable.createdAt to SortOrder.ASC).firstOrNull()
            if (match != null) return support.toRef(match)
        }
        support.validateLocation(locationId)

        val code = sequences.next("CU")
        val now = Instant.now()
        val id = CustomersTable.insertAndGetId {
            it[tenantId] = tenantContext.requireTenantId()
            it[CustomersTable.code] = code
            it[type] = CustomerType.NO_CREDIT_WALKIN.name
            it[isTemporary] = true
            it[CustomersTable.name] = name.trim().ifEmpty { "Walk-in customer" }.take(200)
            it[CustomersTable.phone] = phone.trim().take(40)
            it[phoneE164] = normalised
            it[countryCode] = "NG"
            it[CustomersTable.locationId] = locationId
            it[creditLimit] = BigDecimal.ZERO
            it[customerSince] = LocalDate.now(moduleSettings.zone())
            it[status] = CustomerStatus.ACTIVE.name
            it[customAttributes] = "{}"
            it[createdByUserId] = support.userId()
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        domainEvents.record(
            type = "customers.customer.temporary-created",
            aggregateType = "customer",
            aggregateId = id,
            aggregateRef = code,
            summary = "Temporary customer $code created for a walk-in return — ${name.trim()}",
            locationId = locationId,
        )
        return get(id)
    }

    // ---- CustomerActivity ----

    override fun recordSale(customerId: UUID, amount: BigDecimal, at: Instant) {
        val row = support.row(customerId, forUpdate = true)
        val last = row[CustomersTable.lastPurchaseAt]
        CustomersTable.update({ CustomersTable.id eq customerId }) {
            it[lifetimeValue] = row[CustomersTable.lifetimeValue] + amount
            it[lastPurchaseAt] = if (last == null || at.isAfter(last)) at else last
        }
    }

    override fun reverseSale(customerId: UUID, amount: BigDecimal) {
        val row = support.row(customerId, forUpdate = true)
        // Never below zero, even if a sale recorded before this module existed is voided.
        CustomersTable.update({ CustomersTable.id eq customerId }) {
            it[lifetimeValue] = (row[CustomersTable.lifetimeValue] - amount).max(BigDecimal.ZERO)
        }
    }

    // ---- CustomerQueries ----

    override fun byIds(ids: Collection<UUID>): List<CustomerRef> {
        if (ids.isEmpty()) return emptyList()
        return support.query((CustomersTable.tenantId eq tenantContext.requireTenantId()) and (CustomersTable.id inList ids)).map(support::toRef)
    }

    private fun ledgerCondition(filter: LedgerEntriesFilter): Op<Boolean> {
        val parts = mutableListOf<Op<Boolean>>(CustomerLedgerEntriesTable.tenantId eq tenantContext.requireTenantId())
        filter.customerIds?.let { parts += CustomerLedgerEntriesTable.customerId inList it }
        filter.entryTypes?.let { types -> parts += CustomerLedgerEntriesTable.entryType inList types.map { it.name } }
        filter.sourceModule?.let { parts += CustomerLedgerEntriesTable.sourceModule eq it }
        filter.from?.let { parts += CustomerLedgerEntriesTable.entryDate greaterEq it }
        filter.to?.let { parts += CustomerLedgerEntriesTable.entryDate lessEq it }
        filter.customerTypes?.let { types -> parts += CustomersTable.type inList types.map { it.name } }
        return parts.allOf()!!
    }

    private fun ledgerJoin() = CustomerLedgerEntriesTable
        .join(CustomersTable, JoinType.INNER, additionalConstraint = { RawOp { +CustomerLedgerEntriesTable.customerId; +" = "; +CustomersTable.id } })

    override fun ledgerTotal(filter: LedgerEntriesFilter): LedgerTotal {
        val count = Aggregates.count()
        val amount = Aggregates.sum(CustomerLedgerEntriesTable.amount)
        val row = ledgerJoin().select(count, amount).where(ledgerCondition(filter)).single()
        return LedgerTotal(Aggregates.long(row, count), Aggregates.money(row, amount))
    }

    override fun ledgerEntries(filter: LedgerEntriesFilter): List<LedgerEntryView> {
        val order = if (filter.newestFirst) SortOrder.DESC else SortOrder.ASC
        val orderBy = if (filter.newestFirst) arrayOf(CustomerLedgerEntriesTable.entryDate to order, CustomerLedgerEntriesTable.createdAt to order, CustomerLedgerEntriesTable.id to order)
        else arrayOf(CustomerLedgerEntriesTable.createdAt to order, CustomerLedgerEntriesTable.id to order)
        return ledgerJoin()
            .selectAll()
            .where(ledgerCondition(filter))
            .orderBy(*orderBy)
            .limit(minOf(filter.limit ?: MAX_REPORT_ROWS, MAX_REPORT_ROWS))
            .map { row ->
                val type = LedgerEntryType.valueOf(row[CustomerLedgerEntriesTable.entryType])
                LedgerEntryView(
                    id = row[CustomerLedgerEntriesTable.id].value,
                    customerId = row[CustomerLedgerEntriesTable.customerId].value,
                    customerCode = row[CustomersTable.code],
                    customerName = row[CustomersTable.name],
                    balanceType = BalanceType.valueOf(row[CustomerLedgerEntriesTable.balanceType]),
                    entryType = type,
                    direction = LedgerDirection.valueOf(row[CustomerLedgerEntriesTable.direction]),
                    amount = row[CustomerLedgerEntriesTable.amount],
                    balanceAfter = row[CustomerLedgerEntriesTable.balanceAfter],
                    reference = row[CustomerLedgerEntriesTable.reference],
                    description = row[CustomerLedgerEntriesTable.description],
                    paymentMethod = row[CustomerLedgerEntriesTable.paymentMethod],
                    sourceModule = row[CustomerLedgerEntriesTable.sourceModule],
                    sourceType = row[CustomerLedgerEntriesTable.sourceType],
                    sourceId = row[CustomerLedgerEntriesTable.sourceId],
                    locationId = row[CustomerLedgerEntriesTable.locationId],
                    performedByUserId = row[CustomerLedgerEntriesTable.performedByUserId],
                    entryDate = row[CustomerLedgerEntriesTable.entryDate],
                    createdAt = row[CustomerLedgerEntriesTable.createdAt],
                )
            }
    }

    private companion object {
        /** Report callers page by date range; this is a safety cap, not a page size. */
        const val MAX_REPORT_ROWS = 50_000
    }
}
