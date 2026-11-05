package com.zifiq.communication

import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesLineRef
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.communication.service.CommissionQueryService
import com.zifiq.communication.service.CommunicationSettings
import com.zifiq.communication.service.CommunicationSupport
import com.zifiq.communication.service.ReportService
import com.zifiq.communication.spi.CommissionFilter
import com.zifiq.customers.spi.CorrespondentRef
import com.zifiq.customers.spi.Correspondents
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Communication slice 4: the Correspondents Report (rep activity, "brought in" detection, pending-
 * confirmation reconciliation) and the CommissionQueries SPI (allocated per approved-run period).
 */
class CommissionReportsIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val rep = UUID.randomUUID()
        private val showroom = UUID.randomUUID()
        private val custA = UUID.randomUUID()
        private val custB = UUID.randomUUID()
        private val runId = UUID.randomUUID()
        private val dlr = UUID.randomUUID()
        private val from: LocalDate = LocalDate.now().minusMonths(1).withDayOfMonth(1)
        private val to: LocalDate = from.plusMonths(1).minusDays(1)

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'rp-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Report Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                // An approved DEALER run, for the CommissionQueries SPI.
                exec(
                    "insert into commission_runs (id, tenant_id, ref, beneficiary_type, period_month, status, total_commission, beneficiary_count, " +
                        "eligible_lines, ineligible_lines, pending_confirmation_lines, calculated_at, run_by_user_id, approved_by_user_id, approved_at, created_at) values " +
                        "('$runId', '$tenantId', 'CA-1', 'DEALER', '$from', 'APPROVED', 10000.00, 1, 1, 0, 0, now(), '$admin', '$admin', now(), now())",
                )
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), CommunicationSettings()), tenantContext)
    private val support = CommunicationSupport(tenantContext, actors, PermissionAuthorizer(actors, tx), LocationAccessService(actors, LocationHierarchy()), moduleSettings, DomainEvents(tenantContext, actors))

    private val salesStub = object : SalesQueries {
        override fun linesFor(filter: SalesLineFilter): List<SalesLineRef> = listOf(
            line("S1", custA, "CU-A", "Acme", qty = BigDecimal("10"), net = BigDecimal("40000"), confirmed = true),
            line("S2", custA, "CU-A", "Acme", qty = BigDecimal("5"), net = BigDecimal("20000"), confirmed = false),  // pending
            line("S3", custB, "CU-B", "Beta", qty = BigDecimal("8"), net = BigDecimal("32000"), confirmed = true),
        )
        override fun firstCompletedSaleDates(customerIds: Collection<UUID>): Map<UUID, LocalDate> =
            mapOf(custA to from.plusDays(2), custB to from.minusMonths(6))   // A is new in range, B is an old customer
        private fun line(s: String, cust: UUID, code: String, name: String, qty: BigDecimal, net: BigDecimal, confirmed: Boolean) = SalesLineRef(
            saleId = UUID.nameUUIDFromBytes(s.toByteArray()), saleLineId = UUID.nameUUIDFromBytes("l$s".toByteArray()), saleDate = from.plusDays(5),
            saleRef = "TXN-$s", customerId = cust, customerCode = code, customerName = name, customerType = "ORDINARY_CUSTOMER", correspondentId = rep,
            stockItemId = UUID.randomUUID(), itemCode = "FP-1", itemName = "Drink", quantity = qty, lineNet = net, receiptsConfirmed = confirmed, invoicePaid = confirmed,
        )
    }
    private val correspondentsStub = object : Correspondents {
        override fun get(id: UUID): CorrespondentRef = CorrespondentRef(rep, "COR-1", "Bisi Rep", "0803", null, "Lagos", showroom, "ASSIGNED", null)
        override fun names(ids: Collection<UUID>): Map<UUID, String> = mapOf(rep to "Bisi Rep")
        override fun creditableFor(customerId: UUID): CorrespondentRef? = null
        override fun listAssigned(locationId: UUID?): List<CorrespondentRef> = emptyList()
    }
    private val reports = ReportService(support, tx, salesStub, correspondentsStub)
    private val commissionQueries = CommissionQueryService(support, tx)

    private fun signIn() {
        tenantContext.set(tenantId)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").subject(admin.toString()).build())
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    @Test
    fun `correspondents report aggregates rep activity and the SPI totals allocated commission`() {
        signIn()
        val repReport = reports.correspondents(null, from, to, null, "rep")
        val row = repReport.reps!!.single()
        assertThat(row.repCode).isEqualTo("COR-1")
        assertThat(row.customers).isEqualTo(2)
        assertThat(row.newCustomers).isEqualTo(1)                       // only Acme's first sale is in range
        assertThat(row.transactions).isEqualTo(3)
        assertThat(row.revenue).isEqualByComparingTo("92000")           // 40000 + 20000 + 32000
        assertThat(row.pendingConfirmationValue).isEqualByComparingTo("20000")  // S2

        val customerView = reports.correspondents(null, from, to, null, "customer")
        assertThat(customerView.customers!!.single { it.customerCode == "CU-A" }.newCustomer).isTrue()
        assertThat(customerView.customers!!.single { it.customerCode == "CU-B" }.newCustomer).isFalse()

        // The SPI reports the approved run's allocated commission.
        val totals = commissionQueries.allocatedFor(CommissionFilter(beneficiaryType = "DEALER"))
        assertThat(totals.single().earned).isEqualByComparingTo("10000.00")
        assertThat(totals.single().paid).isEqualByComparingTo("0.00")
    }
}
