package com.zifiq.reports

import com.zifiq.inventory.reports.StockRegisterReportProvider
import com.zifiq.common.paging.PageRequest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.service.ExportQueueWorker
import com.zifiq.reports.service.ExportService
import com.zifiq.reports.service.ReportsSettings
import com.zifiq.reports.service.ReportsSupport
import com.zifiq.settings.spi.CompanyProfile
import com.zifiq.settings.spi.CompanyProfileProvider
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Reports & Analytics slice 4c: a large (or explicitly deferred) export is QUEUED, produced in the
 * background by the worker re-running the report as the requester, stored, and downloaded by ref.
 */
class AsyncExportIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val factory = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'ae-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Async Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin')")
                grant(admin, "Administrator", "'inventory.stock.read'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'F', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                exec(
                    "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, " +
                        "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                        "('${UUID.randomUUID()}', '$tenantId', '$factory', 'FINISHED_PRODUCT', 'FP-01', 'Citrus', '$unit', 0, " +
                        "(select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                        "(select id from isic_sections where code = 'C'), '$admin', now())",
                )
            }
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
    private val sequences = DocumentSequenceService(actors, tx)
    private val provider = StockRegisterReportProvider(actors, permissions)
    private val companyProfile = object : CompanyProfileProvider {
        override fun get() = CompanyProfile(
            tradingName = "Async Co", legalName = null, registrationNumber = null, tin = null, vatRegistrationNumber = null,
            nrsBusinessId = null, businessDescription = null, email = null, phone = "0800", website = null,
            streetAddress = "1 Main St", city = null, postalCode = null, stateCode = null, stateName = null, lgaCode = null,
            countryCode = "NG", hasLogo = false, documentFooter = null,
        )
    }
    private val exports = ExportService(support, tx, sequences, PdfRenderer(), companyProfile, listOf(provider))
    private val worker = ExportQueueWorker(database, tx, tenantContext, exports)

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
    fun `a queued export is produced by the worker and downloaded by ref`() {
        signIn(admin)
        val outcome = exports.export("inventory.stock-register", emptyMap(), "CSV", wait = false)
        val ref = (outcome as ExportService.Outcome.Queued).ref
        assertThat(ref).startsWith("RPT")

        // Not downloadable while still queued.
        assertThrows<ApiException> { exports.download(ref) }

        // The worker produces it in the background, re-running the report as the requester.
        SecurityContextHolder.clearContext(); tenantContext.clear()
        worker.drain()

        signIn(admin)
        // Surface the failure reason if it did not finish.
        val afterRow = exports.list(false, PageRequest.of(0, 50)).items.single { it["ref"] == ref }
        assertThat(afterRow["status"]).describedAs("export error: %s", afterRow["errorMessage"]).isEqualTo("DONE")
        val file = exports.download(ref)
        assertThat(file.fileName).isEqualTo("Stock Register.csv")
        assertThat(String(file.bytes, Charsets.UTF_8)).contains("Code,Product").contains("FP-01")

        // Recorded DONE with a stored size, and a push notification was queued for the requester.
        val row = exports.list(false, PageRequest.of(0, 50)).items.single { it["ref"] == ref }
        assertThat(row["status"]).isEqualTo("DONE")
        assertThat(row["sizeBytes"] as Long).isGreaterThan(0)
        val notified = tx.execute {
            NotificationOutboxTable.selectAll()
                .where { (NotificationOutboxTable.tenantId eq tenantId) and (NotificationOutboxTable.recipient eq admin.toString()) }
                .any { (it[NotificationOutboxTable.metadata]["ref"]) == ref }
        }
        assertThat(notified).isTrue()
    }
}
