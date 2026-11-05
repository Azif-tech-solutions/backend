package com.zifiq.communication

import com.zifiq.common.paging.PageRequest
import com.zifiq.communication.dto.SetRateRequest
import com.zifiq.communication.dto.UpdateRateRequest
import com.zifiq.communication.service.CommunicationSupport
import com.zifiq.communication.service.RateService
import com.zifiq.common.errors.ErrorCode
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
import org.assertj.core.api.AbstractObjectAssert
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
 * Communication slice 1: the Comms. Guide — effective-dated commission rates, superseding, no
 * backdating, and that a future-dated rate (not yet in force) is the only one that can be changed.
 */
class CommissionRatesIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val factory = UUID.randomUUID()
        private val citrus = UUID.randomUUID()
        private val malt = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'cm-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Comms Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'CM-F', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                for ((id, code, name) in listOf(listOf(citrus, "FP-01", "Citrus 50cl"), listOf(malt, "FP-02", "Malt 33cl"))) {
                    exec(
                        "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, " +
                            "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                            "('$id', '$tenantId', '$factory', 'FINISHED_PRODUCT', '$code', '$name', '$unit', 0, " +
                            "(select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                            "(select id from isic_sections where code = 'C'), '$admin', now())",
                    )
                }
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings()), tenantContext)
    private val support = CommunicationSupport(tenantContext, actors, PermissionAuthorizer(actors, tx), LocationAccessService(actors, LocationHierarchy()), moduleSettings, DomainEvents(tenantContext, actors))
    private val rates = RateService(support, tx)

    private fun signIn() {
        tenantContext.set(tenantId)
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").subject(admin.toString()).build())
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    @Test
    fun `rates are effective-dated, superseded, never backdated, and only future rows are editable`() {
        signIn()
        val today = LocalDate.now()

        // A rate from today, and a future change from next month.
        rates.setRate(SetRateRequest(citrus, "DEALER", "PER_UNIT", BigDecimal("45.00"), null, today, "ACTIVE", "Q4"))
        val future = rates.setRate(SetRateRequest(citrus, "DEALER", "PER_UNIT", BigDecimal("50.00"), null, today.plusMonths(1), "ACTIVE", "Nov"))

        // Backdating is refused; a duplicate start date conflicts.
        failsWith { rates.setRate(SetRateRequest(citrus, "DEALER", "PER_UNIT", BigDecimal("40.00"), null, today.minusDays(1), "ACTIVE", null)) }
            .isEqualTo(ErrorCode.RATE_CANNOT_BE_BACKDATED)
        failsWith { rates.setRate(SetRateRequest(citrus, "DEALER", "PER_UNIT", BigDecimal("41.00"), null, today, "ACTIVE", null)) }
            .isEqualTo(ErrorCode.RATE_ALREADY_SET_FOR_DATE)

        // The guide shows the in-force rate (₦45 today) and previews the next change (₦50 next month).
        val row = rates.list("DEALER", null, null, null, null, true, PageRequest.of(0, 50)).items.single { it.stockItem.id == citrus }
        assertThat(row.status).isEqualTo("Active")
        assertThat(row.rate).isEqualByComparingTo("45.00")
        assertThat(row.commissionPerUnit).isEqualByComparingTo("45.00")
        assertThat(row.nextChange!!.rate).isEqualByComparingTo("50.00")

        // The predecessor was closed the day before the future row takes effect.
        val history = rates.history(citrus, "DEALER")
        assertThat(history).hasSize(2)
        assertThat(history.first { it.effectiveFrom == today }.effectiveTo).isEqualTo(today.plusMonths(1).minusDays(1))

        // An unrated product shows "Not set"; it disappears when includeUnrated is off.
        val maltRow = rates.list("DEALER", null, null, null, null, true, PageRequest.of(0, 50)).items.single { it.stockItem.id == malt }
        assertThat(maltRow.status).isEqualTo("Not set")
        assertThat(rates.list("DEALER", null, null, null, null, false, PageRequest.of(0, 50)).items.map { it.stockItem.id }).doesNotContain(malt)

        // Only the future row can be edited/withdrawn; editing the in-force row is refused.
        rates.update(future.id, UpdateRateRequest(rate = BigDecimal("55.00")))
        assertThat(rates.history(citrus, "DEALER").first { it.effectiveFrom == today.plusMonths(1) }.rate).isEqualByComparingTo("55.00")
        val inForceId = history.first { it.effectiveFrom == today }.id
        failsWith { rates.update(inForceId, UpdateRateRequest(rate = BigDecimal("46.00"))) }.isEqualTo(ErrorCode.RATE_NOT_EDITABLE)

        // Withdrawing the future row reopens the predecessor (its effective_to goes back to null).
        rates.withdraw(future.id)
        assertThat(rates.history(citrus, "DEALER").single().effectiveTo).isNull()
    }
}
