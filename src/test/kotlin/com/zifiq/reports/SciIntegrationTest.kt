package com.zifiq.reports

import com.zifiq.account.spi.AccountReports
import com.zifiq.account.spi.ExpenseLine
import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesLineRef
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.communication.spi.CommissionFilter
import com.zifiq.communication.spi.CommissionQueries
import com.zifiq.communication.spi.CommissionTotal
import com.zifiq.invoicing.spi.CreditNoteLineView
import com.zifiq.invoicing.spi.InvoiceLineView
import com.zifiq.invoicing.spi.InvoiceQueries
import com.zifiq.invoicing.spi.InvoiceRef
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.service.ReportsSettings
import com.zifiq.reports.service.ReportsSupport
import com.zifiq.reports.service.SciService
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
 * Reports & Analytics slice 2: the SCI is assembled from the owning modules' records (sales, returns,
 * credit notes, the inventory ledger, operating expenses, commissions), and cost sections are masked
 * for a caller without `factory.costing.read`. The cross-module reads are faked; the ledger reads
 * (cost of sales, closing stock) and permission masking run for real against Postgres.
 */
class SciIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val clerk = UUID.randomUUID()
        private val showroom = UUID.randomUUID()
        private val factory = UUID.randomUUID()
        private val item = UUID.randomUUID()
        private val from = LocalDate.of(2026, 1, 1)
        private val to = LocalDate.of(2026, 1, 31)

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'sci-a-$admin@test.com', 'Admin'), ('$clerk', 'sci-c-$clerk@test.com', 'Clerk')")
                exec("insert into tenants (id, name) values ('$tenantId', 'SCI Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin'), ('$tenantId', '$clerk')")
                grant(admin, "Administrator", "'reports.sci.read', 'factory.costing.read'")
                grant(clerk, "Accounts Personnel", "'reports.sci.read'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$showroom', '$tenantId', 'SHOWROOM', 'SR', 'Main store', '$admin'), ('$factory', '$tenantId', 'FACTORY', 'FY', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                exec(
                    "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, " +
                        "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                        "('$item', '$tenantId', '$factory', 'FINISHED_PRODUCT', 'FP-01', 'Citrus', '$unit', 0, 100, " +
                        "(select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                        "(select id from isic_sections where code = 'C'), '$admin', now())",
                )
                // Ledger: a refill in (50), a sale out (10 @ 100), a sale void back in (2 @ 100).
                ledger("REFILL", qtyIn = 50, qtyOut = 0, unitCost = 100, date = "2026-01-05")
                ledger("POS_SALE", qtyIn = 0, qtyOut = 10, unitCost = 100, date = "2026-01-10")
                ledger("SALE_VOID", qtyIn = 2, qtyOut = 0, unitCost = 100, date = "2026-01-12")
            }
        }

        private fun org.jetbrains.exposed.sql.Transaction.ledger(source: String, qtyIn: Int, qtyOut: Int, unitCost: Int, date: String) {
            TransactionManager.current().exec(
                "insert into stock_ledger_entries (id, tenant_id, location_id, stock_item_id, source_type, source_reference, qty_in, qty_out, unit_cost, entry_date, created_by_user_id, created_at) " +
                    "values ('${UUID.randomUUID()}', '$tenantId', '$factory', '$item', '$source', 'REF', $qtyIn, $qtyOut, $unitCost, '$date', '$admin', now())",
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
    private val sci = SciService(support, tx, FakeSales(), FakeShowroom(), FakeInvoices(), FakeExpenses(), FakeCommissions())

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
    fun `the SCI is assembled from each module's records for a cost-visible caller`() {
        signIn(admin)
        val r = sci.sci("COMPANY", null, from, to, "NONE")
        assertThat(r.costVisible).isTrue()
        assertThat(r.columns).containsExactly("This period")
        assertThat(r.stats["netProfit"]).isNotNull()

        val revenue = r.sections.single { it.title == "Revenue" }
        assertThat(revenue.lines.map { it.label }).containsExactly(
            "Showroom sales", "Dealer / distributor sales", "Factory direct sales", "Less: sales returns", "Less: credit notes",
        )
        assertThat(revenue.lines[0].values[0]).isEqualByComparingTo(BigDecimal(500))   // showroom, non-dealer
        assertThat(revenue.lines[1].values[0]).isEqualByComparingTo(BigDecimal(300))   // dealer anywhere
        assertThat(revenue.lines[2].values[0]).isEqualByComparingTo(BigDecimal(200))   // factory, non-dealer
        assertThat(revenue.subtotal!!.values[0]).isEqualByComparingTo(BigDecimal(920)) // 1000 − 50 returns − 30 credit

        val cost = r.sections.single { it.title == "Cost of sales" }
        assertThat(cost.lines[0].values[0]).isEqualByComparingTo(BigDecimal(800))      // (10−2) × 100
        assertThat(cost.subtotal!!.values[0]).isEqualByComparingTo(BigDecimal(120))    // gross profit 920 − 800

        val opex = r.sections.single { it.title == "Operating expenses" }
        assertThat(opex.subtotal!!.values[0]).isEqualByComparingTo(BigDecimal(200))    // 100 + 40 + 60 commissions
        assertThat(r.result.values[0]).isEqualByComparingTo(BigDecimal(-80))           // net profit 120 − 200

        val memo = r.sections.single { it.title == "Memorandum" }
        assertThat(memo.lines[0].values[0]).isEqualByComparingTo(BigDecimal(4200))     // (50 − 10 + 2) × 100
    }

    @Test
    fun `cost sections are masked without factory cost visibility`() {
        signIn(clerk)
        val r = sci.sci("COMPANY", null, from, to, "NONE")
        assertThat(r.costVisible).isFalse()

        assertThat(r.sections.single { it.title == "Revenue" }.subtotal!!.values[0]).isEqualByComparingTo(BigDecimal(920))
        val cost = r.sections.single { it.title == "Cost of sales" }
        assertThat(cost.lines[0].values[0]).isEqualByComparingTo(BigDecimal.ZERO)     // zeroed, not hidden
        assertThat(cost.subtotal!!.values[0]).isNull()                       // gross profit withheld
        assertThat(r.result.values[0]).isNull()                              // net profit withheld
        assertThat(r.sections.single { it.title == "Memorandum" }.lines[0].values[0]).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `a prior-period comparison adds a second column and a change percentage`() {
        signIn(admin)
        val r = sci.sci("COMPANY", null, from, to, "PRIOR_PERIOD")
        assertThat(r.comparison).isNotNull()
        assertThat(r.columns).hasSize(2)
        val revenue = r.sections.single { it.title == "Revenue" }
        assertThat(revenue.subtotal!!.values).hasSize(2)
    }

    // ---- faked cross-module reads ----

    private inner class FakeSales : SalesQueries {
        override fun linesFor(filter: SalesLineFilter): List<SalesLineRef> = listOf(
            saleLine(showroom, "INDIVIDUAL", 500),
            saleLine(factory, "AUTHORISED_DEALER", 300),
            saleLine(factory, "INDIVIDUAL", 200),
        )

        override fun firstCompletedSaleDates(customerIds: Collection<UUID>): Map<UUID, LocalDate> = emptyMap()

        private fun saleLine(location: UUID, type: String, net: Int) = SalesLineRef(
            saleId = UUID.randomUUID(), saleLineId = UUID.randomUUID(), saleDate = LocalDate.of(2026, 1, 15), saleRef = "S",
            customerId = null, customerCode = null, customerName = "C", customerType = type, correspondentId = null,
            stockItemId = item, itemCode = "FP-01", itemName = "Citrus", quantity = BigDecimal.ONE, lineNet = BigDecimal(net),
            receiptsConfirmed = true, invoicePaid = true, locationId = location,
        )
    }

    private inner class FakeShowroom : ShowroomQueries {
        override fun showroom(id: UUID): ShowroomRef = throw UnsupportedOperationException()
        override fun returns(filter: ReturnFilter): List<ReturnLineRef> = listOf(
            ReturnLineRef(
                lineId = UUID.randomUUID(), returnId = UUID.randomUUID(), ref = "R", returnType = "NON_DEALER", locationId = showroom,
                customerId = UUID.randomUUID(), customerType = "INDIVIDUAL", returnDate = LocalDate.of(2026, 1, 20), stockItemId = item,
                quantity = BigDecimal.ONE, unitPrice = BigDecimal(50), lineValue = BigDecimal(50), reason = "X", condition = "GOOD", status = "POSTED",
            ),
        )
        override fun stockEntries(filter: StockEntryFilter): List<StockEntryLineRef> = emptyList()
    }

    private inner class FakeInvoices : InvoiceQueries {
        override fun byNumber(number: String): InvoiceRef? = null
        override fun bySource(sourceType: String, sourceId: UUID): InvoiceRef? = null
        override fun linesOf(invoiceId: UUID): List<InvoiceLineView> = emptyList()
        override fun creditNoteLines(locationId: UUID?, from: LocalDate, to: LocalDate): List<CreditNoteLineView> = listOf(
            CreditNoteLineView(
                date = LocalDate.of(2026, 1, 22), creditNoteNumber = "CN-1", invoiceNumber = "INV-1", locationId = showroom,
                buyerName = "C", buyerTin = null, lineNet = BigDecimal(30), vatRate = BigDecimal.ZERO, vatAmount = BigDecimal.ZERO,
            ),
        )
        override fun issuedLineCount(stockItemId: UUID): Int = 0
    }

    private inner class FakeExpenses : AccountReports {
        override fun operatingExpenses(from: LocalDate, to: LocalDate, locationIds: Set<UUID>?): List<ExpenseLine> = listOf(
            ExpenseLine(UUID.randomUUID(), "RENT", "Rent", BigDecimal(100)),
            ExpenseLine(UUID.randomUUID(), "POW", "Power", BigDecimal(40)),
        )
    }

    private inner class FakeCommissions : CommissionQueries {
        override fun allocatedFor(filter: CommissionFilter): List<CommissionTotal> = listOf(
            CommissionTotal(periodMonth = LocalDate.of(2026, 1, 1), beneficiaryType = "CORRESPONDENT", locationId = null, earned = BigDecimal(60), paid = BigDecimal.ZERO),
        )
    }
}
