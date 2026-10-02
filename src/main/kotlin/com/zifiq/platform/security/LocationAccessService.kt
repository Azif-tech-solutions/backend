package com.zifiq.platform.security

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.tenancy.model.UserLocationAssignmentsTable
import java.util.UUID
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/**
 * Enforces the PRD business rule that a user assigned to a single location (SINGLE_LOCATION, e.g.
 * a Stock Keeper tied to one factory) can only read/write that location's records, while a user
 * with no assignment row or an explicit ALL_LOCATIONS scope (e.g. Administrator/Management) sees
 * everything. Every factory/inventory service method that takes a `locationId` calls through here
 * instead of trusting the caller-supplied value directly.
 *
 * Must be called from inside an already-open [com.zifiq.platform.tenancy.TenantScopedTransaction]
 * -- it runs plain Exposed DSL, it does not open its own transaction.
 */
@Component
class LocationAccessService(
    private val currentActorProvider: CurrentActorProvider,
) {
    /** Null = unrestricted (no assignment row, or explicit ALL_LOCATIONS). Otherwise the one location the current user may access. */
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

    /** For write endpoints: throws [ApiErrors.locationAccessDenied] if the current user isn't allowed at [locationId]. */
    fun assertAccess(locationId: UUID) {
        val restriction = restriction()
        if (restriction != null && restriction != locationId) throw ApiErrors.locationAccessDenied()
    }

    /**
     * For list/read endpoints that take an optional `locationId` filter. A restricted user's own
     * location always wins -- if they pass a conflicting filter it's a denial, not an empty list
     * (silently returning nothing would hide the fact that the request was out of scope); passing
     * no filter narrows to their location rather than defaulting to "all". An unrestricted user's
     * requested filter (including null, meaning "all locations") passes through unchanged.
     */
    fun resolveFilter(requested: UUID?): UUID? {
        val restriction = restriction() ?: return requested
        if (requested != null && requested != restriction) throw ApiErrors.locationAccessDenied()
        return restriction
    }
}
