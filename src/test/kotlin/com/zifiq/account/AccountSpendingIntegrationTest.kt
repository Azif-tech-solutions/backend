package com.zifiq.account

import com.zifiq.common.paging.PageRequest
import com.fasterxml.jackson.databind.node.BooleanNode
import com.zifiq.account.dto.BankAccountRequest
import com.zifiq.account.dto.CashAccountRequest
import com.zifiq.account.dto.CashLodgementRequest
import com.zifiq.account.dto.ExpenseCategoryRequest
import com.zifiq.account.dto.FundConfirmRequest
import com.zifiq.account.dto.FundDisbursementRequest
import com.zifiq.account.dto.PettyCashAllocationRequest
import com.zifiq.account.dto.PettyCashFundingRequest
import com.zifiq.account.dto.PettyCashPaymentRequest
import com.zifiq.account.dto.RunningCostRequest
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.account.service.AccountSupport
import com.zifiq.account.service.AccountingSettings
import com.zifiq.account.service.BankAccountService
import com.zifiq.account.service.CashAccountService
import com.zifiq.account.service.CashLodgementService
import com.zifiq.account.service.ExpenseCategoryService
import com.zifiq.account.service.FundDisbursementService
import com.zifiq.account.service.MoneyMoves
import com.zifiq.account.service.PettyCashService
import com.zifiq.account.service.RunningCostService
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
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

