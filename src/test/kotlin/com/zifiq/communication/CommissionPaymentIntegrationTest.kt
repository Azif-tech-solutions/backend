package com.zifiq.communication

import com.zifiq.account.spi.CommissionPayoutPosting
import com.zifiq.account.spi.CommissionPayoutRequest
import com.zifiq.account.spi.CommissionPayouts
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.paging.PageRequest
import com.zifiq.communication.dto.PayCommissionRequest
import com.zifiq.communication.dto.ReversePaymentRequest
import com.zifiq.communication.service.CommunicationSettings
import com.zifiq.communication.service.CommunicationSupport
import com.zifiq.communication.service.PaymentService
import com.zifiq.communication.service.ReportService
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.LedgerEntryRef
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Communication slice 3: partial commission payments against an approved run (outstanding = earned −
 * Σ PAID), the exceeds-outstanding and wallet-only-for-dealers guards, same-day reversal, and the
 * Comms. Report rolling earned/paid/outstanding together.
 */
class CommissionPaymentIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val dlr42 = UUID.randomUUID()
        private val runId = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'cp-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Pay Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                val period = LocalDate.now().minusMonths(2).withDayOfMonth(1)
                exec(
                    "insert into commission_runs (id, tenant_id, ref, beneficiary_type, period_month, status, total_commission, beneficiary_count, " +
                        "eligible_lines, ineligible_lines, pending_confirmation_lines, calculated_at, run_by_user_id, approved_by_user_id, approved_at, created_at) values " +
                        "('$runId', '$tenantId', 'CA-1', 'DEALER', '$period', 'APPROVED', 53100.00, 1, 1, 0, 0, now(), '$admin', '$admin', now(), now())",
                )
                exec(
                    "insert into commission_run_beneficiaries (tenant_id, run_id, beneficiary_id, beneficiary_code, beneficiary_name, gross_commission, carried_in, earned, carried_out) " +
                        "values ('$tenantId', '$runId', '$dlr42', 'DLR-42', 'Dealer 42', 53100.00, 0, 53100.00, 0)",
                )
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), CommunicationSettings()), tenantContext)
    private val support = CommunicationSupport(tenantContext, actors, PermissionAuthorizer(actors, tx), LocationAccessService(actors, LocationHierarchy()), moduleSettings, DomainEvents(tenantContext, actors))
    private val sequences = com.zifiq.platform.numbering.DocumentSequenceService(actors, tx)
    private val payoutsStub = object : CommissionPayouts {
        override fun payOut(request: CommissionPayoutRequest) = CommissionPayoutPosting(UUID.randomUUID(), null)
        override fun reversePayout(request: CommissionPayoutRequest, reason: String) = CommissionPayoutPosting(UUID.randomUUID(), null)
    }
    private val walletStub = object : WalletLedger {
        override fun credit(posting: LedgerPosting) = LedgerEntryRef(UUID.randomUUID(), posting.customerId, BalanceType.WALLET, LedgerEntryType.COMMISSION_CREDIT, posting.amount, posting.amount, posting.reference)
        override fun debit(posting: LedgerPosting) = error("not used")
        override fun reverse(entryId: UUID, reason: String, sourceModule: String) = LedgerEntryRef(UUID.randomUUID(), dlr42, BalanceType.WALLET, LedgerEntryType.REVERSAL_DEBIT, BigDecimal.ZERO, BigDecimal.ZERO, "rev")
    }
    private val payments = PaymentService(support, tx, sequences, payoutsStub, walletStub)
    private val salesStub = object : com.zifiq.cashier.spi.SalesQueries {
        override fun linesFor(filter: com.zifiq.cashier.spi.SalesLineFilter): List<com.zifiq.cashier.spi.SalesLineRef> = emptyList()
        override fun firstCompletedSaleDates(customerIds: Collection<UUID>): Map<UUID, LocalDate> = emptyMap()
    }
    private val correspondentsStub = object : com.zifiq.customers.spi.Correspondents {
        override fun get(id: UUID): com.zifiq.customers.spi.CorrespondentRef = error("not used")
        override fun names(ids: Collection<UUID>): Map<UUID, String> = emptyMap()
        override fun creditableFor(customerId: UUID): com.zifiq.customers.spi.CorrespondentRef? = null
        override fun listAssigned(locationId: UUID?): List<com.zifiq.customers.spi.CorrespondentRef> = emptyList()
    }
    private val reports = ReportService(support, tx, salesStub, correspondentsStub)

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
    fun `partial payments reduce outstanding, guards hold, reversal frees it, and the report rolls up`() {
        signIn()
        val cash = UUID.randomUUID()
        // Pay ₦20,000 of ₦53,100 by cash.
        val first = payments.pay(PayCommissionRequest(runId, dlr42, BigDecimal("20000.00"), "CASH", null, cash, "Ikeja till", "part"))
        assertThat(first.status).isEqualTo("PAID")

        // Paying more than the remaining ₦33,100 is refused.
        assertThatThrownBy { payments.pay(PayCommissionRequest(runId, dlr42, BigDecimal("40000.00"), "CASH", null, cash, null, null)) }
            .isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }.isEqualTo(ErrorCode.PAYMENT_EXCEEDS_OUTSTANDING)

        // Pay the remainder to the dealer's wallet.
        val second = payments.pay(PayCommissionRequest(runId, dlr42, BigDecimal("33100.00"), "WALLET", null, null, null, null))
        assertThat(second.method).isEqualTo("WALLET")

        // Comms report: earned 53,100, paid 53,100, settled.
        val report = reports.commissions("DEALER", null, null, null, null, PageRequest.of(0, 50))
        val row = report.items.single { it.code == "DLR-42" }
        assertThat(row.earned).isEqualByComparingTo("53100.00")
        assertThat(row.paid).isEqualByComparingTo("53100.00")
        assertThat(row.outstanding).isEqualByComparingTo("0.00")
        assertThat(row.status).isEqualTo("Settled")

        // Reverse the cash payment (same day) -> it's freed and the dealer is only part paid again.
        payments.reverse(first.id, ReversePaymentRequest("entered twice"))
        val after = reports.commissions("DEALER", null, null, null, null, PageRequest.of(0, 50)).items.single { it.code == "DLR-42" }
        assertThat(after.paid).isEqualByComparingTo("33100.00")
        assertThat(after.outstanding).isEqualByComparingTo("20000.00")
        assertThat(after.status).isEqualTo("Part paid")
    }
}
