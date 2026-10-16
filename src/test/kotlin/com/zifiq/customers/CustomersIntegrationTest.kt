package com.zifiq.customers

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.customers.dto.CorrespondentRequest
import com.zifiq.customers.dto.CustomerRequest
import com.zifiq.customers.dto.CustomerStatusRequest
import com.zifiq.customers.dto.ReclassificationReason
import com.zifiq.customers.dto.ReclassificationRequest
import com.zifiq.customers.dto.ReversalRequest
import com.zifiq.customers.dto.WalletAdjustmentRequest
import com.zifiq.customers.service.CorrespondentService
import com.zifiq.customers.service.CustomerLedgerService
import com.zifiq.customers.service.CustomerListService
import com.zifiq.customers.service.CustomerService
import com.zifiq.customers.service.CustomerSpiService
import com.zifiq.customers.service.CustomerSupport
import com.zifiq.customers.service.CustomersSettings
import com.zifiq.customers.service.DealerCategoryService
import com.zifiq.customers.service.ReclassificationService
import com.zifiq.customers.service.WalletLedgerService
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerDirection
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.customfields.CustomFieldValidator
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.Recipients
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.assertj.core.api.AbstractObjectAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/** Customers & Distributors (docs/04 §11) against real PostgreSQL with RLS. */
class CustomersIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val otherTenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val repUser: UUID = UUID.randomUUID()
        private val cashier: UUID = UUID.randomUUID()
        private val otherAdmin: UUID = UUID.randomUUID()
        private val ikeja: UUID = UUID.randomUUID()
        private val lekki: UUID = UUID.randomUUID()
        private val otherShowroom: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, repUser, cashier, otherAdmin).forEach {
                    exec("insert into app_users (id, email, display_name) values ('$it', 'cu-$it@test.com', 'User $it')")
                }
                exec("insert into tenants (id, name) values ('$tenantId', 'Customers A'), ('$otherTenantId', 'Customers B')")
            }
            seedTenant(tenantId, admin, mapOf(repUser to listOf("customers.read.own"), cashier to listOf("customers.read", "customers.manage")),
                listOf(ikeja to "SHR-1", lekki to "SHR-2"))
            seedTenant(otherTenantId, otherAdmin, emptyMap(), listOf(otherShowroom to "SHR-1"))
            // The cashier works at Ikeja only.
            inTenant(tenantId) {
                exec("insert into user_location_assignments (tenant_id, user_id, location_id, access_scope, assigned_by_user_id) values ('$tenantId', '$cashier', '$ikeja', 'SINGLE_LOCATION', '$admin')")
            }
        }

        private fun inTenant(tenant: UUID, block: org.jetbrains.exposed.sql.Transaction.() -> Unit) = transaction(database) {
            exec("select set_config('app.tenant_id', '$tenant', true)")
            block()
        }

        /** An Administrator holding every permission, extra users with the given permissions, and showrooms. */
        private fun seedTenant(tenant: UUID, adminUser: UUID, others: Map<UUID, List<String>>, showrooms: List<Pair<UUID, String>>) = inTenant(tenant) {
            (others.keys + adminUser).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenant', '$it')") }
            val adminRole = UUID.randomUUID()
            exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$adminRole', '$tenant', 'Administrator', '$adminUser')")
            exec("insert into role_permissions (role_id, permission_id) select '$adminRole', id from permission_catalog")
            exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenant', '$adminUser', '$adminRole', '$adminUser')")
            others.forEach { (user, permissions) ->
                val role = UUID.randomUUID()
                exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenant', 'Role $user', '$adminUser')")
                exec("insert into role_permissions (role_id, permission_id) select '$role', id from permission_catalog where code in (${permissions.joinToString { "'$it'" }})")
                exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenant', '$user', '$role', '$adminUser')")
            }
            showrooms.forEach { (id, code) ->
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$id', '$tenant', 'SHOWROOM', '$code', 'Showroom $code', '$adminUser')")
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val support = CustomerSupport(actors, PermissionAuthorizer(actors, tx), tenantContext)
    private val locationAccess = LocationAccessService(actors)
    private val events = DomainEvents(tenantContext, actors)
    private val notify = Notify(tenantContext, Recipients(tenantContext))
    private val sequences = DocumentSequenceService(actors, tx)
    private val settings = ModuleSettings(listOf(PlatformSettings(), CustomersSettings()), tenantContext)
    private val customFields = CustomFieldValidator(tenantContext)
    private val customers = CustomerService(support, tx, locationAccess, customFields, sequences, events, notify)
    private val lists = CustomerListService(support, tx, locationAccess, customFields, settings)
    private val wallet = WalletLedgerService(tenantContext, actors, events)
    private val ledger = CustomerLedgerService(support, tx, customers, wallet, sequences, settings, notify)
    private val spi = CustomerSpiService(support, tenantContext, lists, sequences, settings, events)
    private val categories = DealerCategoryService(support, tx, events)
    private val correspondents = CorrespondentService(support, tx, tenantContext, customers, locationAccess, sequences, settings, events, notify)
    private val reclassifications = ReclassificationService(support, tx, tenantContext, database, customers, locationAccess, settings, events, notify)

    init {
        // Default categories, as TenantProvisioner gives every new tenant (idempotent per test class).
        listOf(tenantId to admin, otherTenantId to otherAdmin).forEach { (tenant, user) ->
            val seeded = tx.executeForTenant(tenant) {
                TransactionManager.current().exec("select count(*) from dealer_categories") { rs -> rs.next(); rs.getLong(1) }!!
            }
            if (seeded == 0L) tx.executeForTenant(tenant) { categories.provision(tenant, user) }
        }
    }

    private fun signIn(user: UUID, tenant: UUID = tenantId) {
        tenantContext.set(tenant)
        val jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).claim("email", "$user@test.com").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    /** "Today" for the tenant (Africa/Lagos, the default business timezone): the JVM's UTC date is a day behind after 23:00 UTC. */
    private fun tenantToday(): LocalDate = LocalDate.now(java.time.ZoneId.of("Africa/Lagos"))

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    private fun gold(): UUID = categories.list(null, "gold").single().id

    private fun dealer(name: String, phone: String = "0803 412 7781", location: UUID = ikeja) = customers.register(
        CustomerRequest(
            type = CustomerType.AUTHORISED_DEALER, name = name, contactPerson = "Tunde Okafor", phone = phone,
            streetAddress = "14 Ikorodu Rd", stateCode = "NG-LA", locationId = location, dealerCategoryId = gold(),
        ),
    )

    private fun ordinary(name: String, phone: String, location: UUID? = ikeja, correspondentId: UUID? = null) = customers.register(
        CustomerRequest(type = CustomerType.ORDINARY_CUSTOMER, name = name, phone = phone, locationId = location, correspondentId = correspondentId),
    )

    private fun posting(customerId: UUID, type: LedgerEntryType, amount: String, reference: String, balance: BalanceType = BalanceType.WALLET) = LedgerPosting(
        customerId = customerId, balanceType = balance, entryType = type, amount = BigDecimal(amount), reference = reference,
        description = "$type $reference", paymentMethod = "Wallet", sourceModule = "test", sourceType = "TEST", sourceId = null, locationId = ikeja,
    )

    @Test
    fun `registering a dealer applies the category's suggested limit, a DLR code and a normalised phone`() {
        signIn(admin)
        val detail = dealer("Okafor & Sons Distribution")
        assertThat(detail.code).startsWith("DLR-")
        assertThat(detail.creditLimit).isEqualByComparingTo("1500000")
        assertThat(detail.dealerCategory?.name).isEqualTo("Gold")
        val e164 = tx.execute {
            TransactionManager.current().exec("select phone_e164 from customers where id = '${detail.id}'") { rs -> rs.next(); rs.getString(1) }
        }
        assertThat(e164).isEqualTo("+2348034127781")

        failsWith { dealer("okafor & sons distribution", phone = "08030000000") }.isEqualTo(ErrorCode.DUPLICATE_NAME)
        failsWith { customers.register(CustomerRequest(type = CustomerType.AUTHORISED_DEALER, name = "No Category", contactPerson = "X", phone = "0803", streetAddress = "1", stateCode = "NG-LA")) }
            .isEqualTo(ErrorCode.CATEGORY_REQUIRED_FOR_DEALER)
        failsWith { customers.register(CustomerRequest(type = CustomerType.ORDINARY_CUSTOMER, name = "No Phone")) }.isEqualTo(ErrorCode.PHONE_REQUIRED)
        failsWith { customers.register(CustomerRequest(type = CustomerType.NO_CREDIT_WALKIN, name = "Walk In", creditLimit = BigDecimal.TEN)) }
            .isEqualTo(ErrorCode.CREDIT_LIMIT_NOT_ALLOWED)
        failsWith { customers.register(CustomerRequest(type = CustomerType.ORDINARY_CUSTOMER, name = "Bad LGA", phone = "08031112222", stateCode = "NG-LA", lgaCode = "NOPE")) }
            .isEqualTo(ErrorCode.INVALID_LGA_CODE)
    }

    @Test
    fun `cashiers cannot register dealers or grant credit`() {
        signIn(cashier)
        failsWith { dealer("Cashier Dealer Ltd") }.isEqualTo(ErrorCode.FORBIDDEN)
        failsWith { customers.register(CustomerRequest(type = CustomerType.ORDINARY_CUSTOMER, name = "Credit Seeker", phone = "08035550000", locationId = ikeja, creditLimit = BigDecimal("1000"))) }
            .isEqualTo(ErrorCode.FORBIDDEN)
        assertThat(ordinary("Cash Buyer", "08035550001").creditLimit).isEqualByComparingTo("0")
        // Ikeja-only: registering a customer homed at Lekki is out of scope.
        failsWith { ordinary("Lekki Buyer", "08035550002", location = lekki) }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
    }

    @Test
    fun `two concurrent debits never overdraw a wallet`() {
        signIn(admin)
        val id = dealer("Concurrent Traders").id
        tx.execute { wallet.credit(posting(id, LedgerEntryType.FUNDING, "250000", "WF-1")) }

        val start = CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Result<Unit>>()
        val workers = (1..2).map { n ->
            thread {
                signIn(admin)
                start.await(10, TimeUnit.SECONDS)
                results += runCatching { tx.execute { wallet.debit(posting(id, LedgerEntryType.PURCHASE_DEBIT, "150000", "TXN-$n")) }; Unit }
                signOut()
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat((results.single { it.isFailure }.exceptionOrNull() as ApiException).code).isEqualTo(ErrorCode.INSUFFICIENT_WALLET_BALANCE)
        val detail = customers.get(id)
        assertThat(detail.walletBalance).isEqualByComparingTo("100000")
        val page = ledger.ledger(id, BalanceType.WALLET, ListSearchRequest())
        assertThat(page.closingBalance).isEqualByComparingTo("100000")
        assertThat(page.totalCredits.subtract(page.totalDebits)).isEqualByComparingTo("100000")
    }

    @Test
    fun `a temporary walk-in is removed once its wallet is spent and a later return creates a fresh record`() {
        signIn(admin)
        val first = tx.execute { spi.findOrCreate("Ada", "0809 111 2222", ikeja) }
        assertThat(first.isTemporary).isTrue()
        assertThat(tx.execute { spi.findOrCreate("Ada again", "+2348091112222", ikeja) }.id).isEqualTo(first.id)

        tx.execute { wallet.credit(posting(first.id, LedgerEntryType.RETURN_CREDIT, "9000", "RT-458")) }
        tx.execute { wallet.debit(posting(first.id, LedgerEntryType.PURCHASE_DEBIT, "9000", "TXN-4502")) }
        assertThat(customers.get(first.id).status).isEqualTo(CustomerStatus.REMOVED)

        val second = tx.execute { spi.findOrCreate("Ada", "08091112222", ikeja) }
        assertThat(second.id).isNotEqualTo(first.id)
        // Removed records stay out of default lists and pickers.
        val listed = lists.list(ListSearchRequest(params = mapOf("isTemporary" to listOf("true")))).items.map { it.id }
        assertThat(listed).contains(second.id).doesNotContain(first.id)
    }

    @Test
    fun `reclassification applies now or later, never moves the wallet, and guards deposits`() {
        signIn(admin)
        val customer = ordinary("Reclass Candidate", "08036660000")
        tx.execute { wallet.credit(posting(customer.id, LedgerEntryType.RETURN_CREDIT, "4000", "RT-1")) }

        val promoted = reclassifications.reclassify(customer.id, ReclassificationRequest(CustomerType.SPECIAL_CUSTOMER, ReclassificationReason.MET_PURCHASE_VOLUME, null, tenantToday()))
        assertThat(promoted.status).isEqualTo("APPLIED")
        assertThat(promoted.direction).isEqualTo("PROMOTION")
        assertThat(customers.get(customer.id).type).isEqualTo(CustomerType.SPECIAL_CUSTOMER)
        assertThat(customers.get(customer.id).walletBalance).isEqualByComparingTo("4000")

        failsWith { reclassifications.reclassify(customer.id, ReclassificationRequest(CustomerType.SPECIAL_CUSTOMER, ReclassificationReason.MANAGEMENT_DECISION, null, tenantToday())) }
            .isEqualTo(ErrorCode.RECLASSIFICATION_SAME_TYPE)

        tx.execute { wallet.credit(posting(customer.id, LedgerEntryType.FUNDING, "5000", "DP-1", BalanceType.DEPOSIT)) }
        failsWith { reclassifications.reclassify(customer.id, ReclassificationRequest(CustomerType.ORDINARY_CUSTOMER, ReclassificationReason.PAYMENT_ARREARS, null, tenantToday())) }
            .isEqualTo(ErrorCode.DEPOSIT_MUST_BE_SETTLED)
        tx.execute { wallet.debit(posting(customer.id, LedgerEntryType.WITHDRAWAL_DEBIT, "5000", "WW-1", BalanceType.DEPOSIT)) }

        val scheduled = reclassifications.reclassify(
            customer.id,
            ReclassificationRequest(CustomerType.AUTHORISED_DEALER, ReclassificationReason.SUSTAINED_SALES_PERFORMANCE, null, tenantToday().plusDays(3), toDealerCategoryId = gold()),
        )
        assertThat(scheduled.status).isEqualTo("SCHEDULED")
        failsWith { reclassifications.reclassify(customer.id, ReclassificationRequest(CustomerType.ORDINARY_CUSTOMER, ReclassificationReason.MANAGEMENT_DECISION, null, tenantToday().plusDays(5))) }
            .isEqualTo(ErrorCode.RECLASSIFICATION_PENDING)
        assertThat(reclassifications.cancel(customer.id, scheduled.id).status).isEqualTo("CANCELLED")
        assertThat(customers.get(customer.id).type).isEqualTo(CustomerType.SPECIAL_CUSTOMER)
    }

    @Test
    fun `a due scheduled reclassification is applied by the job`() {
        signIn(admin)
        val customer = ordinary("Scheduled Promotion", "08037770000")
        val scheduled = reclassifications.reclassify(customer.id, ReclassificationRequest(CustomerType.SPECIAL_CUSTOMER, ReclassificationReason.MET_PURCHASE_VOLUME, null, tenantToday().plusDays(1)))
        // Make it due, then run the job as the scheduler would (no signed-in user).
        tx.execute { TransactionManager.current().exec("update customer_reclassifications set effective_from = current_date where id = '${scheduled.id}'") }
        signOut()
        reclassifications.applyDueFor(tenantId)

        signIn(admin)
        assertThat(customers.get(customer.id).type).isEqualTo(CustomerType.SPECIAL_CUSTOMER)
    }

    @Test
    fun `correspondents see only their own customers`() {
        signIn(admin)
        val rep = correspondents.create(CorrespondentRequest(name = "Bisi Adeyemi", phone = "08021234567", userId = repUser, region = "Lagos Mainland", locationId = ikeja))
        assertThat(rep.correspondent.status).isEqualTo("ASSIGNED")
        val mine = ordinary("Rep Customer", "08038880000", correspondentId = rep.correspondent.id)
        val notMine = ordinary("Someone Else", "08038880001")

        // The rep's customer count is kept on their row by the customers trigger (V14_2) and read in the paged register.
        assertThat(correspondents.getDetail(rep.correspondent.id).correspondent.customerCount).isEqualTo(1)
        val register = correspondents.list(null, emptySet(), emptySet(), null, null, hasCustomers = true, sort = "customerCount", paging = PageRequest.of(0, 50))
        assertThat(register.items.single { it.id == rep.correspondent.id }.customerCount).isEqualTo(1)

        signIn(repUser)
        val visible = lists.list(ListSearchRequest(size = 200)).items.map { it.id }
        assertThat(visible).containsExactly(mine.id)
        assertThat(customers.get(mine.id).id).isEqualTo(mine.id)
        failsWith { customers.get(notMine.id) }.isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND)

        signIn(admin)
        val deassigned = correspondents.deassign(rep.correspondent.id, com.zifiq.customers.dto.CorrespondentDeassignRequest())
        assertThat(deassigned.affectedCustomers).isEqualTo(1)
        assertThat(tx.execute { correspondents.creditableFor(mine.id) }).isNull()
        failsWith { correspondents.deassign(rep.correspondent.id, com.zifiq.customers.dto.CorrespondentDeassignRequest()) }.isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION)
    }

    @Test
    fun `single-location staff only list customers homed at their location`() {
        signIn(admin)
        val atIkeja = ordinary("Ikeja Home", "08039990000", location = ikeja)
        val atLekki = ordinary("Lekki Home", "08039990001", location = lekki)

        signIn(cashier)
        val visible = lists.list(ListSearchRequest(size = 200)).items.map { it.id }
        assertThat(visible).contains(atIkeja.id).doesNotContain(atLekki.id)
        failsWith { customers.get(atLekki.id) }.isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND)
        // A customer can buy anywhere: the picker isn't location-restricted.
        assertThat(lists.lookup("Lekki Home", null, forSale = true, limit = 5).map { it.id }).contains(atLekki.id)
    }

    @Test
    fun `search ranks by name, matches phones with spaces, and never crosses tenants`() {
        signIn(otherAdmin, otherTenantId)
        customers.register(CustomerRequest(type = CustomerType.ORDINARY_CUSTOMER, name = "Okafor Other Tenant", phone = "08034127799", locationId = otherShowroom))

        signIn(admin)
        val okafor = dealer("Okafor Brothers Search", phone = "0805 412 0000")
        ordinary("Nkechi Okaforlike", "08051110000")

        val byName = lists.list(ListSearchRequest(q = "okafor brothers")).items
        assertThat(byName.first().id).isEqualTo(okafor.id)
        assertThat(byName.map { it.name }).noneMatch { it.contains("Other Tenant") }

        assertThat(lists.list(ListSearchRequest(q = "0805 412 0000")).items.map { it.id }).containsExactly(okafor.id)
        assertThat(lists.list(ListSearchRequest(q = okafor.code)).items.first().id).isEqualTo(okafor.id)

        val faceted = lists.list(ListSearchRequest(q = "okafor", facets = listOf("type"), highlight = true))
        assertThat(faceted.facets!!["type"]!!.map { it.value }).contains(CustomerType.AUTHORISED_DEALER.name)
        assertThat(faceted.meta.matches[okafor.id.toString()]!!.first().snippet).contains("<mark>")

        val typoSuggestions = lists.list(ListSearchRequest(q = "Okafro Brothers Serch")).meta
        assertThat(typoSuggestions.suggestions + lists.list(ListSearchRequest(q = "Okafro Brothers Serch")).items.map { it.name })
            .anyMatch { it.contains("Okafor Brothers") }

        failsWith { lists.list(ListSearchRequest(params = mapOf("nonsense" to listOf("1")))) }.isEqualTo(ErrorCode.INVALID_FILTER)
    }

    @Test
    fun `adjustments and their reversal go through the ledger, while sales entries are reversed in their own module`() {
        signIn(admin)
        val id = dealer("Adjusted Dealer Ltd", phone = "08061230000").id
        val sale = tx.execute { wallet.credit(posting(id, LedgerEntryType.FUNDING, "1000", "WF-9")) }

        val adjustment = ledger.adjust(id, WalletAdjustmentRequest(BalanceType.WALLET, LedgerDirection.CREDIT, BigDecimal("500"), "Bank charge refund"))
        assertThat(adjustment.reference).startsWith("WLT-")
        assertThat(adjustment.balanceAfter).isEqualByComparingTo("1500")

        failsWith { ledger.adjust(id, WalletAdjustmentRequest(BalanceType.DEPOSIT, LedgerDirection.CREDIT, BigDecimal("5"), "x")) }.isEqualTo(ErrorCode.BALANCE_TYPE_NOT_ALLOWED)
        failsWith { ledger.adjust(id, WalletAdjustmentRequest(BalanceType.WALLET, LedgerDirection.DEBIT, BigDecimal("99999"), "too much")) }.isEqualTo(ErrorCode.INSUFFICIENT_WALLET_BALANCE)

        val reversal = ledger.reverse(adjustment.id, ReversalRequest("Posted in error"))
        assertThat(reversal.balanceAfter).isEqualByComparingTo("1000")
        failsWith { ledger.reverse(adjustment.id, ReversalRequest("again")) }.isEqualTo(ErrorCode.ALREADY_REVERSED)
        failsWith { ledger.reverse(sale.id, ReversalRequest("not here")) }.isEqualTo(ErrorCode.REVERSE_IN_SOURCE_MODULE)

        val page = ledger.ledger(id, BalanceType.WALLET, ListSearchRequest())
        assertThat(page.items.map { it.entryType }).containsExactly(LedgerEntryType.FUNDING, LedgerEntryType.ADJUSTMENT_CREDIT, LedgerEntryType.REVERSAL_DEBIT)
        assertThat(page.items.first { it.id == adjustment.id }.reversible).isFalse()
        assertThat(page.items.last().amount).isEqualByComparingTo("-500")
    }

    @Test
    fun `status rules - removed is never set by hand and suspended customers can't transact`() {
        signIn(admin)
        val id = ordinary("Status Customer", "08062220000").id
        failsWith { customers.changeStatus(id, CustomerStatusRequest(CustomerStatus.REMOVED, "no")) }.isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION)
        assertThat(customers.changeStatus(id, CustomerStatusRequest(CustomerStatus.SUSPENDED, "Arrears")).status).isEqualTo(CustomerStatus.SUSPENDED)
        failsWith { tx.execute { wallet.credit(posting(id, LedgerEntryType.RETURN_CREDIT, "10", "RT-9")) } }.isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE)

        signIn(cashier)
        failsWith { customers.changeStatus(id, CustomerStatusRequest(CustomerStatus.ACTIVE, "Paid up")) }.isEqualTo(ErrorCode.FORBIDDEN)
    }
}
