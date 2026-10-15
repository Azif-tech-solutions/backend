package com.zifiq.platform

import com.zifiq.platform.security.AccessCaches
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * In-process caches must never carry data across tenants (RLS is the isolation boundary): a value
 * cached for one tenant is never served to another, and another tenant's empty lookup never
 * poisons the owner's entry.
 */
class CacheTenantIsolationTest : PostgresIntegrationTest() {
    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val hierarchy = LocationHierarchy()

    @Test
    fun `location caches are keyed by the RLS tenant`() {
        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        val user = UUID.randomUUID()
        val showroomA = UUID.randomUUID()
        val areaA = UUID.randomUUID()
        transaction(database) {
            exec("insert into app_users (id, email, display_name) values ('$user', 'iso-$user@test.com', 'Iso')")
            exec("insert into tenants (id, name) values ('$tenantA', 'Tenant A'), ('$tenantB', 'Tenant B')")
            exec("select set_config('app.tenant_id', '$tenantA', true)")
            exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$showroomA', '$tenantA', 'SHOWROOM', 'ISO-1', 'A showroom', '$user')")
            exec("insert into locations (id, tenant_id, type, code, name, parent_location_id, area_kind, created_by_user_id) " +
                "values ('$areaA', '$tenantA', 'STORAGE_AREA', 'ISO-1-FL', 'A floor', '$showroomA', 'SHOP_FLOOR', '$user')")
        }
        AccessCaches.invalidateAll()

        // Tenant B asks first, about tenant A's ids: RLS shows it nothing, and that is all it gets.
        val seenByB = tx.executeForTenant(tenantB) { hierarchy.withAreas(showroomA) to hierarchy.parentOf(areaA) }
        assertThat(seenByB.first).containsExactly(showroomA)
        assertThat(seenByB.second).isNull()

        // Tenant A then gets its real hierarchy, not B's cached empty answers.
        val seenByA = tx.executeForTenant(tenantA) { hierarchy.withAreas(showroomA) to hierarchy.parentOf(areaA) }
        assertThat(seenByA.first).containsExactlyInAnyOrder(showroomA, areaA)
        assertThat(seenByA.second).isEqualTo(showroomA)

        // And with A's answers now cached, B still sees nothing of A.
        val againByB = tx.executeForTenant(tenantB) { hierarchy.withAreas(showroomA) to hierarchy.parentOf(areaA) }
        assertThat(againByB.first).containsExactly(showroomA)
        assertThat(againByB.second).isNull()
    }
}
