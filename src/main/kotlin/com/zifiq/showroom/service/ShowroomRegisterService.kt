package com.zifiq.showroom.service

import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.security.AccessCaches
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.common.tax.NigeriaStatesTable
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import com.zifiq.showroom.dto.AreaRequest
import com.zifiq.showroom.dto.AreaView
import com.zifiq.showroom.dto.ShowroomDefaultsRequest
import com.zifiq.showroom.dto.ShowroomDetail
import com.zifiq.showroom.dto.ShowroomList
import com.zifiq.showroom.dto.ShowroomLookupRow
import com.zifiq.showroom.dto.ShowroomRequest
import com.zifiq.showroom.dto.ShowroomRow
import com.zifiq.showroom.dto.ShowroomStatusRequest
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Sum
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Showroom register and storage areas (docs/05 §4.1). A showroom is a SHOWROOM location; its areas
 * (Shop Floor, Warehouse, …) are STORAGE_AREA children, so stock is tracked per area by the shared
 * inventory ledger with no change.
 */
@Service
class ShowroomRegisterService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
    private val sequences: DocumentSequenceService,
    private val domainEvents: DomainEvents,
) {
    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(LocationsTable.code, SearchKind.REFERENCE, "Code"),
            SearchField(LocationsTable.name, SearchKind.TRIGRAM, "Name"),
            SearchField(LocationsTable.city, SearchKind.TRIGRAM, "City"),
            SearchField(LocationsTable.managerName, SearchKind.TRIGRAM, "Manager"),
        ),
    )

    // ---- Register ----

    fun create(request: ShowroomRequest): ShowroomDetail = tx.execute {
        val name = request.name.trim()
        ensureNameFree(name, null)
        validate(request)
        val code = sequences.next("SHR")
        val now = Instant.now()
        val id = LocationsTable.insertAndGetId {
            it[tenantId] = tenantContext.requireTenantId()
            it[type] = "SHOWROOM"
            it[LocationsTable.code] = code
            it[status] = if (request.active) "ACTIVE" else "PENDING"
            it[createdByUserId] = currentActorProvider.current().userId
            it[createdAt] = now
            write(it, request)
        }.value

        if (request.createDefaultAreas) {
            val kinds = moduleSettings.stringList(ShowroomSettings.DEFAULT_AREAS)
            val areas = kinds.associateWith { kind -> insertArea(id, code, AREA_NAMES.getValue(kind), kind) }
            LocationsTable.update({ LocationsTable.id eq id }) {
                it[defaultReceivingLocationId] = areas["WAREHOUSE"] ?: areas["RECEIVING"]
                it[defaultSellingLocationId] = areas["SHOP_FLOOR"]
            }
        }
        domainEvents.record(
            type = "showroom.showroom.registered", aggregateType = "showroom", aggregateId = id, aggregateRef = code,
            summary = "Showroom $code registered — $name${if (request.active) "" else " (pending opening)"}", locationId = id,
        )
        detail(id)
    }

    fun update(id: UUID, request: ShowroomRequest): ShowroomDetail = tx.execute {
        val row = showroomRow(id, forWrite = true)
        val name = request.name.trim()
        ensureNameFree(name, id)
        validate(request)
        LocationsTable.update({ LocationsTable.id eq id }) { write(it, request) }
        domainEvents.record(
            type = "showroom.showroom.updated", aggregateType = "showroom", aggregateId = id, aggregateRef = row[LocationsTable.code],
            summary = "Showroom ${row[LocationsTable.code]} updated", locationId = id,
        )
        detail(id)
    }

    /** PENDING → ACTIVE is "open for trading". Suspending a stocked showroom is allowed, with a warning. */
    fun changeStatus(id: UUID, request: ShowroomStatusRequest): ShowroomDetail = tx.execute {
        val row = showroomRow(id, forWrite = true)
        val from = row[LocationsTable.status]
        val to = request.status!!
        if (from == to) throw ApiErrors.invalidStatusTransition(from, to)
        LocationsTable.update({ LocationsTable.id eq id }) { it[status] = to }
        val code = row[LocationsTable.code]
        domainEvents.record(
            type = "showroom.showroom.status-changed", aggregateType = "showroom", aggregateId = id, aggregateRef = code,
            summary = "Showroom $code ${from.lowercase()} → ${to.lowercase()}" + (request.reason?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""),
            payload = mapOf("from" to from, "to" to to), locationId = id,
        )
        val units = unitsOnHand(withAreas(id))
        val warnings = if (to == "SUSPENDED" && units.signum() > 0) listOf("Showroom holds ${format(units)} units; no stock can move while it is suspended") else emptyList()
        detail(id).copy(warnings = warnings)
    }

    // ---- Areas ----

    fun createArea(showroomId: UUID, request: AreaRequest): ShowroomDetail = tx.execute {
        val showroom = showroomRow(showroomId, forWrite = true)
        val areaId = insertArea(showroomId, showroom[LocationsTable.code], request.name.trim(), request.kind!!)
        domainEvents.record(
            type = "showroom.area.created", aggregateType = "storage-area", aggregateId = areaId, aggregateRef = null,
            summary = "Area ${request.name.trim()} added to ${showroom[LocationsTable.name]}", locationId = showroomId,
        )
        detail(showroomId)
    }

    fun updateArea(areaId: UUID, request: AreaRequest): ShowroomDetail = tx.execute {
        val area = areaRow(areaId)
        val showroomId = area[LocationsTable.parentLocationId]!!
        showroomRow(showroomId, forWrite = true)
        if (request.kind == "QUARANTINE" && areaId == showroomRow(showroomId)[LocationsTable.defaultSellingLocationId]) {
            throw ApiErrors.invalidLocation("The selling area can't be a quarantine area")
        }
        LocationsTable.update({ LocationsTable.id eq areaId }) {
            it[name] = request.name.trim()
            it[areaKind] = request.kind
        }
        domainEvents.record(type = "showroom.area.updated", aggregateType = "storage-area", aggregateId = areaId, aggregateRef = area[LocationsTable.code],
            summary = "Area ${area[LocationsTable.code]} updated", locationId = showroomId)
        detail(showroomId)
    }

    /** Deactivating an area needs it empty (move the stock first) and not used as a default. */
    fun changeAreaStatus(areaId: UUID, status: String): ShowroomDetail = tx.execute {
        val area = areaRow(areaId)
        val showroomId = area[LocationsTable.parentLocationId]!!
        val showroom = showroomRow(showroomId, forWrite = true)
        if (status == "SUSPENDED") {
            val units = unitsOnHand(setOf(areaId))
            if (units.signum() != 0) throw ApiErrors.areaHasStock(units)
            if (areaId == showroom[LocationsTable.defaultReceivingLocationId] || areaId == showroom[LocationsTable.defaultSellingLocationId]) {
                throw ApiErrors.invalidLocation("This area is the showroom's default receiving or selling area; change the defaults first")
            }
        }
        LocationsTable.update({ LocationsTable.id eq areaId }) { it[LocationsTable.status] = status }
        domainEvents.record(type = "showroom.area.status-changed", aggregateType = "storage-area", aggregateId = areaId, aggregateRef = area[LocationsTable.code],
            summary = "Area ${area[LocationsTable.code]} ${if (status == "ACTIVE") "reactivated" else "deactivated"}", locationId = showroomId)
        detail(showroomId)
    }

    fun setDefaults(showroomId: UUID, request: ShowroomDefaultsRequest): ShowroomDetail = tx.execute {
        showroomRow(showroomId, forWrite = true)
        fun check(id: UUID?, selling: Boolean) {
            if (id == null || id == showroomId) return
            val area = LocationsTable.selectAll().where { LocationsTable.id eq id }.firstOrNull()
            if (area == null || area[LocationsTable.parentLocationId] != showroomId || area[LocationsTable.status] != "ACTIVE") {
                throw ApiErrors.invalidLocation("Defaults must be the showroom itself or one of its active areas")
            }
            if (selling && area[LocationsTable.areaKind] == "QUARANTINE") throw ApiErrors.invalidLocation("The selling area can't be a quarantine area")
        }
        check(request.defaultReceivingLocationId, selling = false)
        check(request.defaultSellingLocationId, selling = true)
        LocationsTable.update({ LocationsTable.id eq showroomId }) {
            it[defaultReceivingLocationId] = request.defaultReceivingLocationId
            it[defaultSellingLocationId] = request.defaultSellingLocationId
        }
        detail(showroomId)
    }

    // ---- Reads ----

    fun list(q: String?, statuses: Set<String>, stateCodes: Set<String>): ShowroomList = tx.execute {
        val parsed = searchSpec.parse(q)
        val parts = mutableListOf<Op<Boolean>>((LocationsTable.tenantId eq tenantContext.requireTenantId()) and (LocationsTable.type eq "SHOWROOM"))
        locationAccess.allowedLocationIds()?.let { parts += LocationsTable.id inList it }
        val scopeOnly = parts.toList()
        if (statuses.isNotEmpty()) parts += LocationsTable.status inList statuses.map { it.uppercase() }
        if (stateCodes.isNotEmpty()) parts += LocationsTable.state inList stateCodes.map { it.uppercase() }
        searchSpec.condition(parsed)?.let { parts += it }

        val rows = LocationsTable.selectAll().where(parts.allOf()!!)
            .orderBy(*(searchSpec.rank(parsed) + (LocationsTable.name to SortOrder.ASC)).toTypedArray())
            .toList()
        val areaCounts = areaCounts(rows.map { it[LocationsTable.id].value })
        val states = stateNames()
        val all = LocationsTable.select(LocationsTable.status).where(scopeOnly.allOf()!!).map { it[LocationsTable.status] }
        ShowroomList(
            items = rows.map { row ->
                ShowroomRow(
                    id = row[LocationsTable.id].value, code = row[LocationsTable.code], name = row[LocationsTable.name],
                    streetAddress = row[LocationsTable.streetAddress], city = row[LocationsTable.city],
                    stateCode = row[LocationsTable.state], stateName = states[row[LocationsTable.state]],
                    managerName = row[LocationsTable.managerName], phone = row[LocationsTable.phone], dateOpened = row[LocationsTable.dateOpened],
                    vatRate = row[LocationsTable.vatRate], vatEnabled = row[LocationsTable.vatEnabled], status = row[LocationsTable.status],
                    areaCount = areaCounts[row[LocationsTable.id].value] ?: 0,
                )
            },
            stats = mapOf(
                "active" to all.count { it == "ACTIVE" }.toLong(),
                "pendingOpening" to all.count { it == "PENDING" }.toLong(),
                "suspended" to all.count { it == "SUSPENDED" }.toLong(),
            ),
        )
    }

    fun get(id: UUID): ShowroomDetail = tx.execute { detail(id) }

    /** Picker: showrooms in scope with their areas as children. */
    fun lookup(q: String?, status: String?): List<ShowroomLookupRow> = tx.execute {
        list(q, setOfNotNull(status ?: "ACTIVE"), emptySet()).items.take(50).map { row ->
            ShowroomLookupRow(row.id, row.code, row.name, row.status, areas(row.id))
        }
    }

    // ---- For the other showroom services (caller's transaction) ----

    /** A showroom of this tenant that the caller may act at; otherwise SHOWROOM_NOT_FOUND / LOCATION_ACCESS_DENIED. */
    fun showroomRow(id: UUID, forWrite: Boolean = false): ResultRow {
        val row = LocationsTable.selectAll()
            .where { (LocationsTable.id eq id) and (LocationsTable.tenantId eq tenantContext.requireTenantId()) and (LocationsTable.type eq "SHOWROOM") }
            .firstOrNull() ?: throw ApiErrors.showroomNotFound()
        if (forWrite) locationAccess.assertAccess(id)
        else locationAccess.allowedLocationIds()?.let { if (id !in it) throw ApiErrors.showroomNotFound() }
        return row
    }

    /** The showroom's QUARANTINE area, created on first use (returned goods that must never be sold). */
    fun quarantineArea(showroomId: UUID): UUID {
        val existing = LocationsTable.select(LocationsTable.id).where {
            (LocationsTable.parentLocationId eq showroomId) and (LocationsTable.areaKind eq "QUARANTINE") and (LocationsTable.status eq "ACTIVE")
        }.firstOrNull()?.get(LocationsTable.id)?.value
        if (existing != null) return existing
        val showroom = showroomRow(showroomId)
        val id = insertArea(showroomId, showroom[LocationsTable.code], "Quarantine", "QUARANTINE")
        domainEvents.record(type = "showroom.area.created", aggregateType = "storage-area", aggregateId = id, aggregateRef = null,
            summary = "Quarantine area created for ${showroom[LocationsTable.name]}", locationId = showroomId)
        return id
    }

    fun withAreas(showroomId: UUID): Set<UUID> =
        setOf(showroomId) + LocationsTable.select(LocationsTable.id).where { LocationsTable.parentLocationId eq showroomId }.map { it[LocationsTable.id].value }

    fun detail(id: UUID): ShowroomDetail {
        val row = showroomRow(id)
        val stateCode = row[LocationsTable.state]
        return ShowroomDetail(
            id = id, code = row[LocationsTable.code], name = row[LocationsTable.name],
            stateCode = stateCode, stateName = stateCode?.let { stateNames()[it] },
            streetAddress = row[LocationsTable.streetAddress], city = row[LocationsTable.city], phone = row[LocationsTable.phone],
            email = row[LocationsTable.email], managerName = row[LocationsTable.managerName], manager = UserRef.of(row[LocationsTable.managerUserId]?.value),
            dateOpened = row[LocationsTable.dateOpened], storageCapacity = row[LocationsTable.storageCapacity], operatingHours = row[LocationsTable.operatingHours],
            status = row[LocationsTable.status], vatRate = row[LocationsTable.vatRate], vatEnabled = row[LocationsTable.vatEnabled],
            vatRegistrationNumber = row[LocationsTable.vatRegistrationNumber], areas = areas(id),
            defaultReceivingLocationId = row[LocationsTable.defaultReceivingLocationId], defaultSellingLocationId = row[LocationsTable.defaultSellingLocationId],
            createdAt = row[LocationsTable.createdAt],
        )
    }

    private fun areas(showroomId: UUID): List<AreaView> {
        val rows = LocationsTable.selectAll().where { LocationsTable.parentLocationId eq showroomId }.orderBy(LocationsTable.code to SortOrder.ASC).toList()
        val units = unitsByLocation(rows.map { it[LocationsTable.id].value })
        return rows.map {
            AreaView(it[LocationsTable.id].value, it[LocationsTable.code], it[LocationsTable.name], it[LocationsTable.areaKind] ?: "OTHER",
                it[LocationsTable.status], units[it[LocationsTable.id].value] ?: BigDecimal.ZERO)
        }
    }

    private fun insertArea(showroomId: UUID, showroomCode: String, name: String, kind: String): UUID {
        AccessCaches.locationsChanged(tenantContext.requireTenantId(), showroomId)
        val suffix = AREA_SUFFIXES.getValue(kind)
        val taken = LocationsTable.select(LocationsTable.code).where { LocationsTable.parentLocationId eq showroomId }.map { it[LocationsTable.code] }.toSet()
        val code = generateSequence(1) { it + 1 }.map { n -> if (n == 1 && kind != "OTHER") "$showroomCode-$suffix" else "$showroomCode-$suffix$n" }
            .first { it !in taken }
        return LocationsTable.insertAndGetId {
            it[tenantId] = tenantContext.requireTenantId()
            it[type] = "STORAGE_AREA"
            it[LocationsTable.code] = code
            it[LocationsTable.name] = name
            it[parentLocationId] = showroomId
            it[areaKind] = kind
            it[status] = "ACTIVE"
            it[createdByUserId] = currentActorProvider.current().userId
            it[createdAt] = Instant.now()
        }.value
    }

    private fun validate(request: ShowroomRequest) {
        if (NigeriaStatesTable.selectAll().where { NigeriaStatesTable.code eq request.stateCode.trim().uppercase() }.empty()) throw ApiErrors.invalidStateCode()
        request.dateOpened?.let { if (it.isAfter(LocalDate.now(moduleSettings.zone()).plusYears(1))) throw ApiErrors.validationFailed(mapOf("dateOpened" to "is too far in the future")) }
        request.managerUserId?.let { userId ->
            val member = TenantMembershipsTable.selectAll()
                .where { (TenantMembershipsTable.tenantId eq tenantContext.requireTenantId()) and (TenantMembershipsTable.userId eq userId) }.firstOrNull()
            if (member == null || member[TenantMembershipsTable.status] != "ACTIVE") throw ApiErrors.invalidUser("The manager must be an active member of this company")
        }
    }

    private fun write(statement: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, request: ShowroomRequest) {
        val street = request.streetAddress.trim()
        val city = request.city.trim()
        statement[LocationsTable.name] = request.name.trim()
        statement[LocationsTable.streetAddress] = street
        statement[LocationsTable.city] = city
        statement[LocationsTable.address] = "$street, $city".take(300)
        statement[LocationsTable.state] = request.stateCode.trim().uppercase()
        statement[LocationsTable.phone] = request.phone.trim()
        statement[LocationsTable.email] = request.email?.trim()?.lowercase()?.ifEmpty { null }
        statement[LocationsTable.managerName] = request.managerName?.trim()?.ifEmpty { null }
        statement[LocationsTable.managerUserId] = request.managerUserId?.let { org.jetbrains.exposed.dao.id.EntityID(it, com.zifiq.platform.identity.model.AppUsersTable) }
        statement[LocationsTable.dateOpened] = request.dateOpened
        statement[LocationsTable.storageCapacity] = request.storageCapacity?.trim()?.ifEmpty { null }
        statement[LocationsTable.operatingHours] = request.operatingHours?.trim()?.ifEmpty { null }
    }

    private fun ensureNameFree(name: String, existingId: UUID?) {
        val clash = LocationsTable.selectAll().where {
            var condition = (LocationsTable.tenantId eq tenantContext.requireTenantId()) and (LocationsTable.type eq "SHOWROOM") and
                (LocationsTable.name.lowerCase() eq name.lowercase())
            if (existingId != null) condition = condition and (LocationsTable.id neq existingId)
            condition
        }.empty().not()
        if (clash) throw ApiErrors.duplicateName(name)
    }

    private fun areaRow(areaId: UUID): ResultRow =
        LocationsTable.selectAll()
            .where { (LocationsTable.id eq areaId) and (LocationsTable.tenantId eq tenantContext.requireTenantId()) and (LocationsTable.type eq "STORAGE_AREA") }
            .firstOrNull() ?: throw ApiErrors.invalidLocation("Storage area not found")

    private fun areaCounts(showroomIds: List<UUID>): Map<UUID, Int> =
        if (showroomIds.isEmpty()) emptyMap() else LocationsTable.select(LocationsTable.parentLocationId)
            .where { (LocationsTable.parentLocationId inList showroomIds) and (LocationsTable.status eq "ACTIVE") }
            .groupingBy { it[LocationsTable.parentLocationId]!! }.eachCount()

    private fun unitsByLocation(ids: List<UUID>): Map<UUID, BigDecimal> {
        if (ids.isEmpty()) return emptyMap()
        val total = Sum(StockBalancesTable.quantityOnHand, org.jetbrains.exposed.sql.DecimalColumnType(16, 3))
        return StockBalancesTable.select(StockBalancesTable.locationId, total).where { StockBalancesTable.locationId inList ids }
            .groupBy(StockBalancesTable.locationId).associate { it[StockBalancesTable.locationId] to (it[total] ?: BigDecimal.ZERO) }
    }

    private fun unitsOnHand(ids: Set<UUID>): BigDecimal = unitsByLocation(ids.toList()).values.fold(BigDecimal.ZERO, BigDecimal::add)

    private fun stateNames(): Map<String, String> = NigeriaStatesTable.selectAll().associate { it[NigeriaStatesTable.code] to it[NigeriaStatesTable.name] }

    private fun format(units: BigDecimal) = NumberFormat.getNumberInstance(Locale.UK).format(units.stripTrailingZeros())

    companion object {
        val AREA_NAMES = mapOf("SHOP_FLOOR" to "Shop Floor", "WAREHOUSE" to "Warehouse", "RECEIVING" to "Receiving", "QUARANTINE" to "Quarantine", "OTHER" to "Other")
        val AREA_SUFFIXES = mapOf("SHOP_FLOOR" to "SF", "WAREHOUSE" to "WH", "RECEIVING" to "RC", "QUARANTINE" to "QR", "OTHER" to "A")
    }
}
