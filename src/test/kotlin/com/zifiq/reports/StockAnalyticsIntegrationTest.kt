package com.zifiq.reports

import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.service.ReportsSettings
import com.zifiq.reports.service.ReportsSupport
import com.zifiq.reports.service.StockAnalyticsService
import com.zifiq.showroom.spi.BatchRef
import com.zifiq.showroom.spi.ReturnFilter
import com.zifiq.showroom.spi.ReturnLineRef
import com.zifiq.showroom.spi.ShowroomQueries
import com.zifiq.showroom.spi.ShowroomRef
import com.zifiq.showroom.spi.StockEntryFilter
import com.zifiq.showroom.spi.StockEntryLineRef
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Reports & Analytics slice 3c: stock analytics (value, slow-moving/dead, days of cover) aggregated
 * from the inventory ledger and item master, with stock value masked without `factory.costing.read`.
 * Ledger, items and permissions are real against Postgres.
 */
class StockAnalyticsIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val clerk = UUID.randomUUID()
        private val loc = UUID.randomUUID()
        private val itemA = UUID.randomUUID()
        private val itemB = UUID.randomUUID()
        private val itemC = UUID.randomUUID()
        private val asOf = LocalDate.of(2026, 2, 1)

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'st-a-$admin@test.com', 'Admin'), ('$clerk', 'st-c-$clerk@test.com', 'Clerk')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Stock Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin'), ('$tenantId', '$clerk')")
                grant(admin, "Administrator", "'reports.stock.read', 'factory.costing.read'")
                grant(clerk, "Stock Keeper", "'reports.stock.read'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$loc', '$tenantId', 'FACTORY', 'FY', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                item(itemA, "A", "Alpha", "FINISHED_PRODUCT", landingCost = 100, reorder = 5, unit = unit)
                item(itemB, "B", "Beta", "RAW_MATERIAL", landingCost = 50, reorder = 10, unit = unit)
                item(itemC, "C", "Gamma", "OUTSOURCED", landingCost = 200, reorder = 0, unit = unit)
                // A: 50 in, 20 sold 12 days before asOf → on-hand 30, fresh.
                ledger(itemA, "REFILL", 50, 0, 100, "2026-01-01")
                ledger(itemA, "POS_SALE", 0, 20, 100, "2026-01-20")
                // B: 8 in, 2 sold 92 days before asOf → on-hand 6, slow-moving, below reorder.
                ledger(itemB, "REFILL", 8, 0, 50, "2025-10-01")
                ledger(itemB, "POS_SALE", 0, 2, 50, "2025-11-01")
                // C: 10 in, never sold → on-hand 10, dead.
                ledger(itemC, "REFILL", 10, 0, 200, "2025-06-01")
                // An inventory receipt for C carrying an expiry (near-expiry source alongside showroom batches).
                exec(
                    "insert into stock_receipts (id, tenant_id, location_id, ref, stock_item_id, quantity, unit_cost, expiry_date, " +
                        "input_vat_rate, input_vat_amount, created_by_user_id, created_at) values " +
                        "('${UUID.randomUUID()}', '$tenantId', '$loc', 'DL-1', '$itemC', 10, 200, '2026-02-20', 0, 0, '$admin', now())",
                )
            }
        }

        private fun org.jetbrains.exposed.sql.Transaction.item(id: UUID, code: String, name: String, type: String, landingCost: Int, reorder: Int, unit: UUID) {
            TransactionManager.current().exec(
                "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, status, " +
                    "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                    "('$id', '$tenantId', '$loc', '$type', '$code', '$name', '$unit', $reorder, $landingCost, 'ACTIVE', " +
                    "(select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                    "(select id from isic_sections where code = 'C'), '$admin', now())",
            )
        }

        private fun org.jetbrains.exposed.sql.Transaction.ledger(item: UUID, source: String, qtyIn: Int, qtyOut: Int, unitCost: Int, date: String) {
            TransactionManager.current().exec(
                "insert into stock_ledger_entries (id, tenant_id, location_id, stock_item_id, source_type, source_reference, qty_in, qty_out, unit_cost, entry_date, created_by_user_id, created_at) " +
                    "values ('${UUID.randomUUID()}', '$tenantId', '$loc', '$item', '$source', 'REF', $qtyIn, $qtyOut, $unitCost, '$date', '$admin', now())",
            )
        }

        private fun org.jetbrains.exposed.sql.Transaction.grant(user: UUID, roleName: String, permissionCodes: String) {
            val role = UUID.randomUUID()
            TransactionManager.current().exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenantId', '$roleName', '$admin')")
            TransactionManager.current().exec("insert into role_permissions (role_id, permission_id) select '$role', id from permission_catalog where code in ($permissionCodes)")
            TransactionManager.current().exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$user', '$role', '$admin')")
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), ReportsSettings()), tenantContext)
    private val support = ReportsSupport(tenantContext, actors, permissions, moduleSettings)
    private val service = StockAnalyticsService(support, tx, FakeShowroom())

    /** A's two showroom batches: FIFO consumes the older first against A's on-hand of 30 (batches total 40 → 10 eaten). */
    private inner class FakeShowroom : ShowroomQueries {
        override fun showroom(id: UUID): ShowroomRef = throw UnsupportedOperationException()
        override fun returns(filter: ReturnFilter): List<ReturnLineRef> = emptyList()
        override fun stockEntries(filter: StockEntryFilter): List<StockEntryLineRef> = emptyList()
        override fun batchesWithExpiry(locationIds: Set<UUID>?): List<BatchRef> = listOf(
            BatchRef(itemA, loc, "LOT-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 10), BigDecimal(20)),
            BatchRef(itemA, loc, "LOT-2", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 25), BigDecimal(20)),
        )
    }

    private fun signIn(user: UUID) {
        tenantContext.set(tenantId)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build())
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    @Test
    fun `value groups by item type with shares`() {
        signIn(admin)
        val r = service.analytics("VALUE", asOf, "ITEM_TYPE", null)
        assertThat(r.value!!.map { it.group.key }).containsExactly("FINISHED_PRODUCT", "OUTSOURCED", "RAW_MATERIAL")  // by value desc
        val fp = r.value!!.single { it.group.key == "FINISHED_PRODUCT" }
        assertThat(fp.quantity).isEqualByComparingTo(BigDecimal(30))
        assertThat(fp.value).isEqualByComparingTo(BigDecimal(3000))          // 30 × 100
        assertThat(fp.shareOfValuePct).isEqualByComparingTo(BigDecimal("56.6"))  // 3000 / 5300
    }

    @Test
    fun `value by location totals the on-hand value`() {
        signIn(admin)
        val r = service.analytics("VALUE", asOf, "LOCATION", null)
        assertThat(r.value!!).hasSize(1)
        assertThat(r.value!!.single().value).isEqualByComparingTo(BigDecimal(5300))  // 3000 + 300 + 2000
    }

    @Test
    fun `slow moving lists stale stock and classifies dead`() {
        signIn(admin)
        val r = service.analytics("SLOW_MOVING", asOf, null, null)
        val keys = r.slowMoving!!.map { it.classification }
        assertThat(keys).containsExactlyInAnyOrder("SLOW", "DEAD")
        val dead = r.slowMoving!!.single { it.classification == "DEAD" }
        assertThat(dead.lastOutboundAt).isNull()
        assertThat(dead.daysSinceOutbound).isNull()
        val slow = r.slowMoving!!.single { it.classification == "SLOW" }
        assertThat(slow.daysSinceOutbound).isEqualTo(92L)
        assertThat(slow.value).isEqualByComparingTo(BigDecimal(300))   // 6 × 50
    }

    @Test
    fun `days of cover divides on-hand by average daily sales`() {
        signIn(admin)
        val r = service.analytics("DAYS_OF_COVER", asOf, null, null)
        val a = r.daysOfCover!!.single { it.available.compareTo(BigDecimal(30)) == 0 }
        assertThat(a.daysOfCover).isEqualByComparingTo(BigDecimal("45.0"))   // 30 ÷ (20/30)
        assertThat(a.belowReorder).isFalse()
        val b = r.daysOfCover!!.single { it.available.compareTo(BigDecimal(6)) == 0 }
        assertThat(b.daysOfCover).isNull()                                   // no sales in the last 30 days
        assertThat(b.belowReorder).isTrue()                                  // 6 < reorder 10
    }

    @Test
    fun `near expiry estimates remaining per batch FIFO across both sources`() {
        signIn(admin)
        val r = service.analytics("NEAR_EXPIRY", asOf, null, null)
        // A's older LOT-1 (expiry 02-10) absorbs the 10 consumed → remaining 10; LOT-2 full 20; C's receipt 10.
        assertThat(r.nearExpiry!!.map { it.batch }).containsExactlyInAnyOrder("LOT-1", "LOT-2", "DL-1")
        val lot1 = r.nearExpiry!!.single { it.batch == "LOT-1" }
        assertThat(lot1.estimatedQtyRemaining).isEqualByComparingTo(BigDecimal(10))
        assertThat(lot1.daysToExpiry).isEqualTo(9L)   // 2026-02-01 → 2026-02-10
        assertThat(r.nearExpiry!!.single { it.batch == "LOT-2" }.estimatedQtyRemaining).isEqualByComparingTo(BigDecimal(20))
        val c = r.nearExpiry!!.single { it.batch == "DL-1" }
        assertThat(c.estimatedQtyRemaining).isEqualByComparingTo(BigDecimal(10))
        assertThat(c.daysToExpiry).isEqualTo(19L)     // 2026-02-01 → 2026-02-20
    }

    @Test
    fun `stock value is hidden without cost visibility`() {
        signIn(clerk)
        val value = service.analytics("VALUE", asOf, "ITEM_TYPE", null)
        assertThat(value.costVisible).isFalse()
        assertThat(value.value!!).allSatisfy { assertThat(it.value).isNull(); assertThat(it.shareOfValuePct).isNull() }
        val slow = service.analytics("SLOW_MOVING", asOf, null, null)
        assertThat(slow.slowMoving!!).allSatisfy { assertThat(it.value).isNull() }
    }
}
