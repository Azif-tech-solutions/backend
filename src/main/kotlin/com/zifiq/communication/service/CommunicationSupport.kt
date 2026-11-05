package com.zifiq.communication.service

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
 * Shared reads and checks for the Communication (Commissions) services (docs/12 §3); everything runs
 * in the caller's transaction.
 */
@Component
class CommunicationSupport(
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

    fun visibleLocations(): Set<UUID>? = locationAccess.allowedLocationIds()
    fun assertAccess(locationId: UUID) = locationAccess.assertAccess(locationId)

    fun record(type: String, aggregateType: String, id: UUID, ref: String?, summary: String, payload: Map<String, Any?> = emptyMap(), locationId: UUID? = null): UUID =
        domainEvents.record(type = type, aggregateType = aggregateType, aggregateId = id, aggregateRef = ref, summary = summary, payload = payload, locationId = locationId)
}
