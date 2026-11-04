package com.zifiq.barcode.service

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
 * Shared reads and checks for the Barcode & QR services (docs/11 §3). A scan carries a `context`, so
 * the permission it needs is dynamic ([can]); location-bound contexts also run [assertAccess].
 * Everything runs in the caller's transaction.
 */
@Component
class BarcodeSupport(
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val locationAccess: LocationAccessService,
    private val moduleSettings: ModuleSettings,
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
}
