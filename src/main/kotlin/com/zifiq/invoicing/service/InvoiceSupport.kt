package com.zifiq.invoicing.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.model.LocationsTable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.springframework.stereotype.Component

/**
 * Shared reads and checks for the invoicing services; everything runs in the caller's transaction.
 * Location scope (docs/08 §3): an invoice belongs to its issuing showroom, and lists and reads
 * follow [LocationAccessService] — "search never returns invoices outside the user's permitted scope".
 */
@Component
class InvoiceSupport(
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

    /** A balance at or below this counts as settled (absorbs VAT rounding). */
    fun tolerance(): BigDecimal = moduleSettings.decimal(InvoicingSettings.PAID_TOLERANCE)

    fun defaultTermsDays(): Int = moduleSettings.int(InvoicingSettings.DEFAULT_PAYMENT_TERMS_DAYS)

    /** Null = every location; otherwise the user's location (and its areas). */
    fun visibleLocations(): Set<UUID>? = locationAccess.allowedLocationIds()

    fun assertAccess(locationId: UUID) = locationAccess.assertAccess(locationId)

    /** An invoice outside the caller's scope is reported as not found, never as forbidden. */
    fun assertVisible(locationId: UUID) {
        val visible = visibleLocations()
        if (visible != null && locationId !in visible) throw ApiErrors.invoiceNotFound()
    }

    fun filterContext(): FilterContext = FilterContext(costVisible = true, zone = zone())

    fun locationNames(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else LocationsTable.select(LocationsTable.id, LocationsTable.name).where { LocationsTable.id inList ids.toSet() }
            .associate { it[LocationsTable.id].value to it[LocationsTable.name] }

    fun userNames(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else AppUsersTable.select(AppUsersTable.id, AppUsersTable.displayName).where { AppUsersTable.id inList ids.toSet() }
            .associate { it[AppUsersTable.id].value to it[AppUsersTable.displayName] }

    /** Records a domain event; returns its id so a notification can link to it. */
    fun record(type: String, id: UUID, ref: String?, summary: String, payload: Map<String, Any?> = emptyMap(), locationId: UUID? = null): UUID =
        domainEvents.record(type = type, aggregateType = "invoice", aggregateId = id, aggregateRef = ref, summary = summary, payload = payload, locationId = locationId)

    companion object {
        const val SOURCE_MODULE = "invoicing"

        fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)
        fun naira(value: BigDecimal): String = "₦" + "%,.2f".format(value)
    }
}
