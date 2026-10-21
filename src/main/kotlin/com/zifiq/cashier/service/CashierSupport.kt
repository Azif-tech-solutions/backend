package com.zifiq.cashier.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Shared reads and checks for the Sales & POS services; everything runs in the caller's transaction.
 * Scope (docs/09 §3): a sale and a till session belong to their selling location, and every read and
 * write follows [LocationAccessService] — a sale outside the caller's factory or showroom is "not
 * found". A cashier holding only `cashier.sales.read` sees their own sales and sessions.
 */
@Component
class CashierSupport(
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
    private val domainEvents: DomainEvents,
) {
    fun tenantId(): UUID = tenantContext.requireTenantId()
    fun userId(): UUID = currentActorProvider.current().userId
    fun can(permission: String): Boolean = permissionAuthorizer.hasPermission(userId(), permission)
    fun zone(): ZoneId = moduleSettings.zone()
    fun today(): LocalDate = LocalDate.now(zone())
    fun settings(): ModuleSettings = moduleSettings

    /** Null = every location; otherwise the caller's factory/showroom (and its areas). */
    fun visibleLocations(): Set<UUID>? = locationAccess.allowedLocationIds()

    fun assertAccess(locationId: UUID) = locationAccess.assertAccess(locationId)

    /** Only their own sales and sessions, unless they may read everyone's. */
    fun ownOnly(): Boolean = !can(READ_ALL)

    /** A record outside the caller's scope is reported as not found, never as forbidden. */
    fun assertVisible(locationId: UUID, ownerUserId: UUID, notFound: () -> Exception) {
        val visible = visibleLocations()
        if (visible != null && locationId !in visible) throw notFound()
        if (ownOnly() && ownerUserId != userId()) throw notFound()
    }

    fun requireOwner(ownerUserId: UUID) {
        if (ownerUserId != userId()) throw ApiErrors.accessDenied()
    }

    fun record(type: String, aggregateType: String, id: UUID, ref: String?, summary: String, payload: Map<String, Any?> = emptyMap(), locationId: UUID? = null): UUID =
        domainEvents.record(type = type, aggregateType = aggregateType, aggregateId = id, aggregateRef = ref, summary = summary, payload = payload, locationId = locationId)

    companion object {
        const val SOURCE_MODULE = "cashier"
        const val READ_ALL = "cashier.sales.read-all"
    }
}
