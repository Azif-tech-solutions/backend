package com.zifiq.account

import com.zifiq.account.dto.BankAccountRequest
import com.zifiq.account.dto.CashAccountRequest
import com.zifiq.account.dto.CashCountRequest
import com.zifiq.account.dto.ExpenseCategoryRequest
import com.zifiq.account.dto.ManualBankEntryRequest
import com.zifiq.account.dto.PublishRatesRequest
import com.zifiq.account.dto.RateInput
import com.zifiq.account.repository.CashDirection
import com.zifiq.account.repository.LedgerPosting
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.account.service.AccountSupport
import com.zifiq.account.service.AccountingSettings
import com.zifiq.account.service.BankAccountService
import com.zifiq.account.service.CashAccountService
import com.zifiq.account.service.CashAccountsSpi
import com.zifiq.account.service.ExchangeRateService
import com.zifiq.account.service.ExpenseCategoryService
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/** Finance & Accounting slice 1 — the money ledgers (docs/07 §2.1–2.4, §4.1–4.5, §4.16, §11) — against PostgreSQL with RLS. */
class AccountLedgersIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val otherTenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val viewer: UUID = UUID.randomUUID()
        private val manager: UUID = UUID.randomUUID()
        private val ikejaCashier: UUID = UUID.randomUUID()
        private val otherAdmin: UUID = UUID.randomUUID()
        private val ikeja: UUID = UUID.randomUUID()
        private val lekki: UUID = UUID.randomUUID()
        private val ikejaFloor: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, viewer, manager, ikejaCashier, otherAdmin).forEach {
                    exec("insert into app_users (id, email, display_name) values ('$it', 'acct-$it@test.com', 'User ${it.toString().take(4)}')")
                }
                exec("insert into tenants (id, name) values ('$tenantId', 'Accounts A'), ('$otherTenantId', 'Accounts B')")
            }
            inTenant(tenantId) {
                listOf(admin, viewer, manager, ikejaCashier).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
                grant(tenantId, admin, "select id from permission_catalog")
                grant(tenantId, viewer, "select id from permission_catalog where code = 'accounting.read'")
                grant(tenantId, manager, "select id from permission_catalog where code in ('accounting.read', 'platform.activity.read')")
                grant(tenantId, ikejaCashier, "select id from permission_catalog where code = 'accounting.read'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$ikeja', '$tenantId', 'SHOWROOM', 'SHR-1', 'Ikeja', '$admin'), ('$lekki', '$tenantId', 'SHOWROOM', 'SHR-2', 'Lekki', '$admin')")
                exec("insert into locations (id, tenant_id, type, code, name, parent_location_id, area_kind, created_by_user_id) values ('$ikejaFloor', '$tenantId', 'STORAGE_AREA', 'SHR-1-FL', 'Ikeja Shop Floor', '$ikeja', 'SHOP_FLOOR', '$admin')")
                exec("insert into user_location_assignments (tenant_id, user_id, location_id, access_scope, assigned_by_user_id) values ('$tenantId', '$ikejaCashier', '$ikeja', 'SINGLE_LOCATION', '$admin')")
            }
            inTenant(otherTenantId) {
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$otherTenantId', '$otherAdmin')")
                grant(otherTenantId, otherAdmin, "select id from permission_catalog")
            }
        }

        private fun inTenant(tenant: UUID, block: org.jetbrains.exposed.sql.Transaction.() -> Unit) = transaction(database) {
            exec("select set_config('app.tenant_id', '$tenant', true)")
            block()
        }

        private fun org.jetbrains.exposed.sql.Transaction.grant(tenant: UUID, user: UUID, permissionIds: String) {
            val role = UUID.randomUUID()
            exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenant', 'Role $user', '$admin')")
            exec("insert into role_permissions (role_id, permission_id) " + permissionIds.replace("select id", "select '$role', id"))
            exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenant', '$user', '$role', '$admin')")
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), AccountingSettings()), tenantContext)
    private val support = AccountSupport(tenantContext, actors, permissions, LocationAccessService(actors), moduleSettings, DomainEvents(tenantContext, actors))
    private val sequences = DocumentSequenceService(actors, tx)
    private val ledger = MoneyLedgerRepository()
    private val categories = ExpenseCategoryService(support, tx, sequences)
    private val banks = BankAccountService(support, tx, ledger, sequences, categories)
    private val cash = CashAccountService(support, tx, ledger, sequences, categories, Notify(tenantContext, Recipients(tenantContext)))
    private val fx = ExchangeRateService(support, tx, sequences)
    private val cashSpi = CashAccountsSpi(support)

    init {
        // What the TenantProvisioner gives every new tenant (idempotent per test class).
        listOf(tenantId, otherTenantId).forEach { tenant ->
            val seeded = tx.executeForTenant(tenant) { TransactionManager.current().exec("select count(*) from expense_categories") { rs -> rs.next(); rs.getLong(1) }!! }
            if (seeded == 0L) tx.executeForTenant(tenant) { categories.provision(tenant, admin) }
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

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Africa/Lagos"))

    private fun bank(name: String, number: String, opening: String = "0", openingDate: LocalDate? = null, location: UUID? = null, type: String = "COLLECTIONS", currency: String? = null) =
        banks.register(BankAccountRequest(bankName = name, accountName = "Accounts A Ltd", accountNumber = number, accountType = type, currencyCode = currency,
            openingBalance = BigDecimal(opening), openingBalanceDate = openingDate, locationId = location))

    private fun bankCharges(): UUID = categories.list("Bank Charges", emptySet(), null, null, null, null).first { it.name == "Bank Charges" }.id

    @Test
    fun `every tenant starts with the PRD's expense categories, and new codes continue the sequence`() {
        signIn(admin)
        val all = categories.list(null, emptySet(), null, null, null, null)
        assertThat(all).hasSize(10)
        assertThat(all.single { it.name == "Bank Charges" }.pettyCashAllowed).isFalse()
        assertThat(all.map { it.code }).contains("EX-1", "EX-10")

        val created = categories.create(ExpenseCategoryRequest(name = "Security Services", accountHead = "ADMINISTRATIVE", monthlyCap = BigDecimal("250000")))
        assertThat(created.code).isEqualTo("EX-11")
        failsWith { categories.create(ExpenseCategoryRequest(name = "security services", accountHead = "ADMINISTRATIVE")) }.isEqualTo(ErrorCode.DUPLICATE_NAME)
        failsWith { categories.create(ExpenseCategoryRequest(code = "ex-3", name = "Generator Diesel", accountHead = "LOGISTICS")) }.isEqualTo(ErrorCode.DUPLICATE_CODE)
        assertThat(categories.list(null, setOf("FINANCE"), null, null, null, null).map { it.name }).containsExactly("Bank Charges")
        assertThat(categories.lookup("security", null, true).single().sublabel).contains("of ₦250,000.00 this month")

        signIn(otherAdmin, otherTenantId)
        assertThat(categories.list("Security", emptySet(), null, null, null, null)).isEmpty()
    }

    @Test
    fun `registering a bank account posts its opening balance, and numbers are unique per bank and masked for readers`() {
        signIn(admin)
        val gtb = bank("GTBank", "0123456789", opening = "2000000", openingDate = today.minusDays(30))
        assertThat(gtb.code).startsWith("BA-")
        assertThat(gtb.currentBalance).isEqualByComparingTo("2000000")
        assertThat(gtb.accountNumber).isEqualTo("0123456789")
        failsWith { bank("gtbank", "0123456789") }.isEqualTo(ErrorCode.DUPLICATE_ACCOUNT_NUMBER)
        assertThat(bank("Access Bank", "0123456789").currencyCode).isEqualTo("NGN")

        failsWith { bank("Zenith", "1111111111", type = "DOMICILIARY") }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { bank("Zenith", "1111111111", type = "DOMICILIARY", currency = "JPY") }.isEqualTo(ErrorCode.CURRENCY_NOT_TRACKED)
        failsWith { bank("Zenith", "1111111111", currency = "USD") }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        assertThat(bank("Zenith", "1111111111", type = "DOMICILIARY", currency = "usd").currencyCode).isEqualTo("USD")

        signIn(viewer)
        val seen = banks.get(gtb.id)
        assertThat(seen.accountNumber).isEqualTo("•••• 6789")
        // A reader can't search on the hidden digits, only the last four they can see.
        assertThat(banks.list("0123456", emptySet(), null, null, null, null)).isEmpty()
        assertThat(banks.list("6789", emptySet(), null, null, null, null).map { it.id }).contains(gtb.id)
    }

    @Test
    fun `manual entries and the bank statement - date order, running balance, opening and closing`() {
        signIn(admin)
        val account = bank("First Bank", "2033445566", opening = "500000", openingDate = today.minusDays(40))
        failsWith { banks.postManualEntry(account.id, ManualBankEntryRequest("DEBIT", BigDecimal("1500"), today, "Account maintenance fee")) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { banks.postManualEntry(account.id, ManualBankEntryRequest("CREDIT", BigDecimal("10"), today.plusDays(1), "Future")) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { banks.postManualEntry(account.id, ManualBankEntryRequest("CREDIT", BigDecimal("10"), today.minusDays(41), "Before opening")) }.isEqualTo(ErrorCode.VALIDATION_FAILED)

        val charge = banks.postManualEntry(account.id, ManualBankEntryRequest("DEBIT", BigDecimal("1500"), today, "Account maintenance fee", bankCharges()))
        assertThat(charge.reference).startsWith("MAN-")
        assertThat(charge.balanceAfter).isEqualByComparingTo("498500")
        // Interest discovered later but dated before the charge: the statement still reads in date order.
        banks.postManualEntry(account.id, ManualBankEntryRequest("CREDIT", BigDecimal("200"), today.minusDays(5), "Interest"))

        val statement = banks.statement(account.id, ListSearchRequest(params = mapOf("from" to listOf(today.minusDays(10).toString()), "to" to listOf(today.toString()))))
        assertThat(statement.items.map { it.description to it.balance.toPlainString() })
            .containsExactly("Interest" to "500200.00", "Account maintenance fee" to "498700.00")
        assertThat(statement.items.last().expenseCategoryName).isEqualTo("Bank Charges")
        val summary = statement.summary!!
        assertThat(summary["openingBalance"] as BigDecimal).isEqualByComparingTo("500000")
        assertThat(summary["closingBalance"] as BigDecimal).isEqualByComparingTo("498700")
        assertThat(summary["totalCredits"] as BigDecimal).isEqualByComparingTo("200")
        assertThat(summary["totalDebits"] as BigDecimal).isEqualByComparingTo("1500")

        // The opening entry sits before the period; the default period (this month) is used when none is given.
        assertThat(banks.statement(account.id, ListSearchRequest(q = charge.reference, params = mapOf("period" to listOf("LAST_30_DAYS")))).items.map { it.reference }).containsExactly(charge.reference)
        assertThat(categories.get(bankCharges()).spentThisMonth).isGreaterThanOrEqualTo(BigDecimal("1500"))

        // Ledger integrity: the balance cache equals the sum of the ledger.
        val sum = tx.execute { TransactionManager.current().exec("select sum(case when entry_type = 'CREDIT' then amount else -amount end) from bank_transactions where bank_account_id = '${account.id}'") { rs -> rs.next(); rs.getBigDecimal(1) }!! }
        assertThat(banks.get(account.id).currentBalance).isEqualByComparingTo(sum)

        banks.changeStatus(account.id, "DORMANT")
        failsWith { banks.postManualEntry(account.id, ManualBankEntryRequest("CREDIT", BigDecimal("10"), today, "After dormancy")) }.isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE)
        assertThat(banks.statement(account.id, ListSearchRequest(params = mapOf("from" to listOf(today.minusDays(10).toString())))).items).hasSize(2)
    }

    @Test
    fun `cash accounts - kind rules, counts that post the variance, and no deactivation while holding cash`() {
        signIn(admin)
        val settlement = bank("Wema", "3344556677")
        failsWith { cash.register(CashAccountRequest("TILL-X", "Till", "TILL", ikeja, floatLimit = BigDecimal("1000"))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { cash.register(CashAccountRequest("PC-X", "Petty", "PETTY_CASH", ikejaFloor)) }.isEqualTo(ErrorCode.INVALID_LOCATION)
        val till = cash.register(CashAccountRequest("till-ikj-01", "Ikeja Till 1", "TILL", ikeja, posTerminalRef = "T-55", posSettlementBankAccountId = settlement.id))
        assertThat(till.code).isEqualTo("TILL-IKJ-01")
        assertThat(till.posSettlementBankCode).isEqualTo(settlement.code)
        failsWith { cash.register(CashAccountRequest("TILL-IKJ-01", "Duplicate", "TILL", ikeja)) }.isEqualTo(ErrorCode.DUPLICATE_CODE)

        val petty = cash.register(CashAccountRequest("PC-IKJ", "Ikeja Petty Cash", "PETTY_CASH", ikeja, openingBalance = BigDecimal("150000"), floatLimit = BigDecimal("300000")))
        assertThat(petty.currentBalance).isEqualByComparingTo("150000")
        assertThat(petty.belowFloat).isFalse()
        assertThat(tx.execute { cashSpi.tillsAt(ikeja) }.map { it.code }).containsExactly("TILL-IKJ-01")

        failsWith { cash.recordCount(petty.id, CashCountRequest(BigDecimal("149000"), today)) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        val count = cash.recordCount(petty.id, CashCountRequest(BigDecimal("149000"), today, "₦1,000 receipt missing"))
        assertThat(count.variance).isEqualByComparingTo("-1000")
        assertThat(count.adjustmentReference).startsWith("CC-")
        assertThat(count.account.currentBalance).isEqualByComparingTo("149000")
        val alerts = tx.execute { TransactionManager.current().exec("select count(*) from user_notifications where user_id = '$manager' and title like 'Cash count variance%'") { rs -> rs.next(); rs.getLong(1) }!! }
        assertThat(alerts).isEqualTo(1)
        assertThat(cash.recordCount(petty.id, CashCountRequest(BigDecimal("149000"), today)).adjustmentReference).isNull()

        failsWith { cash.changeStatus(petty.id, "INACTIVE") }.isEqualTo(ErrorCode.ACCOUNT_HAS_BALANCE)
        assertThat(cash.changeStatus(till.id, "INACTIVE").status).isEqualTo("INACTIVE")
    }

    @Test
    fun `cash never goes negative, even when two payouts race for the same float`() {
        signIn(admin)
        val box = cash.register(CashAccountRequest("PC-RACE", "Race Petty Cash", "PETTY_CASH", lekki, openingBalance = BigDecimal("150000")))
        val start = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val workers = (1..2).map { n ->
            thread {
                start.await()
                outcomes += runCatching {
                    tx.executeForTenant(tenantId) {
                        ledger.postCash(tenantId, box.id, CashDirection.OUT, BigDecimal("100000"), LedgerPosting("RACE-$n", "Payout $n", "PETTY_CASH_PAYMENT", entryDate = today, userId = admin))
                    }
                }.fold({ "ok" }, { (it as? ApiException)?.code?.name ?: it.toString() })
            }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        assertThat(outcomes).containsExactlyInAnyOrder("ok", "INSUFFICIENT_CASH")
        assertThat(cash.get(box.id).currentBalance).isEqualByComparingTo("50000")
    }

    @Test
    fun `cash flow statement - running balance across a location's accounts, scoped to the user's location`() {
        signIn(admin)
        cash.register(CashAccountRequest("PC-LKK", "Lekki Petty Cash", "PETTY_CASH", lekki, openingBalance = BigDecimal("80000")))
        cash.register(CashAccountRequest("TILL-LKK-01", "Lekki Till", "TILL", lekki, openingBalance = BigDecimal("20000")))
        val vault = cash.register(CashAccountRequest("VAULT-HQ", "Head office vault", "VAULT", null, openingBalance = BigDecimal("1000000")))

        val flow = cash.cashFlow(ListSearchRequest(params = mapOf("locationId" to listOf(lekki.toString()), "cashAccountId" to emptyList())))
        val lekkiCodes = flow.items.map { it.accountCode }.toSet()
        assertThat(lekkiCodes).contains("PC-LKK", "TILL-LKK-01").doesNotContain("VAULT-HQ")
        val summary = flow.summary!!
        assertThat((summary["closingCash"] as BigDecimal)).isEqualByComparingTo(flow.items.last().balance)
        assertThat(summary["totalInflow"] as BigDecimal).isEqualByComparingTo(flow.items.mapNotNull { it.inflow }.fold(BigDecimal.ZERO, BigDecimal::add))
        assertThat(flow.items.map { it.sn }).isEqualTo((1L..flow.items.size).toList())

        val pettyOnly = cash.cashFlow(ListSearchRequest(params = mapOf("locationId" to listOf(lekki.toString()), "kind" to listOf("PETTY_CASH"))))
        assertThat(pettyOnly.items.map { it.kind }.toSet()).containsExactly("PETTY_CASH")
        assertThat(pettyOnly.items.map { it.accountCode }).contains("PC-LKK").doesNotContain("TILL-LKK-01")
        assertThat(cash.cashFlow(ListSearchRequest(params = mapOf("direction" to listOf("IN")))).items.map { it.accountCode }).contains("VAULT-HQ")

        // A cashier tied to Ikeja sees Ikeja's cash only — never Lekki's, never head office's.
        signIn(ikejaCashier)
        assertThat(cash.cashFlow(ListSearchRequest()).items.map { it.accountCode }).doesNotContain("PC-LKK", "VAULT-HQ")
        failsWith { cash.cashFlow(ListSearchRequest(params = mapOf("locationId" to listOf(lekki.toString())))) }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
        failsWith { cash.get(vault.id) }.isEqualTo(ErrorCode.CASH_ACCOUNT_NOT_FOUND)
        assertThat(cash.list(null, emptySet(), null, null, null).map { it.code }).doesNotContain("VAULT-HQ", "PC-LKK")
        assertThat(banks.list(null, emptySet(), null, null, null, null).all { it.locationId == ikeja }).isTrue()
    }

    @Test
    fun `exchange rates are append-only, tracked currencies only, and the spi returns the rate in force`() {
        signIn(admin)
        assertThat(fx.current().map { it.code }).containsExactly("USD", "GBP", "EUR", "CNY", "ZAR", "GHS")
        failsWith { fx.publish(PublishRatesRequest(listOf(RateInput("JPY", BigDecimal("10"), BigDecimal("11"))))) }.isEqualTo(ErrorCode.CURRENCY_NOT_TRACKED)
        failsWith { fx.publish(PublishRatesRequest(listOf(RateInput("USD", BigDecimal("1520"), BigDecimal("1500"))))) }.isEqualTo(ErrorCode.INVALID_RATE)

        val first = fx.publish(PublishRatesRequest(listOf(RateInput("usd", BigDecimal("1500"), BigDecimal("1520")), RateInput("GBP", BigDecimal("1900"), BigDecimal("1935")))))
        assertThat(first.ref).startsWith("FX-")
        val between = Instant.now()
        Thread.sleep(5)
        fx.publish(PublishRatesRequest(listOf(RateInput("USD", BigDecimal("1510"), BigDecimal("1530")))))

        val usd = fx.current().single { it.code == "USD" }
        assertThat(usd.buy).isEqualByComparingTo("1510")
        assertThat(usd.previousBuy).isEqualByComparingTo("1500")
        assertThat(usd.move).isEqualByComparingTo("10")
        assertThat(usd.movePct).isEqualByComparingTo("0.67")
        assertThat(usd.spread).isEqualByComparingTo("20")
        assertThat(usd.freshness).isEqualTo("Current")
        assertThat(fx.current().single { it.code == "EUR" }.buy).isNull()

        assertThat(tx.execute { fx.current("USD") }!!.buy).isEqualByComparingTo("1510")
        assertThat(tx.execute { fx.asOf("USD", between) }!!.buy).isEqualByComparingTo("1500")
        assertThat(fx.history(ListSearchRequest(params = mapOf("currencyCode" to listOf("USD")))).items.map { it.buyRate.toPlainString() }).containsExactly("1510.000000", "1500.000000")
    }
}
