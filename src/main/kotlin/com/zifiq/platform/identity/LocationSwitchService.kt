package com.zifiq.platform.identity

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.identity.dto.SwitchableLocation
import com.zifiq.platform.identity.dto.TokenResponse
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Working inside one factory or showroom, like working inside one tenant: the session switches
 * location (new tokens carrying `location_id`) and from then on every list, report, read and write
 * in every module is confined to it (LocationAccessService), so two factories of a tenant never see
 * each other's data. A user assigned to a location can only ever be in that one.
 */
@Service
class LocationSwitchService(
    private val tx: TenantScopedTransaction,
    private val actors: CurrentActorProvider,
    private val locationAccess: LocationAccessService,
    private val authService: AuthService,
) {
    /** The factories and showrooms this user may work in: their assigned one, or every factory and showroom of the tenant. */
    fun switchable(): List<SwitchableLocation> = tx.execute {
        val assigned = locationAccess.assignedLocation()
        LocationsTable.selectAll()
            .where {
                (LocationsTable.tenantId eq actors.current().tenantId) and (LocationsTable.type inList ROOT_TYPES) and
                    (if (assigned != null) LocationsTable.id eq assigned else LocationsTable.id.isNotNull())
            }
            .orderBy(LocationsTable.type to SortOrder.ASC, LocationsTable.name to SortOrder.ASC)
            .map {
                SwitchableLocation(it[LocationsTable.id].value, it[LocationsTable.code], it[LocationsTable.name], it[LocationsTable.type], it[LocationsTable.status] == "ACTIVE")
            }
    }

    /**
     * New tokens working in [locationId] (an active factory or showroom of this tenant), or — for a user
     * not assigned to a location — in the whole company when it is null. Anything else is refused.
     */
    fun switch(locationId: UUID?): TokenResponse {
        val actor = actors.current()
        val (email, name) = tx.execute {
            val assigned = locationAccess.assignedLocation()
            if (assigned != null && locationId != assigned) throw ApiErrors.locationAccessDenied()
            if (locationId != null) {
                val location = LocationsTable.selectAll().where { (LocationsTable.tenantId eq actor.tenantId) and (LocationsTable.id eq locationId) }.firstOrNull()
                    ?: throw ApiErrors.locationNotFound()
                if (location[LocationsTable.type] !in ROOT_TYPES) throw ApiErrors.validationFailed(mapOf("locationId" to "Switch to a factory or showroom, not a storage area"))
                if (location[LocationsTable.status] != "ACTIVE") throw ApiErrors.validationFailed(mapOf("locationId" to "This location is not active"))
            }
            val user = AppUsersTable.selectAll().where { AppUsersTable.id eq actor.userId }.single()
            user[AppUsersTable.email] to user[AppUsersTable.displayName]
        }
        return authService.issueTokensFor(actor.userId, actor.tenantId, email, name, locationId)
    }

    private companion object {
        val ROOT_TYPES = listOf("FACTORY", "SHOWROOM")
    }
}
