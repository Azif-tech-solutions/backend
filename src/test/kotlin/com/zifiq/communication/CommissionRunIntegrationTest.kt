package com.zifiq.communication

import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.paging.PageRequest
import com.zifiq.communication.dto.ApproveRunRequest
import com.zifiq.communication.dto.CreateRunRequest
import com.zifiq.communication.service.AllocationService
import com.zifiq.communication.service.CommunicationSupport
import com.zifiq.cashier.spi.SalesLineFilter
import com.zifiq.cashier.spi.SalesLineRef
import com.zifiq.cashier.spi.SalesQueries
import com.zifiq.customers.spi.CorrespondentRef
import com.zifiq.customers.spi.Correspondents
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.showroom.spi.ReturnFilter
import com.zifiq.showroom.spi.ReturnLineRef
import com.zifiq.showroom.spi.ShowroomQueries
import com.zifiq.showroom.spi.ShowroomRef
import com.zifiq.showroom.spi.StockEntryFilter
import com.zifiq.showroom.spi.StockEntryLineRef
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Communication slice 2: a monthly run allocates qualifying sales to dealers at the rate in force,
 * nets off returns, excludes pending-confirmation and inactive-rate lines, and locks on approval by
 * someone other than the runner.
 */
class CommissionRunIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val manager = UUID.randomUUID()
        private val factory = UUID.randomUUID()
        private val citrus = UUID.randomUUID()
        private val malt = UUID.randomUUID()
        private val ginger = UUID.randomUUID()
        private val dlr42 = UUID.randomUUID()
        private val dlr17 = UUID.randomUUID()
        private val period: YearMonth = YearMonth.now().minusMonths(2)
        private val periodStart: LocalDate = period.atDay(1)
        private val saleDate: LocalDate = period.atDay(15)

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'cr-$admin@test.com', 'Admin'), ('$manager', 'cr-$manager@test.com', 'Manager')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Run Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'CR-F', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                for ((id, code, name) in listOf(listOf(citrus, "FP-01", "Citrus 50cl"), listOf(malt, "FP-02", "Malt 33cl"), listOf(ginger, "FP-03", "Ginger 50cl"))) {
                    exec(
                        "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, " +
                            "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                            "('$id', '$tenantId', '$factory', 'FINISHED_PRODUCT', '$code', '$name', '$unit', 0, " +
                            "(select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                            "(select id from isic_sections where code = 'C'), '$admin', now())",
                    )
                }
                // Rates in force during the (past) period — seeded directly since the API never backdates.
                fun rate(item: UUID, type: String, r: String, status: String) = exec(
                    "insert into commission_rates (tenant_id, stock_item_id, beneficiary_type, rate_type, rate, status, effective_from, set_by_user_id, created_at) " +
                        "values ('$tenantId', '$item', 'DEALER', '$type', $r, '$status', '$periodStart', '$admin', now())",
                )
                rate(citrus, "PER_UNIT", "45", "ACTIVE")
                rate(malt, "PERCENTAGE", "2", "ACTIVE")
                rate(ginger, "PER_UNIT", "10", "INACTIVE")
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), com.zifiq.communication.service.CommunicationSettings()), tenantContext)
    private val support = CommunicationSupport(tenantContext, actors, PermissionAuthorizer(actors, tx), LocationAccessService(actors, LocationHierarchy()), moduleSettings, DomainEvents(tenantContext, actors))
    private val sequences = DocumentSequenceService(actors, tx)

    private val salesStub = object : SalesQueries {
        override fun linesFor(filter: SalesLineFilter): List<SalesLineRef> = listOf(
            line("L1", dlr42, citrus, "FP-01", BigDecimal("1200"), BigDecimal("5400000"), confirmed = true),
            line("L2", dlr42, malt, "FP-02", BigDecimal("300"), BigDecimal("1200000"), confirmed = false),   // card pending
            line("L3", dlr17, ginger, "FP-03", BigDecimal("80"), BigDecimal("320000"), confirmed = true),
        )
        private fun line(lineId: String, dealer: UUID, item: UUID, code: String, qty: BigDecimal, net: BigDecimal, confirmed: Boolean) = SalesLineRef(
            saleId = UUID.nameUUIDFromBytes(lineId.toByteArray()), saleLineId = UUID.nameUUIDFromBytes("line-$lineId".toByteArray()),
            saleDate = saleDate, saleRef = "TXN-$lineId", customerId = dealer, customerCode = if (dealer == dlr42) "DLR-42" else "DLR-17", customerName = "Dealer",
            customerType = "AUTHORISED_DEALER", correspondentId = null,
            stockItemId = item, itemCode = code, itemName = code, quantity = qty, lineNet = net, receiptsConfirmed = confirmed, invoicePaid = confirmed,
        )
        override fun firstCompletedSaleDates(customerIds: Collection<UUID>): Map<UUID, java.time.LocalDate> = emptyMap()
    }
    private val showroomStub = object : ShowroomQueries {
        override fun showroom(id: UUID): ShowroomRef = error("not used")
        override fun returns(filter: ReturnFilter): List<ReturnLineRef> = listOf(
            ReturnLineRef(
                lineId = UUID.nameUUIDFromBytes("ret-1".toByteArray()), returnId = UUID.randomUUID(), ref = "RET-1", returnType = "SALE_RETURN",
                locationId = factory, customerId = dlr42, customerType = "AUTHORISED_DEALER", returnDate = saleDate, stockItemId = citrus,
                quantity = BigDecimal("20"), unitPrice = BigDecimal("4500"), lineValue = BigDecimal("90000"), reason = "DAMAGED", condition = "DAMAGED", status = "POSTED",
            ),
        )
        override fun stockEntries(filter: StockEntryFilter): List<StockEntryLineRef> = error("not used")
        override fun batchesWithExpiry(locationIds: Set<UUID>?): List<com.zifiq.showroom.spi.BatchRef> = emptyList()
    }
    private val customersStub = object : CustomerDirectory {
        override fun get(id: UUID): CustomerRef = dealer(id, if (id == dlr42) "DLR-42" else "DLR-17", if (id == dlr42) "Dealer 42" else "Dealer 17")
        override fun getForUpdate(id: UUID): CustomerRef = get(id)
        override fun findActiveByPhone(phone: String): CustomerRef? = error("not used")
        override fun search(q: String, limit: Int, types: Set<CustomerType>?): List<CustomerRef> = error("not used")
        private fun dealer(id: UUID, code: String, name: String) = CustomerRef(
            id = id, code = code, type = CustomerType.AUTHORISED_DEALER, isTemporary = false, name = name, contactPerson = null, phone = null, email = null,
            tin = null, businessDescription = null, streetAddress = null, city = null, stateCode = null, lgaCode = null, countryCode = "NG", locationId = null,
            correspondentId = null, correspondentAssigned = false, dealerCategoryId = null, dealerCategoryName = null, dealerDiscountPct = null,
            creditLimit = BigDecimal.ZERO, walletBalance = BigDecimal.ZERO, depositBalance = BigDecimal.ZERO, status = CustomerStatus.ACTIVE,
        )
    }
    private val correspondentsStub = object : Correspondents {
        override fun get(id: UUID): CorrespondentRef = error("not used")
        override fun names(ids: Collection<UUID>): Map<UUID, String> = error("not used")
        override fun creditableFor(customerId: UUID): CorrespondentRef? = error("not used")
        override fun listAssigned(locationId: UUID?): List<CorrespondentRef> = error("not used")
    }
    private val allocation = AllocationService(support, tx, sequences, salesStub, showroomStub, customersStub, correspondentsStub)

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
    fun `a dealer run allocates confirmed sales net of returns, flags pending and inactive, and locks on approval`() {
        signIn(admin)
        // The current month hasn't closed.
        assertThatThrownBy { allocation.createRun(CreateRunRequest("DEALER", YearMonth.now().toString(), null)) }
            .isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }.isEqualTo(ErrorCode.PERIOD_NOT_CLOSED)

        val run = allocation.createRun(CreateRunRequest("DEALER", period.toString(), null))
        // Citrus: (1200 sold − 20 returned) priced at ₦45/unit the dealer actually earned on = ₦53,100.
        assertThat(run.header.commissionPayable).isEqualByComparingTo("53100.00")
        assertThat(run.header.beneficiaries).isEqualTo(1)
        assertThat(run.header.ineligible.lines).isEqualTo(1)                 // Ginger (inactive rate)
        assertThat(run.header.pendingConfirmation.lines).isEqualTo(1)        // Malt (card pending)
        val dlr42Row = run.beneficiaries!!.single { it.beneficiary.code == "DLR-42" }
        assertThat(dlr42Row.commission).isEqualByComparingTo("53100.00")

        val runId = run.header.id
        // The runner can't approve their own run.
        assertThatThrownBy { allocation.approve(runId, ApproveRunRequest(null)) }
            .isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }.isEqualTo(ErrorCode.APPROVER_MUST_DIFFER)

        // A second run for the same period is refused while one is live.
        assertThatThrownBy { allocation.createRun(CreateRunRequest("DEALER", period.toString(), null)) }
            .isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }.isEqualTo(ErrorCode.RUN_EXISTS)

        // A different user approves; the total guard matches.
        signIn(manager)
        val approved = allocation.approve(runId, ApproveRunRequest(BigDecimal("53100.00")))
        assertThat(approved.header.status).isEqualTo("Approved")
        assertThat(allocation.list("DEALER", null, PageRequest.of(0, 50)).items.single().total).isEqualByComparingTo("53100.00")
    }
}
