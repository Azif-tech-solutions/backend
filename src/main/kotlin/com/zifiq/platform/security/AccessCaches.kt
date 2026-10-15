package com.zifiq.platform.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.util.Optional
import java.util.UUID

/**
 * In-process caches for the lookups every request repeats: the caller's permission codes, their
 * location restriction, a location's storage areas, and module setting values. Without them a
 * single request runs the same joins several times (each `@PreAuthorize`, each service `can()`,
 * every search provider's `canSearch`).
 *
 * Every key includes the tenant, and every value was read under that tenant's row-level security,
 * so a cached value is only ever served back to the tenant whose RLS scope produced it.
 * Writers clear the affected entries at once; the short expiry bounds staleness on other app
 * instances, which only see their own clears.
 */
object AccessCaches {
    private val TTL: Duration = Duration.ofSeconds(30)

    data class UserKey(val tenantId: UUID, val userId: UUID)

    data class SettingKey(val tenantId: UUID, val key: String)

    data class LocationKey(val tenantId: UUID, val locationId: UUID)

    val permissions: Cache<UserKey, Set<String>> = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(50_000).build()

    /** Empty = unrestricted. */
    val restrictions: Cache<UserKey, Optional<UUID>> = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(50_000).build()

    /** A location's storage areas (none for an area), as that tenant's RLS returned them. */
    val areas: Cache<LocationKey, Set<UUID>> = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(100_000).build()

    /** An area's parent factory/showroom; empty for a factory/showroom. Never changes once set. */
    val parents: Cache<LocationKey, Optional<UUID>> = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(30)).maximumSize(100_000).build()

    val settings: Cache<SettingKey, Any> = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(100_000).build()

    /** Roles, role permissions or role assignments changed in [tenantId]. */
    fun permissionsChanged(tenantId: UUID) = permissions.asMap().keys.removeIf { it.tenantId == tenantId }

    /** A user's location assignment changed. */
    fun restrictionChanged(tenantId: UUID, userId: UUID) = restrictions.invalidate(UserKey(tenantId, userId))

    /** An area was added under [parentId] in [tenantId]. */
    fun locationsChanged(tenantId: UUID, parentId: UUID) = areas.invalidate(LocationKey(tenantId, parentId))

    fun settingChanged(tenantId: UUID, key: String) = settings.invalidate(SettingKey(tenantId, key))

    /** Everything: for tests and tooling that write these tables directly. */
    fun invalidateAll() {
        permissions.invalidateAll()
        restrictions.invalidateAll()
        areas.invalidateAll()
        parents.invalidateAll()
        settings.invalidateAll()
    }
}
