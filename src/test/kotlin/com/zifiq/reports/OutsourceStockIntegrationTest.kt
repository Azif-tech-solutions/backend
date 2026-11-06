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
import com.zifiq.reports.service.OutsourceStockService
import com.zifiq.reports.service.ReportsSettings
import com.zifiq.reports.service.ReportsSupport
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
 * Reports & Analytics slice 3a: the Out-Source Stock Report attributes lifetime sales to outsource
 * receipt lines FIFO per showroom, computes sell-through / realised margin, and masks cost figures
 * without `factory.costing.read`. Receipt lines and sales are faked; item status and permissions are
 * real against Postgres.
 */
class OutsourceStockIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val clerk = UUID.randomUUID()
        private val store = UUID.randomUUID()
        private val vendor = UUID.randomUUID()
        private val itemA = UUID.randomUUID()
        private val itemB = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'os-a-$admin@test.com', 'Admin'), ('$clerk', 'os-c-$clerk@test.com', 'Clerk')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Outsource Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin'), ('$tenantId', '$clerk')")
                grant(admin, "Administrator", "'reports.outsource.read', 'factory.costing.read'")
                grant(clerk, "Stock Keeper", "'reports.outsource.read'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$store', '$tenantId', 'SHOWROOM', 'SR', 'Main store', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                item(itemA, "OS-A", "Imported blender", "ACTIVE", unit)
                item(itemB, "OS-B", "Imported kettle", "SUSPENDED", unit)
            }
        }

        private fun org.jetbrains.exposed.sql.Transaction.item(id: UUID, code: String, name: String, status: String, unit: UUID) {
            TransactionManager.current().exec(
                "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, status, " +
                    "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                    "('$id', '$tenantId', '$store', 'OUTSOURCED', '$code', '$name', '$unit', 0, '$status', " +
                    "(select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                    "(select id from isic_sections where code = 'C'), '$admin', now())",
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
    private val service = OutsourceStockService(support, tx, FakeShowroom(), FakeSales())

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
    fun `sales are attributed to receipt lines FIFO with realised margin`() {
        signIn(admin)
        val r = service.report(null, null, null, null, null)
        assertThat(r.costVisible).isTrue()
        assertThat(r.rows).hasSize(3)

        val r1 = r.rows.single { it.ref == "R1" }
        assertThat(r1.qtySold).isEqualByComparingTo(BigDecimal(10))   // oldest line absorbed first
        assertThat(r1.remaining).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(r1.status).isEqualTo("SOLD_OUT")
        assertThat(r1.sellingPrice).isEqualByComparingTo(BigDecimal(200))   // 3000 net / 15 units
        assertThat(r1.marginRealised).isEqualByComparingTo(BigDecimal(1000)) // 10×200 − 10×100

        val r2 = r.rows.single { it.ref == "R2" }
        assertThat(r2.qtySold).isEqualByComparingTo(BigDecimal(5))
        assertThat(r2.remaining).isEqualByComparingTo(BigDecimal(5))
        assertThat(r2.status).isEqualTo("IN_STOCK")
        assertThat(r2.marginRealised).isEqualByComparingTo(BigDecimal(400))  // 5×200 − 5×120

        val r3 = r.rows.single { it.ref == "R3" }
        assertThat(r3.status).isEqualTo("SUSPENDED")                         // item suspended overrides
        assertThat(r3.qtySold).isEqualByComparingTo(BigDecimal.ZERO)

        assertThat(r.stats["costOfGoodsSourced"] as BigDecimal).isEqualByComparingTo(BigDecimal(2400))
        assertThat(r.stats["revenueRealised"] as BigDecimal).isEqualByComparingTo(BigDecimal(3000))
        assertThat(r.stats["marginRealised"] as BigDecimal).isEqualByComparingTo(BigDecimal(1400))
        assertThat(r.stats["unitsUnsold"] as BigDecimal).isEqualByComparingTo(BigDecimal(9))
    }

    @Test
    fun `the status filter narrows the rows`() {
        signIn(admin)
        val r = service.report(null, null, "IN_STOCK", null, null)
        assertThat(r.rows.map { it.ref }).containsExactly("R2")
    }

    @Test
    fun `cost columns are hidden without cost visibility`() {
        signIn(clerk)
        val r = service.report(null, null, null, null, null)
        assertThat(r.costVisible).isFalse()
        assertThat(r.rows).allSatisfy { assertThat(it.unitCost).isNull(); assertThat(it.marginRealised).isNull() }
        assertThat(r.stats["costOfGoodsSourced"]).isNull()
        assertThat(r.stats["marginRealised"]).isNull()
        assertThat(r.stats["marginPctOfRevenue"]).isNull()
        assertThat(r.stats["revenueRealised"] as BigDecimal).isEqualByComparingTo(BigDecimal(3000))  // revenue stays visible
    }

    private inner class FakeShowroom : ShowroomQueries {
        override fun showroom(id: UUID): ShowroomRef = throw UnsupportedOperationException()
        override fun returns(filter: ReturnFilter): List<ReturnLineRef> = emptyList()
        override fun batchesWithExpiry(locationIds: Set<UUID>?): List<com.zifiq.showroom.spi.BatchRef> = emptyList()
        override fun stockEntries(filter: StockEntryFilter): List<StockEntryLineRef> = listOf(
            line("R1", itemA, "2026-01-05", 10, 100),
            line("R2", itemA, "2026-01-20", 10, 120),
            line("R3", itemB, "2026-01-10", 4, 50),
        )

        private fun line(ref: String, item: UUID, date: String, qty: Int, unitCost: Int) = StockEntryLineRef(
            entryId = UUID.randomUUID(), ref = ref, kind = "OUTSOURCE_RECEIPT", locationId = store, receivedDate = LocalDate.parse(date),
            stockItemId = item, quantity = BigDecimal(qty), unitCost = BigDecimal(unitCost), lineCost = BigDecimal(qty * unitCost),
            inputVatRate = BigDecimal.ZERO, inputVatAmount = BigDecimal.ZERO, supplierId = null, supplierName = null, manufacturerId = vendor,
        )
    }

    private inner class FakeSales : SalesQueries {
        override fun firstCompletedSaleDates(customerIds: Collection<UUID>): Map<UUID, LocalDate> = emptyMap()
        override fun linesFor(filter: SalesLineFilter): List<SalesLineRef> = listOf(
            saleLine(5, 1000), saleLine(10, 2000),   // item A at the store: 15 units, ₦3000 net
        )

        private fun saleLine(qty: Int, net: Int) = SalesLineRef(
            saleId = UUID.randomUUID(), saleLineId = UUID.randomUUID(), saleDate = LocalDate.of(2026, 1, 25), saleRef = "S",
            customerId = null, customerCode = null, customerName = "C", customerType = "INDIVIDUAL", correspondentId = null,
            stockItemId = itemA, itemCode = "OS-A", itemName = "Imported blender", quantity = BigDecimal(qty), lineNet = BigDecimal(net),
            receiptsConfirmed = true, invoicePaid = true, locationId = store,
        )
    }
}
