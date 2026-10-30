package com.zifiq.history

import com.zifiq.common.errors.ErrorCode
import com.zifiq.platform.api.ApiException
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.history.dto.AuditReviewUpsertRequest
import com.zifiq.history.service.ActivityLogService
import com.zifiq.history.service.AuditReviewService
import com.zifiq.history.service.HistorySupport
import com.zifiq.history.service.StockMovementHistoryService
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.Recipients
import com.zifiq.platform.security.CostVisibility
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.notifications.Notify
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class HistoryIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val keeper = UUID.randomUUID()
        private val factory = UUID.randomUUID()
        private val unit = UUID.randomUUID()
        private val item = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin to "Ada Admin", keeper to "Kemi Keeper").forEach { (id, name) ->
                    exec("insert into app_users (id, email, display_name) values ('$id', 'hist-$id@test.com', '$name')")
                }
                exec("insert into tenants (id, name) values ('$tenantId', 'Audit Co')")
            }
            inTenant {
                listOf(admin, keeper).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
                grant(admin, "Administrator", "select id from permission_catalog")
                grant(keeper, "Keeper", "select id from permission_catalog where code = 'history.stock-movements.read'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'FAC-1', 'Factory A', '$admin')")
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'CTN', 'Cartons')")
                exec("insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, vat_category_id, " +
                    "hsn_section_id, isic_section_id, status, created_by_user_id, created_at) values ('$item', '$tenantId', '$factory', 'FINISHED_PRODUCT', 'ZF-9', 'Widget', " +
                    "'$unit', 0, 1000, (select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                    "(select id from isic_sections where code = 'A'), 'ACTIVE', '$admin', now())")
            }
        }

        private fun inTenant(block: org.jetbrains.exposed.sql.Transaction.() -> Unit) = transaction(database) {
            TransactionManager.current().exec("select set_config('app.tenant_id', '$tenantId', true)")
            block()
        }

        private fun org.jetbrains.exposed.sql.Transaction.grant(user: UUID, roleName: String, permissionIds: String) {
            val role = UUID.randomUUID()
            TransactionManager.current().exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenantId', '$roleName', '$admin')")
            TransactionManager.current().exec("insert into role_permissions (role_id, permission_id) " + permissionIds.replace("select id", "select '$role', id"))
            TransactionManager.current().exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$user', '$role', '$admin')")
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings()), tenantContext)
    private val events = DomainEvents(tenantContext, actors)
    private val locationAccess = LocationAccessService(actors)
    private val notify = Notify(tenantContext, Recipients(tenantContext))
    private val support = HistorySupport(tenantContext, actors, permissions, locationAccess, moduleSettings, events)
    private val reviews = AuditReviewService(support, tx)
    private val activity = ActivityLogService(support, tx)
    private val movements = StockMovementHistoryService(support, tx, CostVisibility())
    private val stockLedger = StockLedgerRepository(notify)
    private val today: LocalDate = LocalDate.now()

    private fun signIn(user: UUID) {
        tenantContext.set(tenantId)
        val jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).claim("email", "$user@test.com").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `audit review is one state per record, flag needs a note, and the change is logged`() {
        signIn(admin)
        val record = UUID.randomUUID()
        val created = reviews.upsert(AuditReviewUpsertRequest("WALLET_WITHDRAWAL", record, "WW-1", "UNDER_REVIEW", null))
        assertThat(created.status).isEqualTo("UNDER_REVIEW")

        // Upserting again replaces the state in place — still one row for the record.
        reviews.upsert(AuditReviewUpsertRequest("WALLET_WITHDRAWAL", record, "WW-1", "REVIEWED", "Looks fine"))
        val list = reviews.list(ListSearchRequest())
        assertThat(list.items.filter { it.recordId == record }).hasSize(1)
        assertThat(list.items.first { it.recordId == record }.status).isEqualTo("REVIEWED")

        // A flag requires a note.
        assertThatThrownBy { reviews.upsert(AuditReviewUpsertRequest("WALLET_WITHDRAWAL", record, "WW-1", "FLAGGED", null)) }
            .isInstanceOf(ApiException::class.java)
            .extracting { (it as ApiException).code }.isEqualTo(ErrorCode.VALIDATION_FAILED)

        // Batch lookup returns the badge.
        val badges = reviews.lookup("WALLET_WITHDRAWAL", listOf(record))
        assertThat(badges).hasSize(1)
        assertThat(badges.first().status).isEqualTo("REVIEWED")

        // The upsert emitted an activity event.
        val events = activity.list(ListSearchRequest.fromQuery(mapOf("eventType" to listOf("history.review.updated"))))
        assertThat(events.items).isNotEmpty
        assertThat(events.items).allMatch { it.eventType == "history.review.updated" }
    }

    @Test
    fun `stock movement lists ledger entries with labels and masks cost for non-cost users`() {
        signIn(admin)
        tx.executeForTenant(tenantId) {
            stockLedger.postEntry(tenantId, factory, item, "REFILL", "RF-1", qtyIn = BigDecimal("100"), unitCost = BigDecimal("1200"), performedByUserId = admin, entryDate = today)
        }
        signIn(admin)
        val asAdmin = movements.list(ListSearchRequest.fromQuery(mapOf("stockItemId" to listOf(item.toString()))))
        val row = asAdmin.items.first { it.ref == "RF-1" }
        assertThat(row.source).isEqualTo("Stock Refill")
        assertThat(row.direction).isEqualTo("IN")
        assertThat(row.quantity).isEqualByComparingTo("100")
        assertThat(row.unitCost).isEqualByComparingTo("1200")
        assertThat(asAdmin.summary?.get("movements")).isNotNull

        // The stock keeper has no factory.costing.read: cost is zeroed, not the quantity.
        signIn(keeper)
        val asKeeper = movements.list(ListSearchRequest.fromQuery(mapOf("stockItemId" to listOf(item.toString()))))
        val keeperRow = asKeeper.items.first { it.ref == "RF-1" }
        assertThat(keeperRow.quantity).isEqualByComparingTo("100")
        assertThat(keeperRow.unitCost).isEqualByComparingTo("0")
        assertThat(keeperRow.value).isEqualByComparingTo("0")
    }
}
