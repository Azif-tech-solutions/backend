package com.zifiq.platform.security

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.model.UserLocationAssignmentsTable
import java.util.UUID
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/**
 * Enforces the PRD business rule that a user assigned to a single location (SINGLE_LOCATION, e.g.
 * a Stock Keeper tied to one factory) can only read/write that location's records, while a user
 * with no assignment row or an explicit ALL_LOCATIONS scope (e.g. Administrator/Management) sees
 * everything. Every service method that takes a `locationId` calls through here instead of trusting
 * the caller-supplied value directly.
 *
 * Users are assigned to a factory or showroom; that assignment also covers the location's storage
 * areas (Shop Floor, Warehouse, ...) — docs/03 §11.3.
 *
 * Must be called from inside an already-open [com.zifiq.platform.tenancy.TenantScopedTransaction]
 * -- it runs plain Exposed DSL, it does not open its own transaction.
 */
@Component
class LocationAccessService(
    private val currentActorProvider: CurrentActorProvider,
    private val hierarchy: LocationHierarchy = LocationHierarchy(),
) {
    /** Null = unrestricted (no assignment row, or explicit ALL_LOCATIONS). Otherwise the one location the current user is assigned to. */
    private fun restriction(): UUID? {
        val actor = currentActorProvider.current()
        val row = UserLocationAssignmentsTable.selectAll()
            .andWhere { UserLocationAssignmentsTable.tenantId eq actor.tenantId }
            .andWhere { UserLocationAssignmentsTable.userId eq actor.userId }
            .firstOrNull() ?: return null
        return if (row[UserLocationAssignmentsTable.accessScope] == "SINGLE_LOCATION") {
            row[UserLocationAssignmentsTable.locationId]
        } else {
            null
        }
    }

    /** Null = unrestricted. Otherwise the assigned location plus its storage areas. */
    fun allowedLocationIds(): Set<UUID>? = restriction()?.let(hierarchy::withAreas)

    /** For write endpoints: throws [ApiErrors.locationAccessDenied] unless the current user may act at [locationId] (or it's an area of their location). */
    fun assertAccess(locationId: UUID) {
        val restriction = restriction() ?: return
        if (locationId != restriction && hierarchy.parentOf(locationId) != restriction) throw ApiErrors.locationAccessDenied()
    }

    /**
     * For list/read endpoints with an optional `locationId` filter; returns the location ids to
     * filter on (`location_id IN (...)`), or null for "everywhere". A factory or showroom includes
     * its storage areas; an area is just itself. A restricted user's own location always wins: a
     * conflicting filter is a denial, not an empty list (silently returning nothing would hide that
     * the request was out of scope), and no filter narrows to their location rather than "all".
     */
    fun resolveFilterSet(requested: UUID?): Set<UUID>? {
        val restriction = restriction() ?: return requested?.let(hierarchy::withAreas)
        val allowed = hierarchy.withAreas(restriction)
        if (requested == null || requested == restriction) return allowed
        if (requested !in allowed) throw ApiErrors.locationAccessDenied()
        return setOf(requested)
    }

    /**
     * Root-level variant for records that only ever belong to a factory or showroom (customers'
     * home location, correspondents): the restricted user's own location, or [requested] when
     * unrestricted. Prefer [resolveFilterSet] for anything that can sit in a storage area.
     */
    fun resolveFilter(requested: UUID?): UUID? {
        val restriction = restriction() ?: return requested
        if (requested != null && requested != restriction) throw ApiErrors.locationAccessDenied()
        return restriction
    }
}
