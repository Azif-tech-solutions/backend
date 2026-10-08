package com.zifiq.inventory.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.phone.PhoneNumbers
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.inventory.dto.DriverRequest
import com.zifiq.inventory.dto.DriverSummary
import com.zifiq.inventory.model.DriversTable
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Drivers register (docs/05 §4.13): the driver and vehicle named on waybills. */
@Service
class DriverService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val domainEvents: DomainEvents,
) {
    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(DriversTable.name, SearchKind.TRIGRAM, "Name"),
            SearchField(DriversTable.vehicleNumber, SearchKind.CODE, "Vehicle"),
            SearchField(DriversTable.phoneE164, SearchKind.PHONE, "Phone"),
            SearchField(DriversTable.licenceNumber, SearchKind.CODE, "Licence"),
        ),
    )

    /** Unpaged: a company has tens of drivers, not thousands. */
    fun list(q: String?, status: String?, limit: Int = 500): List<DriverSummary> = tx.execute {
        val parsed = searchSpec.parse(q)
        val parts = listOfNotNull<Op<Boolean>>(
            DriversTable.tenantId eq tenantContext.requireTenantId(),
            status?.let { DriversTable.status eq it.uppercase() },
            searchSpec.condition(parsed),
        )
        DriversTable.selectAll().where(parts.allOf()!!)
            .orderBy(*(searchSpec.rank(parsed) + (DriversTable.name to SortOrder.ASC) + (DriversTable.id to SortOrder.ASC)).toTypedArray())
            .limit(limit)
            .map(::toSummary)
    }

    fun create(request: DriverRequest): DriverSummary = tx.execute {
        val name = request.name.trim()
        val vehicle = request.vehicleNumber?.trim()?.uppercase()?.ifEmpty { null }
        ensureUnique(name, vehicle, null)
        val id = DriversTable.insertAndGetId {
            it[tenantId] = tenantContext.requireTenantId()
            it[DriversTable.name] = name
            it[phone] = request.phone?.trim()?.ifEmpty { null }
            it[phoneE164] = PhoneNumbers.normalise(request.phone)
            it[vehicleNumber] = vehicle
            it[licenceNumber] = request.licenceNumber?.trim()?.ifEmpty { null }
            it[status] = "ACTIVE"
            it[createdByUserId] = currentActorProvider.current().userId
            it[createdAt] = Instant.now()
        }.value
        record(id, "inventory.driver.registered", "Driver registered — $name${vehicle?.let { " ($it)" } ?: ""}")
        get(id)
    }

    fun update(id: UUID, request: DriverRequest): DriverSummary = tx.execute {
        row(id)
        val name = request.name.trim()
        val vehicle = request.vehicleNumber?.trim()?.uppercase()?.ifEmpty { null }
        ensureUnique(name, vehicle, id)
        DriversTable.update({ DriversTable.id eq id }) {
            it[DriversTable.name] = name
            it[phone] = request.phone?.trim()?.ifEmpty { null }
            it[phoneE164] = PhoneNumbers.normalise(request.phone)
            it[vehicleNumber] = vehicle
            it[licenceNumber] = request.licenceNumber?.trim()?.ifEmpty { null }
        }
        record(id, "inventory.driver.updated", "Driver updated — $name")
        get(id)
    }

    fun changeStatus(id: UUID, status: String): DriverSummary = tx.execute {
        val row = row(id)
        DriversTable.update({ DriversTable.id eq id }) { it[DriversTable.status] = status }
        record(id, "inventory.driver.status-changed", "Driver ${row[DriversTable.name]} ${status.lowercase()}")
        get(id)
    }

    /** For dispatch: an ACTIVE driver of this tenant. Runs in the caller's transaction. */
    fun activeDriver(id: UUID): DriverSummary {
        val driver = toSummary(row(id))
        if (driver.status != "ACTIVE") throw ApiErrors.validationFailed(mapOf("driverId" to "This driver is inactive"))
        return driver
    }

    private fun get(id: UUID) = toSummary(row(id))

    private fun row(id: UUID): ResultRow =
        DriversTable.selectAll().where { (DriversTable.id eq id) and (DriversTable.tenantId eq tenantContext.requireTenantId()) }
            .firstOrNull() ?: throw ApiErrors.driverNotFound()

    /** Same name and vehicle is the same driver (the unique index backs this). */
    private fun ensureUnique(name: String, vehicle: String?, existingId: UUID?) {
        val clash = DriversTable.selectAll().where {
            var condition = (DriversTable.tenantId eq tenantContext.requireTenantId()) and (DriversTable.name.lowerCase() eq name.lowercase())
            condition = condition and (if (vehicle == null) DriversTable.vehicleNumber.isNull() else DriversTable.vehicleNumber.lowerCase() eq vehicle.lowercase())
            if (existingId != null) condition = condition and (DriversTable.id neq existingId)
            condition
        }.empty().not()
        if (clash) throw ApiErrors.duplicateName(listOfNotNull(name, vehicle).joinToString(" / "))
    }

    private fun record(id: UUID, type: String, summary: String) {
        domainEvents.record(type = type, aggregateType = "driver", aggregateId = id, aggregateRef = null, summary = summary)
    }

    private fun toSummary(row: ResultRow) = DriverSummary(
        id = row[DriversTable.id].value,
        name = row[DriversTable.name],
        phone = row[DriversTable.phone],
        vehicleNumber = row[DriversTable.vehicleNumber],
        licenceNumber = row[DriversTable.licenceNumber],
        status = row[DriversTable.status],
        createdAt = row[DriversTable.createdAt],
    )
}
