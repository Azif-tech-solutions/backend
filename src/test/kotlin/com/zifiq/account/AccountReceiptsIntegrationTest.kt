package com.zifiq.account

import com.zifiq.account.dto.BankAccountRequest
import com.zifiq.account.dto.CashAccountRequest
import com.zifiq.account.dto.LocationVatRequest
import com.zifiq.account.dto.OtherIncomeRequest
import com.zifiq.account.dto.WalletFundingRequest
import com.zifiq.account.dto.WalletWithdrawalRequest
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.account.service.AccountSupport
import com.zifiq.account.service.AccountingSettings
import com.zifiq.account.service.BankAccountService
import com.zifiq.account.service.CashAccountService
import com.zifiq.account.service.ExpenseCategoryService
import com.zifiq.account.service.LocationVatService
import com.zifiq.account.service.MoneyMoves
import com.zifiq.account.service.ReceiptService
import com.zifiq.account.service.ReceiptWriter
import com.zifiq.account.service.TenderableService
import com.zifiq.account.service.WalletMoneyService
import com.zifiq.account.spi.Channel
import com.zifiq.account.spi.ConfirmationStatus
import com.zifiq.account.spi.ReceiptRequest
import com.zifiq.account.spi.RefundRequest
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.customers.service.CustomerListService
import com.zifiq.customers.service.CustomerSpiService
import com.zifiq.customers.service.CustomerSupport
import com.zifiq.customers.service.CustomersSettings
import com.zifiq.customers.service.DealerCategoryService
import com.zifiq.customers.service.WalletLedgerService
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.customfields.CustomFieldValidator
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.Recipients
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.security.otp.OtpChallengeService
import com.zifiq.platform.security.otp.dto.OtpChallengeRequest
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.settings.service.CompanyProfileService
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/** Finance & Accounting slice 3 — receipts, wallet money, tenderable, other income, VAT per location (docs/07 §4.10–4.13, §4.17–4.18, §11). */
class AccountReceiptsIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val accounts: UUID = UUID.randomUUID()
        private val cashier: UUID = UUID.randomUUID()
        private val manager: UUID = UUID.randomUUID()
        private val ikeja: UUID = UUID.randomUUID()
        private val ikejaFloor: UUID = UUID.randomUUID()

        private const val ACCOUNTS_PERMISSIONS = "'accounting.read', 'accounting.receipts.read', 'accounting.receipts.confirm', 'accounting.receipts.void', " +
            "'accounting.receipts.record', 'accounting.wallet.fund', 'accounting.wallet.withdraw', 'accounting.tenderable.read', 'accounting.bank.manage', " +
            "'accounting.cash-accounts.manage', 'accounting.vat.manage'"

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, accounts, cashier, manager).forEach {
                    exec("insert into app_users (id, email, display_name) values ('$it', 'rcpt-$it@test.com', 'User ${it.toString().take(4)}')")
                }
                exec("insert into tenants (id, name) values ('$tenantId', 'Receipts Co')")
            }
            inTenant {
                listOf(admin, accounts, cashier, manager).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
                grant(admin, "Administrator", "select id from permission_catalog")
                grant(accounts, "Accounts", "select id from permission_catalog where code in ($ACCOUNTS_PERMISSIONS)")
                grant(cashier, "Cashier", "select id from permission_catalog where code in ('accounting.receipts.read', 'accounting.wallet.fund')")
                grant(manager, "Manager", "select id from permission_catalog where code in ('accounting.read', 'accounting.wallet.recover')")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$ikeja', '$tenantId', 'SHOWROOM', 'SHR-1', 'Ikeja', '$admin')")
                exec("insert into locations (id, tenant_id, type, code, name, parent_location_id, area_kind, created_by_user_id) values ('$ikejaFloor', '$tenantId', 'STORAGE_AREA', 'SHR-1-FL', 'Ikeja floor', '$ikeja', 'SHOP_FLOOR', '$admin')")
                exec("insert into user_location_assignments (tenant_id, user_id, location_id, access_scope, assigned_by_user_id) values ('$tenantId', '$cashier', '$ikeja', 'SINGLE_LOCATION', '$admin')")
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
    private val permissions = PermissionAuthorizer(actors, tx)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), CustomersSettings(), AccountingSettings()), tenantContext)
    private val events = DomainEvents(tenantContext, actors)
    private val support = AccountSupport(tenantContext, actors, permissions, LocationAccessService(actors), moduleSettings, events)
    private val sequences = DocumentSequenceService(actors, tx)
    private val ledger = MoneyLedgerRepository()
    private val recipients = Recipients(tenantContext)
    private val notify = Notify(tenantContext, recipients)
    private val moves = MoneyMoves(support, ledger)
    private val categories = ExpenseCategoryService(support, tx, sequences)
    private val banks = BankAccountService(support, tx, ledger, sequences, categories)
    private val cash = CashAccountService(support, tx, ledger, sequences, categories, notify)
    private val registry = DocumentRegistry(tenantContext, actors, events)
    private val writer = ReceiptWriter(support, moves, sequences, registry)
    private val customerSupport = CustomerSupport(actors, permissions, tenantContext)
    private val customerDirectory = CustomerSpiService(customerSupport, tenantContext,
        CustomerListService(customerSupport, tx, LocationAccessService(actors), CustomFieldValidator(tenantContext), moduleSettings), sequences, moduleSettings, events)
    private val walletLedger = WalletLedgerService(tenantContext, actors, events)
    private val otp = OtpChallengeService(actors, tx, BCryptPasswordEncoder())
    private val wallets = WalletMoneyService(support, tx, writer, moves, sequences, customerDirectory, walletLedger, otp, permissions)
    private val profiles = CompanyProfileService(tx, tenantContext, actors, events)
    private val receipts = ReceiptService(support, tx, writer, listOf(wallets), registry, PdfRenderer(), profiles, notify, recipients)
    private val tenderable = TenderableService(support, tx, customerDirectory)
    private val vat = LocationVatService(support, tx, database, notify)

    private var dealer: UUID = UUID(0, 0)
    private var special: UUID = UUID(0, 0)
    private var walkIn: UUID = UUID(0, 0)

    init {
        tx.executeForTenant(tenantId) {
            val seeded = TransactionManager.current().exec("select count(*) from dealer_categories") { rs -> rs.next(); rs.getLong(1) }!!
            if (seeded == 0L) {
                DealerCategoryService(customerSupport, tx, events).provision(tenantId, admin)
                profiles.provision(tenantId, admin)
            }
        }
        val ids = (1..3).map { UUID.randomUUID() }
        dealer = ids[0]; special = ids[1]; walkIn = ids[2]
        inTenant {
            exec("insert into customers (id, tenant_id, code, type, name, created_by_user_id, dealer_category_id) select '$dealer', '$tenantId', 'DLR-${dealer.toString().take(6)}', 'AUTHORISED_DEALER', 'Okafor & Sons ${dealer.toString().take(6)}', '$admin', id from dealer_categories where tenant_id = '$tenantId' limit 1")
            exec("insert into customers (id, tenant_id, code, type, name, created_by_user_id) values ('$special', '$tenantId', 'CU-S${special.toString().take(5)}', 'SPECIAL_CUSTOMER', 'Grace Foods ${special.toString().take(6)}', '$admin'), ('$walkIn', '$tenantId', 'CU-W${walkIn.toString().take(5)}', 'ORDINARY_CUSTOMER', 'Ada ${walkIn.toString().take(6)}', '$admin')")
        }
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

    private fun bank(): UUID {
        signIn(admin)
        return banks.register(BankAccountRequest(bankName = "GTBank", accountName = "Receipts Co", accountNumber = "55${(10000000..99999999).random()}",
            accountType = "COLLECTIONS", openingBalance = BigDecimal("1000000"), openingBalanceDate = today)).id
    }

    private fun till(location: UUID? = ikeja, opening: String = "0", settlement: UUID? = null): UUID {
        signIn(admin)
        return cash.register(CashAccountRequest("T-${++counter}-${UUID.randomUUID().toString().take(4)}", "Till $counter", if (location == null) "VAULT" else "TILL", location,
            openingBalance = BigDecimal(opening), posSettlementBankAccountId = settlement)).id
    }

    private fun balanceOf(customer: UUID, column: String = "wallet_balance"): BigDecimal =
        tx.executeForTenant(tenantId) { TransactionManager.current().exec("select $column from customers where id = '$customer'") { rs -> rs.next(); rs.getBigDecimal(1) }!! }

    private fun notifications(user: UUID, titlePrefix: String): Long =
        tx.executeForTenant(tenantId) { TransactionManager.current().exec("select count(*) from user_notifications where user_id = '$user' and title like '$titlePrefix%'") { rs -> rs.next(); rs.getLong(1) }!! }

    @Test
    fun `cash funding credits the wallet at once and a cashier sees only their own receipts`() {
        val ikejaTill = till()
        signIn(cashier)
        val before = balanceOf(dealer)
        val funded = wallets.fund(WalletFundingRequest(dealer, "WALLET", "CASH", BigDecimal("75000"), ikeja, cashAccountId = ikejaTill))
        assertThat(funded.status).isEqualTo("CONFIRMED")
        assertThat(funded.confirmationStatus).isEqualTo("NOT_REQUIRED")
        assertThat(balanceOf(dealer) - before).isEqualByComparingTo("75000")
        assertThat(receipts.list(ListSearchRequest()).items.map { it.cashierUserId }.toSet()).containsExactly(cashier)

        signIn(accounts)
        assertThat(cash.get(ikejaTill).currentBalance).isEqualByComparingTo("75000")
        val other = wallets.fund(WalletFundingRequest(dealer, "WALLET", "CASH", BigDecimal("1000"), ikeja, cashAccountId = ikejaTill))
        signIn(cashier)
        failsWith { receipts.getRow(other.receiptId) }.isEqualTo(ErrorCode.RECEIPT_NOT_FOUND)
        failsWith { wallets.fund(WalletFundingRequest(walkIn, "WALLET", "CASH", BigDecimal("10"), ikeja, cashAccountId = ikejaTill)) }.isEqualTo(ErrorCode.BALANCE_TYPE_NOT_ALLOWED)
        failsWith { wallets.fund(WalletFundingRequest(dealer, "DEPOSIT", "CASH", BigDecimal("10"), ikeja, cashAccountId = ikejaTill)) }.isEqualTo(ErrorCode.BALANCE_TYPE_NOT_ALLOWED)
    }

    @Test
    fun `transfer funding waits for Accounts - approve credits bank and wallet, decline credits nothing`() {
        val gtb = bank()
        signIn(accounts)
        val before = balanceOf(dealer)
        val transfer = wallets.fund(WalletFundingRequest(dealer, "WALLET", "FUND_TRANSFER", BigDecimal("200000"), ikeja, receivingBankAccountId = gtb, payerReference = "NIP-1"))
        assertThat(transfer.status).isEqualTo("AWAITING_CONFIRMATION")
        assertThat(balanceOf(dealer)).isEqualByComparingTo(before)
        assertThat(receipts.confirmationStats(null).pendingCount).isGreaterThanOrEqualTo(1)

        val approved = receipts.approve(transfer.receiptId)
        assertThat(approved.confirmationStatus).isEqualTo("APPROVED")
        assertThat(balanceOf(dealer) - before).isEqualByComparingTo("200000")
        assertThat(banks.get(gtb).currentBalance).isEqualByComparingTo("1200000")
        failsWith { receipts.approve(transfer.receiptId) }.isEqualTo(ErrorCode.ALREADY_DECIDED)

        val bounced = wallets.fund(WalletFundingRequest(dealer, "WALLET", "CHEQUE", BigDecimal("50000"), ikeja, receivingBankAccountId = gtb, payerReference = "CHQ-77"))
        receipts.decline(bounced.receiptId, "Cheque bounced")
        assertThat(balanceOf(dealer) - before).isEqualByComparingTo("200000")
        assertThat(banks.get(gtb).currentBalance).isEqualByComparingTo("1200000")
        assertThat(wallets.fundings(ListSearchRequest(q = bounced.ref)).items.single().status).isEqualTo("DECLINED")
        assertThat(notifications(accounts, "Payment declined")).isEqualTo(0)
    }

    @Test
    fun `voiding a funding takes the credit back, and sale receipts are voided in their own module`() {
        val gtb = bank()
        val ikejaTill = till()
        signIn(accounts)
        val start = balanceOf(special, "deposit_balance")
        val deposit = wallets.fund(WalletFundingRequest(special, "DEPOSIT", "CASH", BigDecimal("30000"), ikeja, cashAccountId = ikejaTill))
        assertThat(balanceOf(special, "deposit_balance") - start).isEqualByComparingTo("30000")
        assertThat(receipts.void(deposit.receiptId, "Recorded against the wrong customer").status).isEqualTo("VOIDED")
        assertThat(balanceOf(special, "deposit_balance")).isEqualByComparingTo(start)
        assertThat(cash.get(ikejaTill).currentBalance).isEqualByComparingTo("0")
        failsWith { receipts.void(deposit.receiptId, "Again") }.isEqualTo(ErrorCode.ALREADY_REVERSED)

        // A sale receipt belongs to Sales & POS.
        val sale = tx.execute {
            receipts.recordReceipt(ReceiptRequest(ikeja, null, null, "Walk-in", Channel.POS, BigDecimal("5000"), "cashier", "POS_SALE", UUID.randomUUID(), "TXN-1",
                receivingBankAccountId = gtb, terminalRef = "T-55"))
        }
        assertThat(sale.confirmationStatus).isEqualTo(ConfirmationStatus.PENDING)
        failsWith { receipts.void(sale.id, "No") }.isEqualTo(ErrorCode.REVERSE_IN_SOURCE_MODULE)
        failsWith { tx.execute { receipts.voidReceipt(sale.id, "No", "invoicing") } }.isEqualTo(ErrorCode.REVERSE_IN_SOURCE_MODULE)
        tx.execute { receipts.voidReceipt(sale.id, "Sale cancelled", "cashier") }
        assertThat(tx.execute { receipts.get(sale.id) }!!.status).isEqualTo("VOIDED")

        // Refunds post at once; cheques can't be refunded through here.
        signIn(admin)
        val till2 = till(opening = "8000")
        val refund = tx.execute { receipts.recordRefund(RefundRequest(ikeja, till2, null, null, "Walk-in", Channel.CASH, BigDecimal("2000"), "cashier", "SALE_REFUND", UUID.randomUUID(), "TXN-2")) }
        assertThat(refund.ref).startsWith("RCT-")
        assertThat(cash.get(till2).currentBalance).isEqualByComparingTo("6000")
        failsWith { tx.execute { receipts.recordRefund(RefundRequest(ikeja, null, gtb, null, null, Channel.CHEQUE, BigDecimal("1"), "cashier", "SALE_REFUND", UUID.randomUUID(), "TXN-3")) } }
            .isEqualTo(ErrorCode.REFUND_CHANNEL_NOT_SUPPORTED)
    }

    @Test
    fun `a dealer withdrawal needs an OTP issued for that dealer and that amount, used once`() {
        val gtb = bank()
        val ikejaTill = till()
        signIn(accounts)
        wallets.fund(WalletFundingRequest(dealer, "WALLET", "CASH", BigDecimal("250000"), ikeja, cashAccountId = ikejaTill))
        val before = balanceOf(dealer)

        fun verifiedOtp(amount: Int, target: UUID = dealer): UUID {
            signIn(accounts)
            val challenge = otp.request(OtpChallengeRequest("WALLET_WITHDRAWAL", target.toString(), mapOf("amount" to amount, "targetLabel" to "Okafor & Sons"))).challengeId
            signIn(admin)
            val code = otp.issue(challenge).code
            signIn(accounts)
            otp.verify(challenge, code)
            return challenge
        }
        fun request(amount: String, challenge: UUID?) = WalletWithdrawalRequest(dealer, "WALLET", "DEALER_REQUEST", BigDecimal(amount), "BANK",
            payoutBankAccountId = gtb, paidVia = "Transfer to Access 0123456789", otpChallengeId = challenge, withdrawalDate = today, locationId = ikeja)

        failsWith { wallets.withdraw(request("120000", null)) }.isEqualTo(ErrorCode.OTP_REQUIRED)
        val forFifty = verifiedOtp(50000)
        failsWith { wallets.withdraw(request("120000", forFifty)) }.isEqualTo(ErrorCode.OTP_MISMATCH)
        failsWith { wallets.withdraw(request("120000", verifiedOtp(120000, special))) }.isEqualTo(ErrorCode.OTP_MISMATCH)
        val done = wallets.withdraw(request("50000", forFifty))
        assertThat(done.ref).startsWith("WW-")
        assertThat(done.otp).isEqualTo("Verified")
        assertThat(done.balanceBefore - done.balanceAfter).isEqualByComparingTo("50000")
        assertThat(before - balanceOf(dealer)).isEqualByComparingTo("50000")
        assertThat(banks.get(gtb).currentBalance).isEqualByComparingTo("950000")
        failsWith { wallets.withdraw(request("50000", forFifty)) }.isEqualTo(ErrorCode.OTP_REQUIRED)
        failsWith { wallets.withdraw(request("50000", null).copy(reason = "INVOICE_SETTLEMENT")) }.isEqualTo(ErrorCode.USE_INVOICE_PAYMENT)

        // Company recoveries: Management, someone authorised, nobody paid.
        val recovery = WalletWithdrawalRequest(dealer, "WALLET", "EXCESS_COMMISSION_RECOVERY", BigDecimal("10000"), "NONE", authorisedByUserId = manager, withdrawalDate = today)
        failsWith { wallets.withdraw(recovery) }.isEqualTo(ErrorCode.FORBIDDEN)
        signIn(manager)
        failsWith { wallets.withdraw(recovery.copy(authorisedByUserId = accounts)) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        assertThat(wallets.withdraw(recovery).initiatedBy).isEqualTo("COMPANY")
        assertThat(wallets.reverse(done.id, "Dealer cancelled").auditStatus).isEqualTo("REVERSED")
        assertThat(before - balanceOf(dealer)).isEqualByComparingTo("10000")
        assertThat(banks.get(gtb).currentBalance).isEqualByComparingTo("1000000")
        assertThat(wallets.withdrawals(ListSearchRequest(params = mapOf("initiatedBy" to listOf("COMPANY")))).summary!!["companyInitiated"] as BigDecimal).isGreaterThanOrEqualTo(BigDecimal("10000"))
    }

    @Test
    fun `tenderable counts only money that arrived, other income posts like any receipt, and receipts print`() {
        val gtb = bank()
        val vault = till(location = null)
        signIn(cashier)
        val ikejaTill = till()
        signIn(cashier)
        wallets.fund(WalletFundingRequest(dealer, "WALLET", "CASH", BigDecimal("40000"), ikeja, cashAccountId = ikejaTill))
        val pending = wallets.fund(WalletFundingRequest(dealer, "WALLET", "POS", BigDecimal("15000"), ikeja, cashAccountId = ikejaTill, receivingBankAccountId = gtb))
        assertThat(pending.confirmationStatus).isEqualTo("PENDING")

        signIn(accounts)
        val row = tenderable.payable(ikeja, cashier, today, today).single()
        assertThat(row.cash).isEqualByComparingTo("40000")
        assertThat(row.pos).isEqualByComparingTo("0")
        assertThat(row.pendingConfirmation).isEqualByComparingTo("15000")
        assertThat(row.total).isEqualByComparingTo("40000")
        assertThat(tenderable.generalReport(ikeja, today, today).totals!!.total).isEqualByComparingTo("40000")
        assertThat(tenderable.walletFunds(dealer, today, today, null, null).items.map { it.status }).contains("Confirmed", "Awaiting confirmation")

        val rent = receipts.recordOtherIncome(OtherIncomeRequest(null, "RENTAL", "CASH", BigDecimal("120000"), cashAccountId = vault, payerName = "Tenant: Shop 4",
            description = "October rent", receivedDate = today))
        assertThat(rent.sourceRef).isEqualTo(rent.ref)
        assertThat(cash.get(vault).currentBalance).isEqualByComparingTo("120000")
        val interest = receipts.recordOtherIncome(OtherIncomeRequest(null, "INTEREST", "FUND_TRANSFER", BigDecimal("3500"), receivingBankAccountId = gtb,
            payerName = "GTBank", description = "Interest on deposit", receivedDate = today))
        assertThat(interest.confirmationStatus).isEqualTo("PENDING")
        assertThat(receipts.otherIncome(ListSearchRequest(params = mapOf("incomeCategory" to listOf("RENTAL")))).items.map { it.id }).containsExactly(rent.id)
        receipts.void(rent.id, "Paid by the wrong tenant")
        assertThat(cash.get(vault).currentBalance).isEqualByComparingTo("0")

        val pdf = receipts.pdf(rent.id)
        assertThat(String(pdf.pdf, 0, 5)).isEqualTo("%PDF-")
        assertThat(pdf.ref).isEqualTo(rent.ref)
    }

    @Test
    fun `VAT per location is effective-dated, applies today's change at once, and resolves areas to their showroom`() {
        signIn(accounts)
        val start = vat.list().locations.single { it.locationId == ikeja }
        assertThat(start.rate).isEqualByComparingTo("7.5")
        failsWith { vat.set(ikeja, LocationVatRequest(true, BigDecimal("10"), effectiveFrom = today.minusDays(1))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { vat.set(ikejaFloor, LocationVatRequest(true, BigDecimal("10"), effectiveFrom = today)) }.isEqualTo(ErrorCode.INVALID_LOCATION)

        val scheduled = vat.set(ikeja, LocationVatRequest(true, BigDecimal("10"), "NG-VAT-1", today.plusDays(10), "New rate")).locations.single { it.locationId == ikeja }
        assertThat(scheduled.rate).isEqualByComparingTo("7.5")
        assertThat(scheduled.scheduled!!.rate).isEqualByComparingTo("10")
        failsWith { vat.set(ikeja, LocationVatRequest(true, BigDecimal("11"), effectiveFrom = today.plusDays(10))) }.isEqualTo(ErrorCode.VAT_SETTING_EXISTS_FOR_DATE)

        val disabled = vat.set(ikeja, LocationVatRequest(false, BigDecimal("7.5"), effectiveFrom = today)).locations.single { it.locationId == ikeja }
        assertThat(disabled.enabled).isFalse()
        assertThat(vat.list().disabledCount).isGreaterThanOrEqualTo(1)
        val onFloor = tx.execute { vat.forLocation(ikejaFloor, today) }
        assertThat(onFloor.locationId).isEqualTo(ikeja)
        assertThat(onFloor.rate).isEqualByComparingTo("0")
        assertThat(tx.execute { vat.forLocation(ikeja, today.plusDays(10)) }.rate).isEqualByComparingTo("10")
        val cache = tx.execute { TransactionManager.current().exec("select vat_enabled from locations where id = '$ikeja'") { rs -> rs.next(); rs.getBoolean(1) }!! }
        assertThat(cache).isFalse()
        assertThat(vat.history(ikeja).map { it.state }).contains("Scheduled", "In force")
    }
}
