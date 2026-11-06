package com.zifiq.reports

import com.zifiq.inventory.reports.StockRegisterReportProvider
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.dto.ScheduleRequest
import com.zifiq.reports.model.ReportSchedulesTable
import com.zifiq.reports.service.ExportQueueWorker
import com.zifiq.reports.service.ExportService
import com.zifiq.reports.service.ReportsSettings
import com.zifiq.reports.service.ReportsSupport
import com.zifiq.reports.service.ScheduleRunner
import com.zifiq.reports.service.ScheduleService
import com.zifiq.settings.spi.CompanyProfile
import com.zifiq.settings.spi.CompanyProfileProvider
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Reports & Analytics slice 4d: a scheduled report checks every recipient can run the report, fires as
 * its owner, and e-mails the produced export to recipients.
 */
class ScheduleIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val reader = UUID.randomUUID()   // permitted recipient
        private val outsider = UUID.randomUUID()  // member without the report permission
        private val factory = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'sch-a-$admin@test.com', 'Admin'), ('$reader', 'sch-r-$reader@test.com', 'Reader'), ('$outsider', 'sch-o-$outsider@test.com', 'Outsider')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Schedule Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin'), ('$tenantId', '$reader'), ('$tenantId', '$outsider')")
                grant(admin, "Administrator", "'reports.schedules.manage', 'inventory.stock.read'")
                grant(reader, "Stock Keeper", "'inventory.stock.read'")
                grant(outsider, "Clerk", "'customers.read'")
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
            "Schedule Co", null, null, null, null, null, null, null, "0800", null,
            "1 Main St", null, null, null, null, null, "NG", false, null,
        )
    }
    private val exports = ExportService(support, tx, sequences, PdfRenderer(), companyProfile, listOf(provider))
    private val schedules = ScheduleService(support, tx, permissions, exports, listOf(provider))
    private val worker = ExportQueueWorker(database, tx, tenantContext, exports)
    private val runner = ScheduleRunner(database, tx, tenantContext, schedules)

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
    fun `a recipient who cannot run the report is rejected`() {
        signIn(admin)
        val ex = assertThrows<ApiException> {
            schedules.create(req(recipients = listOf(outsider)))
        }
        assertThat(ex.message).contains("couldn't run")
    }

    @Test
    fun `the runner fires a due schedule and the export is e-mailed to recipients`() {
        signIn(admin)
        val created = schedules.create(req(recipients = listOf(reader)))
        assertThat(created.nextRunAt).isAfter(Instant.now())
        assertThat(created.recipients.single()!!.id).isEqualTo(reader)

        // Make it due, then run the schedule runner and the export worker.
        signIn(admin)
        tx.execute { ReportSchedulesTable.update({ ReportSchedulesTable.id eq created.id }) { it[nextRunAt] = Instant.now().minusSeconds(60) } }
        SecurityContextHolder.clearContext(); tenantContext.clear()
        runner.run()
        worker.drain()

        signIn(admin)
        val readerEmail = "sch-r-$reader@test.com"
        val (exportDone, emailed) = tx.execute {
            val export = com.zifiq.reports.model.ReportExportsTable.selectAll()
                .where { (com.zifiq.reports.model.ReportExportsTable.tenantId eq tenantId) and (com.zifiq.reports.model.ReportExportsTable.scheduleId eq created.id) }
                .single()
            val email = NotificationOutboxTable.selectAll()
                .where { (NotificationOutboxTable.tenantId eq tenantId) and (NotificationOutboxTable.recipient eq readerEmail) and (NotificationOutboxTable.channel eq "EMAIL") }
                .any()
            export[com.zifiq.reports.model.ReportExportsTable.status] to email
        }
        assertThat(exportDone).isEqualTo("DONE")
        assertThat(emailed).isTrue()
    }

    private fun req(recipients: List<UUID>) = ScheduleRequest(
        name = "Daily stock", reportKey = "inventory.stock-register", params = emptyMap(), format = "CSV",
        frequency = "DAILY", recipients = recipients,
    )
}
