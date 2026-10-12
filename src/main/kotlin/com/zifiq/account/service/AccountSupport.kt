package com.zifiq.account.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.DatePeriod
import com.zifiq.common.filter.FilterContext
import com.zifiq.platform.accesscontrol.RoleDefaults
import com.zifiq.platform.accesscontrol.model.TenantRolesTable
import com.zifiq.platform.accesscontrol.model.UserRoleAssignmentsTable
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
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/**
 * Shared reads and checks for the Account services; everything runs in the caller's transaction.
 *
 * Location scope (docs/07 §3): accounts and entries at a factory or showroom follow
 * [LocationAccessService]; head-office ones (`location_id` null) are visible only to users not
 * restricted to a single location.
 */
@Component
class AccountSupport(
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
    fun today(): LocalDate = LocalDate.now(moduleSettings.zone())
    fun moduleSettings(): ModuleSettings = moduleSettings

    /** Null = the user sees every location and head office; otherwise their location (and its areas) only. */
    fun visibleLocations(): Set<UUID>? = locationAccess.allowedLocationIds()

    /** For a record at [locationId] (null = head office): throws unless the current user may see/act on it. */
    fun assertAccess(locationId: UUID?) {
        if (locationId == null) {
            if (visibleLocations() != null) throw ApiErrors.locationAccessDenied()
        } else {
            locationAccess.assertAccess(locationId)
        }
    }

    /** The location filter for a list: the caller's own location when restricted; [requested] otherwise. */
    fun resolveLocation(requested: UUID?): UUID? = locationAccess.resolveFilter(requested)

    /** Money accounts belong to a factory or showroom (not one of its storage areas), or to head office. */
    fun validateLocation(locationId: UUID?) {
        if (locationId == null) return
        val location = LocationsTable.selectAll()
            .where { (LocationsTable.id eq locationId) and (LocationsTable.tenantId eq tenantId()) }
            .firstOrNull() ?: throw ApiErrors.invalidLocation("Location not found")
        if (location[LocationsTable.type] !in setOf("SHOWROOM", "FACTORY")) throw ApiErrors.invalidLocation("Accounts belong to a showroom or factory, not a storage area")
        locationAccess.assertAccess(locationId)
    }

    fun locationNames(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else LocationsTable.select(LocationsTable.id, LocationsTable.name).where { LocationsTable.id inList ids.toSet() }
            .associate { it[LocationsTable.id].value to it[LocationsTable.name] }

    fun userNames(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else AppUsersTable.select(AppUsersTable.id, AppUsersTable.displayName).where { AppUsersTable.id inList ids.toSet() }
            .associate { it[AppUsersTable.id].value to it[AppUsersTable.displayName] }

    /** Records a domain event; returns its id so a notification can link to it. */
    fun record(type: String, aggregateType: String, id: UUID, ref: String?, summary: String, payload: Map<String, Any?> = emptyMap(), locationId: UUID? = null): UUID =
        domainEvents.record(type = type, aggregateType = aggregateType, aggregateId = id, aggregateRef = ref, summary = summary, payload = payload, locationId = locationId)

    /** Holds the tenant's Administrator role (exempt from the running-cost four-eyes rule when the setting allows). */
    fun isAdministrator(): Boolean =
        UserRoleAssignmentsTable.join(TenantRolesTable, JoinType.INNER, UserRoleAssignmentsTable.roleId, TenantRolesTable.id)
            .selectAll()
            .where { (UserRoleAssignmentsTable.tenantId eq tenantId()) and (UserRoleAssignmentsTable.userId eq userId()) and (TenantRolesTable.name eq RoleDefaults.ADMINISTRATOR) }
            .any()

    fun filterContext(): FilterContext = FilterContext(costVisible = true, zone = moduleSettings.zone())

    /** The from/to a list or statement is bounded by: explicit dates, else a period preset, else null. */
    fun dateRange(params: Map<String, List<String>>, context: FilterContext = filterContext()): Pair<LocalDate?, LocalDate?> {
        val period = params["period"]?.firstOrNull()?.let { DatePeriod.parse(it) }?.resolve(context.today)
        val from = params["from"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: period?.from
        val to = params["to"]?.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: period?.to
        return from to to
    }

    /** A business date for a money document: not in the future. */
    fun requireNotFuture(field: String, date: LocalDate) {
        if (date.isAfter(today())) throw ApiErrors.validationFailed(mapOf(field to "can't be in the future"))
    }

    companion object {
        fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)
        fun naira(value: BigDecimal): String = "₦" + "%,.2f".format(value)
    }
}
