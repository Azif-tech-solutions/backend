package com.zifiq.reports.service

import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Component

/** Shared reads and checks for Reports & Analytics (docs/13); everything runs in the caller's transaction. */
@Component
class ReportsSupport(
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val moduleSettings: ModuleSettings,
) {
    fun tenantId(): UUID = tenantContext.requireTenantId()
    fun userId(): UUID = currentActorProvider.current().userId
    fun can(permission: String): Boolean = permissionAuthorizer.hasPermission(userId(), permission)
    fun zone(): ZoneId = moduleSettings.zone()
    fun today(): LocalDate = LocalDate.now(zone())
    fun settings(): ModuleSettings = moduleSettings

    /** Cost columns are hidden without this permission (docs/13 §4.2, doc 03 §14). */
    fun costVisible(): Boolean = can("factory.costing.read")
}
