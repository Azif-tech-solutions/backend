package com.zifiq.platform.tenancy

import com.zifiq.platform.security.AccessCaches
import java.util.Optional
import com.zifiq.platform.tenancy.model.LocationsTable
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.springframework.stereotype.Component

/**
 * Factories and showrooms and their storage areas (docs/03 §11.2): an area is a child location
 * whose stock is part of its parent's. Areas never nest. Runs in the caller's transaction (RLS
 * already scopes it to the tenant).
 */
@Component
class LocationHierarchy {
    /** [locationId] plus its storage areas (an area has none, so just itself). */
    fun withAreas(locationId: UUID): Set<UUID> = withAreas(setOf(locationId))

    fun withAreas(locationIds: Collection<UUID>): Set<UUID> {
        if (locationIds.isEmpty()) return emptySet()
        // Cached per tenant and location (AccessCaches), read under that tenant's RLS; adding an area clears its parent's entry.
        val tenantId = TenantScopedTransaction.rlsTenant()
        val areas = AccessCaches.areas.getAll(locationIds.map { AccessCaches.LocationKey(tenantId, it) }.toSet()) { missing ->
            val found = LocationsTable.select(LocationsTable.id, LocationsTable.parentLocationId)
                .where { LocationsTable.parentLocationId inList missing.map { it.locationId } }
                .groupBy({ it[LocationsTable.parentLocationId]!! }, { it[LocationsTable.id].value })
            missing.associateWith { found[it.locationId].orEmpty().toSet() }
        }
        return locationIds.toSet() + areas.values.flatten()
    }

    /** The factory/showroom an area belongs to; null for a factory/showroom (or an unknown id). */
    fun parentOf(locationId: UUID): UUID? =
        AccessCaches.parents.get(AccessCaches.LocationKey(TenantScopedTransaction.rlsTenant(), locationId)) {
            Optional.ofNullable(
                LocationsTable.select(LocationsTable.parentLocationId).where { LocationsTable.id eq locationId }.firstOrNull()?.get(LocationsTable.parentLocationId),
            )
        }.orElse(null)

    /** The factory/showroom a location belongs to: itself, or an area's parent. */
    fun rootOf(locationId: UUID): UUID = parentOf(locationId) ?: locationId
}