/** Finance & Accounting slice 2 — petty cash, running costs, funds awaiting, lodgements (docs/07 §4.6–4.9, §4.14–4.15, §11). */
class AccountSpendingIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val officer: UUID = UUID.randomUUID()
        private val manager: UUID = UUID.randomUUID()
        private val ikejaAccountant: UUID = UUID.randomUUID()
        private val lekkiAccountant: UUID = UUID.randomUUID()
        private val ikeja: UUID = UUID.randomUUID()
        private val lekki: UUID = UUID.randomUUID()

        private const val OFFICER_PERMISSIONS = "'accounting.read', 'accounting.bank.manage', 'accounting.cash-accounts.manage', 'accounting.expense-categories.manage', " +
            "'accounting.petty-cash.fund', 'accounting.petty-cash.pay', 'accounting.running-costs.submit', 'accounting.funds.send', " +
            "'accounting.lodgements.manage', 'accounting.lodgements.confirm'"
        private const val BRANCH_PERMISSIONS = "'accounting.read', 'accounting.petty-cash.respond', 'accounting.funds.confirm', 'accounting.lodgements.manage'"

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, officer, manager, ikejaAccountant, lekkiAccountant).forEach {
                    exec("insert into app_users (id, email, display_name) values ('$it', 'spend-$it@test.com', 'User ${it.toString().take(4)}')")
                }
                exec("insert into tenants (id, name) values ('$tenantId', 'Spending Co')")
            }
            inTenant {
                listOf(admin, officer, manager, ikejaAccountant, lekkiAccountant).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
                grant(admin, "Administrator", "select id from permission_catalog")
                grant(officer, "Accounts officer", "select id from permission_catalog where code in ($OFFICER_PERMISSIONS)")
                grant(manager, "Manager", "select id from permission_catalog where code in ('accounting.read', 'accounting.running-costs.approve', 'platform.activity.read')")
                grant(ikejaAccountant, "Ikeja accounts", "select id from permission_catalog where code in ($BRANCH_PERMISSIONS)")
                grant(lekkiAccountant, "Lekki accounts", "select id from permission_catalog where code in ($BRANCH_PERMISSIONS)")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$ikeja', '$tenantId', 'SHOWROOM', 'SHR-1', 'Ikeja', '$admin'), ('$lekki', '$tenantId', 'SHOWROOM', 'SHR-2', 'Lekki', '$admin')")
                exec("insert into user_location_assignments (tenant_id, user_id, location_id, access_scope, assigned_by_user_id) values ('$tenantId', '$ikejaAccountant', '$ikeja', 'SINGLE_LOCATION', '$admin'), ('$tenantId', '$lekkiAccountant', '$lekki', 'SINGLE_LOCATION', '$admin')")
            }
        }

        private fun inTenant(block: org.jetbrains.exposed.sql.Transaction.() -> Unit) = transaction(database) {
            exec("select set_config('app.tenant_id', '$tenantId', true)")
            block()
        }

        private fun org.jetbrains.exposed.sql.Transaction.grant(user: UUID, roleName: String, permissionIds: String) {
            val role = UUID.randomUUID()
            exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenantId', '$roleName', '$admin')")
            exec("insert into role_permissions (role_id, permission_id) " + permissionIds.replace("select id", "select '$role', id"))
            exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$user', '$role', '$admin')")
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), AccountingSettings()), tenantContext)
    private val support = AccountSupport(tenantContext, actors, PermissionAuthorizer(actors, tx), LocationAccessService(actors), moduleSettings, DomainEvents(tenantContext, actors))
    private val sequences = DocumentSequenceService(actors, tx)
    private val ledger = MoneyLedgerRepository()
    private val recipients = Recipients(tenantContext)
    private val notify = Notify(tenantContext, recipients)
    private val moves = MoneyMoves(support, ledger)
    private val categories = ExpenseCategoryService(support, tx, sequences)
    private val banks = BankAccountService(support, tx, ledger, sequences, categories)
    private val cash = CashAccountService(support, tx, ledger, sequences, categories, notify)
    private val pettyCash = PettyCashService(support, tx, moves, sequences, categories, notify)
    private val runningCosts = RunningCostService(support, tx, moves, sequences, categories, notify)
    private val funds = FundDisbursementService(support, tx, moves, sequences, notify, recipients)
    private val lodgements = CashLodgementService(support, tx, moves, ledger, sequences, notify)

    init {
        val seeded = tx.executeForTenant(tenantId) { TransactionManager.current().exec("select count(*) from expense_categories") { rs -> rs.next(); rs.getLong(1) }!! }
        if (seeded == 0L) tx.executeForTenant(tenantId) { categories.provision(tenantId, admin) }
    }

    private fun signIn(user: UUID) {
        tenantContext.set(tenantId)
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
    private var counter = 0

    private fun hqBank(opening: String = "5000000"): UUID {
        signIn(admin)
        return banks.register(BankAccountRequest(bankName = "GTBank", accountName = "Spending Co", accountNumber = "90${(100000000..999999999).random()}",
            accountType = "CURRENT", openingBalance = BigDecimal(opening), openingBalanceDate = today)).id
    }

    private fun petty(location: UUID?, opening: String = "0", floatLimit: String? = "300000"): UUID {
        signIn(admin)
        return cash.register(CashAccountRequest("PC-${++counter}-${UUID.randomUUID().toString().take(4)}", "Petty cash $counter", "PETTY_CASH", location,
            openingBalance = BigDecimal(opening), floatLimit = floatLimit?.let(::BigDecimal))).id
    }

    private fun category(name: String): UUID = categories.list(name, emptySet(), null, null, null, null).first { it.name == name }.id

    private fun balance(sql: String): BigDecimal = tx.executeForTenant(tenantId) { TransactionManager.current().exec(sql) { rs -> rs.next(); rs.getBigDecimal(1) }!! }

    private fun notifications(user: UUID, titlePrefix: String): Long =
        tx.executeForTenant(tenantId) { TransactionManager.current().exec("select count(*) from user_notifications where user_id = '$user' and title like '$titlePrefix%'") { rs -> rs.next(); rs.getLong(1) }!! }

    @Test
    fun `funding petty cash debits the source, respects the float limit, and reverses only while the cash is there`() {
        val bank = hqBank()
        val box = petty(null)
        signIn(officer)
        val funding = pettyCash.fund(PettyCashFundingRequest(box, "BANK", sourceBankAccountId = bank, amount = BigDecimal("150000"), fundingDate = today, description = "October float"))
        assertThat(funding.ref).startsWith("PCF-")
        assertThat(banks.get(bank).currentBalance).isEqualByComparingTo("4850000")
        assertThat(cash.get(box).currentBalance).isEqualByComparingTo("150000")
        failsWith { pettyCash.fund(PettyCashFundingRequest(box, "BANK", sourceBankAccountId = bank, amount = BigDecimal("200000"), fundingDate = today)) }.isEqualTo(ErrorCode.FLOAT_LIMIT_EXCEEDED)
        failsWith { pettyCash.fund(PettyCashFundingRequest(box, "BANK", sourceCashAccountId = box, amount = BigDecimal("1"), fundingDate = today)) }.isEqualTo(ErrorCode.VALIDATION_FAILED)

        val aside = pettyCash.fundings(ListSearchRequest(params = mapOf("pettyCashAccountId" to listOf(box.toString())))).summary!!
        assertThat(aside["fundedToDate"] as BigDecimal).isEqualByComparingTo("150000")
        assertThat(aside["currentBalance"] as BigDecimal).isEqualByComparingTo("150000")

        // Spend most of it: the funding can no longer be reversed in full.
        pettyCash.pay(PettyCashPaymentRequest(box, category("Transportation"), BigDecimal("100000"), "Haulage", today))
        failsWith { pettyCash.reverseFunding(funding.id, "Wrong box") }.isEqualTo(ErrorCode.INSUFFICIENT_CASH)
        val second = pettyCash.fund(PettyCashFundingRequest(box, "BANK", sourceBankAccountId = bank, amount = BigDecimal("20000"), fundingDate = today))
        assertThat(pettyCash.reverseFunding(second.id, "Sent twice").status).isEqualTo("REVERSED")
        failsWith { pettyCash.reverseFunding(second.id, "Again") }.isEqualTo(ErrorCode.ALREADY_REVERSED)
        assertThat(banks.get(bank).currentBalance).isEqualByComparingTo("4850000")
    }

    @Test
    fun `petty cash payments - payable categories only, never overdrawn, within the monthly cap - and the statement adds up`() {
        val box = petty(null, opening = "50000", floatLimit = null)
        signIn(officer)
        failsWith { pettyCash.pay(PettyCashPaymentRequest(box, category("Bank Charges"), BigDecimal("500"), "Bank", today)) }.isEqualTo(ErrorCode.CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH)
        failsWith { pettyCash.pay(PettyCashPaymentRequest(box, category("Fuel Expenses"), BigDecimal("60000"), "Filling station", today)) }.isEqualTo(ErrorCode.INSUFFICIENT_CASH)
        val capped = categories.create(ExpenseCategoryRequest(name = "Refreshments ${++counter}", accountHead = "ADMINISTRATIVE", monthlyCap = BigDecimal("10000"))).id
        pettyCash.pay(PettyCashPaymentRequest(box, capped, BigDecimal("8000"), "Canteen", today))
        failsWith { pettyCash.pay(PettyCashPaymentRequest(box, capped, BigDecimal("3000"), "Canteen", today)) }.isEqualTo(ErrorCode.MONTHLY_CAP_EXCEEDED)
        val fuel = pettyCash.pay(PettyCashPaymentRequest(box, category("Fuel Expenses"), BigDecimal("12000"), "Filling station", today, approvedBy = "Mrs Okafor"))
        assertThat(fuel.ref).startsWith("PCP-")
        assertThat(fuel.balanceAfter).isEqualByComparingTo("30000")
        assertThat(pettyCash.reversePayment(fuel.id, "Paid twice").status).isEqualTo("REVERSED")

        val statement = pettyCash.statement(box, null, null, null, PageRequest.of(0, 50))
        assertThat(statement.statement.closingBalance).isEqualByComparingTo("42000")
        assertThat(statement.statement.utilisationByCategory.map { it.amount.toPlainString() }).containsExactly("8000.00")
        assertThat(statement.statement.totalUtilised).isEqualByComparingTo("8000")
        assertThat(statement.ledger.items.last().balance).isEqualByComparingTo("42000")

        val history = pettyCash.paymentHistory(ListSearchRequest(params = mapOf("pettyCashAccountId" to listOf(box.toString()))))
        assertThat(history.items.map { it.source }.toSet()).containsExactly("Petty cash payment")
        assertThat(history.summary!!["paymentsMade"]).isEqualTo(1L)
        assertThat(history.items.single { it.ref == fuel.ref }.status).isEqualTo("REVERSED")
    }

    @Test
    fun `running costs - drafts, four-eyes approval, rejection and resubmission, reversal`() {
        val bank = hqBank()
        val maintenance = category("Maintenance Expenses")
        signIn(officer)
        val draft = runningCosts.create(RunningCostRequest(expenseCategoryId = maintenance, payee = "CoolAir Ltd", amount = BigDecimal("45000"),
            paymentMethod = "BANK_TRANSFER", payingBankAccountId = bank, paymentDate = today, description = "Generator servicing"))
        assertThat(draft.status).isEqualTo("DRAFT")
        assertThat(draft.ref).isNull()
        failsWith { runningCosts.create(RunningCostRequest(expenseCategoryId = maintenance, payee = "X", amount = BigDecimal("10"), paymentMethod = "CHEQUE", payingBankAccountId = bank, paymentDate = today)) }
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { runningCosts.create(RunningCostRequest(expenseCategoryId = maintenance, payee = "X", amount = BigDecimal("10"), paymentMethod = "BANK_TRANSFER", payingBankAccountId = bank,
            inputVatAmount = BigDecimal("1"), paymentDate = today)) }.isEqualTo(ErrorCode.VALIDATION_FAILED)

        val submitted = runningCosts.submit(draft.id)
        assertThat(submitted.ref).startsWith("RC-")
        assertThat(submitted.status).isEqualTo("PENDING_APPROVAL")
        assertThat(notifications(manager, "Running cost awaiting approval")).isGreaterThanOrEqualTo(1)
        failsWith { runningCosts.update(draft.id, RunningCostRequest(expenseCategoryId = maintenance, payee = "Changed", amount = BigDecimal("1"), paymentMethod = "BANK_TRANSFER", payingBankAccountId = bank, paymentDate = today)) }
            .isEqualTo(ErrorCode.NOT_EDITABLE)

        signIn(manager)
        runningCosts.reject(draft.id, "Attach the invoice")
        signIn(officer)
        val resubmitted = runningCosts.update(draft.id, RunningCostRequest(expenseCategoryId = maintenance, payee = "CoolAir Ltd", amount = BigDecimal("45000"),
            paymentMethod = "BANK_TRANSFER", payingBankAccountId = bank, invoiceOrReceiptNo = "INV-889", paymentDate = today, submit = true))
        assertThat(resubmitted.ref).isEqualTo(submitted.ref)
        assertThat(resubmitted.rejectionReason).isNull()

        signIn(manager)
        val approved = runningCosts.approve(draft.id)
        assertThat(approved.status).isEqualTo("APPROVED")
        signIn(admin)
        assertThat(banks.get(bank).currentBalance).isEqualByComparingTo("4955000")
        failsWith { runningCosts.approve(draft.id) }.isEqualTo(ErrorCode.ALREADY_DECIDED)
        assertThat(runningCosts.reverse(draft.id, "Duplicate payment").status).isEqualTo("REVERSED")
        assertThat(banks.get(bank).currentBalance).isEqualByComparingTo("5000000")

        val throwaway = runningCosts.create(RunningCostRequest(expenseCategoryId = maintenance, payee = "Draft only", amount = BigDecimal("10"), paymentMethod = "BANK_TRANSFER", payingBankAccountId = bank, paymentDate = today))
        runningCosts.deleteDraft(throwaway.id)
        failsWith { runningCosts.get(throwaway.id) }.isEqualTo(ErrorCode.RUNNING_COST_NOT_FOUND)
        failsWith { runningCosts.deleteDraft(draft.id) }.isEqualTo(ErrorCode.NOT_EDITABLE)
    }

    @Test
    fun `the submitter can't approve their own cost - only an Administrator, and only while the setting allows`() {
        val bank = hqBank()
        val box = petty(null, opening = "20000", floatLimit = null)
        signIn(officer)
        failsWith { runningCosts.create(RunningCostRequest(expenseCategoryId = category("Bank Charges"), payee = "X", amount = BigDecimal("100"), paymentMethod = "PETTY_CASH", payingCashAccountId = box, paymentDate = today, submit = true)) }
            .isEqualTo(ErrorCode.CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH)
        failsWith { runningCosts.create(RunningCostRequest(expenseCategoryId = category("Fuel Expenses"), payee = "X", amount = BigDecimal("100"), paymentMethod = "CASH", payingCashAccountId = box, paymentDate = today, submit = true)) }
            .isEqualTo(ErrorCode.INVALID_PAYING_ACCOUNT)
        failsWith { runningCosts.create(RunningCostRequest(expenseCategoryId = category("Fuel Expenses"), payee = "X", amount = BigDecimal("25000"), paymentMethod = "PETTY_CASH", payingCashAccountId = box, paymentDate = today, submit = true)) }
            .isEqualTo(ErrorCode.INSUFFICIENT_CASH)

        signIn(admin)
        val own = runningCosts.create(RunningCostRequest(expenseCategoryId = category("Fuel Expenses"), payee = "Total", amount = BigDecimal("5000"), paymentMethod = "PETTY_CASH",
            payingCashAccountId = box, paymentDate = today, submit = true))
        tx.execute { moduleSettings.set(AccountingSettings.ADMIN_SELF_APPROVAL, BooleanNode.FALSE, admin) }
        failsWith { runningCosts.approve(own.id) }.isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED)
        tx.execute { moduleSettings.reset(AccountingSettings.ADMIN_SELF_APPROVAL) }
        assertThat(runningCosts.approve(own.id).status).isEqualTo("APPROVED")
        assertThat(cash.get(box).currentBalance).isEqualByComparingTo("15000")
        // Paid from petty cash, so it shows in the Petty Cash Payment History.
        assertThat(pettyCash.paymentHistory(ListSearchRequest(params = mapOf("pettyCashAccountId" to listOf(box.toString())))).items.map { it.source }).containsExactly("Running cost")

        signIn(officer)
        val mine = runningCosts.create(RunningCostRequest(expenseCategoryId = category("Fuel Expenses"), payee = "Oando", amount = BigDecimal("1000"), paymentMethod = "BANK_TRANSFER",
            payingBankAccountId = bank, paymentDate = today, submit = true))
        // An officer holding the approve permission still can't approve their own submission.
        tx.execute { TransactionManager.current().exec("insert into role_permissions (role_id, permission_id) select r.id, p.id from tenant_roles r, permission_catalog p where r.name = 'Accounts officer' and r.tenant_id = '$tenantId' and p.code = 'accounting.running-costs.approve'") }
        failsWith { runningCosts.approve(mine.id) }.isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED)
        assertThat(runningCosts.list(ListSearchRequest(params = mapOf("awaitingMyApproval" to listOf("true")))).items.map { it.id }).doesNotContain(mine.id)
        signIn(manager)
        assertThat(runningCosts.list(ListSearchRequest(params = mapOf("awaitingMyApproval" to listOf("true")))).items.map { it.id }).contains(mine.id)
    }

    @Test
    fun `petty cash sent to a branch is accepted or rejected there, and only there`() {
        val bank = hqBank()
        val ikejaBox = petty(ikeja)
        signIn(officer)
        // Notification counts are cumulative and the tenant's users are shared across this class's
        // tests, so assert the delta from this action, not an absolute count (order-independent).
        val ikejaBefore = notifications(ikejaAccountant, "Petty cash sent to your branch")
        val lekkiBefore = notifications(lekkiAccountant, "Petty cash sent to your branch")
        val officerRejectedBefore = notifications(officer, "Petty cash rejected")
        val sent = pettyCash.sendAllocation(PettyCashAllocationRequest("BANK", sourceBankAccountId = bank, destinationPettyCashAccountId = ikejaBox, amount = BigDecimal("40000"), purpose = "Branch float"))
        assertThat(sent.ref).startsWith("PPC-")
        assertThat(sent.status).isEqualTo("PENDING")
        assertThat(notifications(ikejaAccountant, "Petty cash sent to your branch")).isEqualTo(ikejaBefore + 1)
        assertThat(notifications(lekkiAccountant, "Petty cash sent to your branch")).isEqualTo(lekkiBefore)

        signIn(lekkiAccountant)
        failsWith { pettyCash.acceptAllocation(sent.id) }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
        assertThat(pettyCash.allocations(ListSearchRequest()).items.map { it.id }).doesNotContain(sent.id)
        signIn(ikejaAccountant)
        assertThat(pettyCash.acceptAllocation(sent.id).status).isEqualTo("ACCEPTED")
        failsWith { pettyCash.rejectAllocation(sent.id, "Too late") }.isEqualTo(ErrorCode.ALREADY_DECIDED)
        assertThat(cash.get(ikejaBox).currentBalance).isEqualByComparingTo("40000")

        signIn(officer)
        val second = pettyCash.sendAllocation(PettyCashAllocationRequest("BANK", sourceBankAccountId = bank, destinationPettyCashAccountId = ikejaBox, amount = BigDecimal("10000"), purpose = "Extra"))
        signIn(ikejaAccountant)
        assertThat(pettyCash.rejectAllocation(second.id, "Not requested").status).isEqualTo("REJECTED")
        assertThat(notifications(officer, "Petty cash rejected")).isEqualTo(officerRejectedBefore + 1)
        signIn(admin)
        assertThat(banks.get(bank).currentBalance).isEqualByComparingTo("4960000")
    }

    @Test
    fun `funds awaiting - received never exceeds sent, a shortfall needs a reason and is reported, rejection returns the money`() {
        val hq = hqBank()
        signIn(admin)
        val ikejaBank = banks.register(BankAccountRequest(bankName = "Access", accountName = "Spending Co Ikeja", accountNumber = "77${(10000000..99999999).random()}",
            accountType = "CURRENT", locationId = ikeja)).id
        signIn(officer)
        failsWith { funds.send(FundDisbursementRequest("BANK_TRANSFER", sourceBankAccountId = hq, destinationLocationId = lekki, destinationBankAccountId = ikejaBank, purpose = "Wrong", sentAmount = BigDecimal("1"))) }
            .isEqualTo(ErrorCode.INVALID_DESTINATION_ACCOUNT)
        val sent = funds.send(FundDisbursementRequest("BANK_TRANSFER", sourceBankAccountId = hq, destinationLocationId = ikeja, destinationBankAccountId = ikejaBank, purpose = "Restocking", sentAmount = BigDecimal("500000")))
        assertThat(sent.ref).startsWith("BF-")
        assertThat(notifications(ikejaAccountant, "Funds sent to your location")).isGreaterThanOrEqualTo(1)

        signIn(ikejaAccountant)
        failsWith { funds.confirm(sent.id, FundConfirmRequest(BigDecimal("500001"), today)) }.isEqualTo(ErrorCode.RECEIVED_EXCEEDS_SENT)
        failsWith { funds.confirm(sent.id, FundConfirmRequest(BigDecimal("480000"), today)) }.isEqualTo(ErrorCode.SHORTFALL_REASON_REQUIRED)
        val confirmed = funds.confirm(sent.id, FundConfirmRequest(BigDecimal("480000"), today, "TRF-55", "Bank deducted transfer charges"))
        assertThat(confirmed.status).isEqualTo("PARTIALLY_CONFIRMED")
        assertThat(confirmed.shortfall).isEqualByComparingTo("20000")
        assertThat(notifications(manager, "Funds shortfall")).isGreaterThanOrEqualTo(1)

        signIn(officer)
        val again = funds.send(FundDisbursementRequest("BANK_TRANSFER", sourceBankAccountId = hq, destinationLocationId = ikeja, destinationBankAccountId = ikejaBank, purpose = "Sent in error", sentAmount = BigDecimal("100000")))
        signIn(ikejaAccountant)
        assertThat(funds.reject(again.id, "Not expected").status).isEqualTo("REJECTED")
        signIn(admin)
        assertThat(banks.get(hq).currentBalance).isEqualByComparingTo("4500000")
        assertThat(banks.get(ikejaBank).currentBalance).isEqualByComparingTo("480000")
    }

    @Test
    fun `cash lodgements leave the till at once and reach the bank on confirmation, and a rejected one is investigated, not reversed`() {
        val bank = hqBank("0")
        signIn(admin)
        val till = cash.register(CashAccountRequest("TILL-L-${++counter}", "Lekki Till", "TILL", lekki, openingBalance = BigDecimal("32500"))).id
        signIn(lekkiAccountant)
        failsWith { lodgements.record(CashLodgementRequest(lekki, till, today, BigDecimal("30000"), BigDecimal("31000"), bank)) }.isEqualTo(ErrorCode.BANKED_EXCEEDS_TAKINGS)
        failsWith { lodgements.record(CashLodgementRequest(lekki, till, today, BigDecimal("40000"), BigDecimal("40000"), bank)) }.isEqualTo(ErrorCode.INSUFFICIENT_CASH)
        val lodged = lodgements.record(CashLodgementRequest(lekki, till, today, BigDecimal("32500"), BigDecimal("30000"), bank, "TLR-8812"))
        assertThat(lodged.retained).isEqualByComparingTo("2500")
        assertThat(lodged.status).isEqualTo("AWAITING_CONFIRMATION")
        signIn(admin)
        assertThat(cash.get(till).currentBalance).isEqualByComparingTo("2500")
        assertThat(banks.get(bank).currentBalance).isEqualByComparingTo("0")

        signIn(officer)
        assertThat(lodgements.confirm(lodged.id).status).isEqualTo("CONFIRMED")
        signIn(admin)
        assertThat(banks.get(bank).currentBalance).isEqualByComparingTo("30000")
        val flow = cash.cashFlow(ListSearchRequest(params = mapOf("cashAccountId" to listOf(till.toString()))))
        assertThat(flow.items.map { it.reference }).contains(lodged.ref)

        signIn(lekkiAccountant)
        val lost = lodgements.record(CashLodgementRequest(lekki, till, today, BigDecimal("2500"), BigDecimal("2500"), bank))
        signIn(officer)
        assertThat(lodgements.reject(lost.id, "No matching deposit on the statement").status).isEqualTo("REJECTED")
        assertThat(notifications(manager, "Cash lodgement not received")).isGreaterThanOrEqualTo(1)
        signIn(admin)
        assertThat(cash.get(till).currentBalance).isEqualByComparingTo("0")
    }

    @Test
    fun `every account's balance equals its ledger after all of the above`() {
        // Runs independently too: post a little of everything, then check every account in the tenant.
        val bank = hqBank()
        val box = petty(null)
        signIn(officer)
        pettyCash.fund(PettyCashFundingRequest(box, "BANK", sourceBankAccountId = bank, amount = BigDecimal("50000"), fundingDate = today))
        pettyCash.pay(PettyCashPaymentRequest(box, category("Office Supplies"), BigDecimal("2500"), "Stationers", today))
        val bankMismatches = balance("select count(*) from bank_accounts a where current_balance <> coalesce((select sum(case when entry_type = 'CREDIT' then amount else -amount end) from bank_transactions t where t.bank_account_id = a.id), 0)")
        val cashMismatches = balance("select count(*) from cash_accounts a where current_balance <> coalesce((select sum(case when direction = 'IN' then amount else -amount end) from cash_transactions t where t.cash_account_id = a.id), 0)")
        assertThat(bankMismatches).isEqualByComparingTo("0")
        assertThat(cashMismatches).isEqualByComparingTo("0")
    }
}
