package com.zifiq.reports

import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesLineRef
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.service.ReportsSettings
import com.zifiq.reports.service.ReportsSupport
import com.zifiq.reports.service.SalesAnalyticsService
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
 * Reports & Analytics slice 3b: sales analytics groups cashier SPI lines by one or two dimensions and
 * an optional interval, computing quantity/revenue/discount/VAT/COGS/margin, a prior-period comparison
 * and cost masking. Sale lines are faked; permissions are real against Postgres.
 */
class SalesAnalyticsIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val clerk = UUID.randomUUID()
        private val p1 = UUID.randomUUID()
        private val p2 = UUID.randomUUID()
        private val l1 = UUID.randomUUID()
        private val l2 = UUID.randomUUID()
        private val cashier = UUID.randomUUID()
        private val from = LocalDate.of(2026, 1, 1)
        private val to = LocalDate.of(2026, 1, 31)

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'sa-a-$admin@test.com', 'Admin'), ('$clerk', 'sa-c-$clerk@test.com', 'Clerk')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Analytics Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin'), ('$tenantId', '$clerk')")
                grant(admin, "Administrator", "'reports.sales.read', 'factory.costing.read'")
                grant(clerk, "Accounts Personnel", "'reports.sales.read'")
            }
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
    private val service = SalesAnalyticsService(support, tx, FakeSales())

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
    fun `groups by product with revenue, margin and share`() {
        signIn(admin)
        val r = service.analytics(from, to, listOf("PRODUCT"), "NONE", null, null, 20, "NONE")
        assertThat(r.costVisible).isTrue()

        val row1 = r.rows.single { it.group.single().key == p1.toString() }
        assertThat(row1.quantity).isEqualByComparingTo(BigDecimal(5))
        assertThat(row1.transactions).isEqualTo(2)
        assertThat(row1.net).isEqualByComparingTo(BigDecimal(500))
        assertThat(row1.cogs).isEqualByComparingTo(BigDecimal(300))
        assertThat(row1.grossMargin).isEqualByComparingTo(BigDecimal(200))
        assertThat(row1.grossMarginPct).isEqualByComparingTo(BigDecimal("40.0"))
        assertThat(row1.avgSellingPrice).isEqualByComparingTo(BigDecimal(100))
        assertThat(row1.shareOfNetPct).isEqualByComparingTo(BigDecimal("83.3"))

        assertThat(r.totals.net).isEqualByComparingTo(BigDecimal(600))
        assertThat(r.totals.transactions).isEqualTo(3)
        assertThat(r.totals.cogs).isEqualByComparingTo(BigDecimal(340))
    }

    @Test
    fun `groups by two dimensions`() {
        signIn(admin)
        val r = service.analytics(from, to, listOf("LOCATION", "CUSTOMER_TYPE"), "NONE", null, null, 20, "NONE")
        assertThat(r.rows).allSatisfy { assertThat(it.group).hasSize(2) }
        val dealerAtL2 = r.rows.single { it.group[0].key == l2.toString() && it.group[1].key == "AUTHORISED_DEALER" }
        assertThat(dealerAtL2.net).isEqualByComparingTo(BigDecimal(100))
    }

    @Test
    fun `a month interval buckets the rows`() {
        signIn(admin)
        val r = service.analytics(from, to, listOf("PRODUCT"), "MONTH", null, null, 20, "NONE")
        assertThat(r.rows).allSatisfy { assertThat(it.interval).isEqualTo("2026-01") }
    }

    @Test
    fun `a prior-period comparison attaches the previous net and change`() {
        signIn(admin)
        val r = service.analytics(from, to, listOf("PRODUCT"), "NONE", null, null, 20, "PRIOR_PERIOD")
        assertThat(r.comparison).isNotNull()
        val row1 = r.rows.single { it.group.single().key == p1.toString() }
        assertThat(row1.comparison!!.net).isEqualByComparingTo(BigDecimal(100))   // P1 sold ₦100 in the prior period
        assertThat(row1.comparison!!.changePct).isEqualByComparingTo(BigDecimal("400.0"))
    }

    @Test
    fun `cost measures are hidden without cost visibility`() {
        signIn(clerk)
        val r = service.analytics(from, to, listOf("PRODUCT"), "NONE", null, null, 20, "NONE")
        assertThat(r.costVisible).isFalse()
        assertThat(r.rows).allSatisfy {
            assertThat(it.cogs).isNull(); assertThat(it.grossMargin).isNull(); assertThat(it.grossMarginPct).isNull()
        }
        assertThat(r.rows.single { it.group.single().key == p1.toString() }.net).isEqualByComparingTo(BigDecimal(500))
    }

    private inner class FakeSales : SalesQueries {
        override fun firstCompletedSaleDates(customerIds: Collection<UUID>): Map<UUID, LocalDate> = emptyMap()
        override fun linesFor(filter: SalesLineFilter): List<SalesLineRef> {
            val lo = filter.from ?: LocalDate.MIN
            return ALL.filter { !it.saleDate.isBefore(lo) && !it.saleDate.isAfter(filter.completedTo) }
        }

        private val ALL = listOf(
            line("S1", p1, l1, "INDIVIDUAL", "2026-01-05", qty = 2, gross = 250, disc = 50, net = 200, cost = 60),
            line("S2", p1, l1, "INDIVIDUAL", "2026-01-10", qty = 3, gross = 330, disc = 30, net = 300, cost = 60),
            line("S3", p2, l2, "AUTHORISED_DEALER", "2026-01-12", qty = 1, gross = 100, disc = 0, net = 100, cost = 40),
            line("S4", p1, l1, "INDIVIDUAL", "2025-12-15", qty = 1, gross = 110, disc = 10, net = 100, cost = 60),
        )

        private fun line(saleRef: String, product: UUID, loc: UUID, type: String, date: String, qty: Int, gross: Int, disc: Int, net: Int, cost: Int) = SalesLineRef(
            saleId = UUID.nameUUIDFromBytes(saleRef.toByteArray()), saleLineId = UUID.randomUUID(), saleDate = LocalDate.parse(date), saleRef = saleRef,
            customerId = null, customerCode = null, customerName = "C", customerType = type, correspondentId = null,
            stockItemId = product, itemCode = "P", itemName = "Product $product", quantity = BigDecimal(qty), lineNet = BigDecimal(net),
            receiptsConfirmed = true, invoicePaid = true, locationId = loc,
            cashierUserId = cashier, channel = "POS", categoryName = "Drinks",
            grossAmount = BigDecimal(gross), discountTotal = BigDecimal(disc), vatAmount = BigDecimal.ZERO, landingCostAtSale = BigDecimal(cost),
        )
    }
}
