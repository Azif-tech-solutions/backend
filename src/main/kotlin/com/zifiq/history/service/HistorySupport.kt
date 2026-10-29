package com.zifiq.history.service

import com.zifiq.common.filter.FilterContext
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
 * Shared reads and checks for the Audit & Compliance services (docs/10). Everything runs in the
 * caller's tenant-scoped transaction. Every report is location-scoped through [LocationAccessService]
 * ([visibleLocations]); cost/value columns follow doc 03 §14, masked when the caller lacks
 * `factory.costing.read` ([costVisible]). Changes to review state are written to the activity log as
 * `history.review.updated` events ([record]).
 */
@Component
class HistorySupport(
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

    /** Landing/unit-cost and value columns are shown only to holders of `factory.costing.read` (doc 03 §14). */
    fun costVisible(): Boolean = can(COSTING_READ)

    fun filterContext(): FilterContext = FilterContext(costVisible = costVisible(), zone = zone())

    fun record(type: String, aggregateType: String, id: UUID, ref: String?, summary: String, payload: Map<String, Any?> = emptyMap(), locationId: UUID? = null): UUID =
        domainEvents.record(type = type, aggregateType = aggregateType, aggregateId = id, aggregateRef = ref, summary = summary, payload = payload, locationId = locationId)

    companion object {
        const val SOURCE_MODULE = "history"
        const val COSTING_READ = "factory.costing.read"
    }
}
