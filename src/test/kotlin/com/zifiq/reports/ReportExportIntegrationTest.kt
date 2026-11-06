package com.zifiq.reports

import com.zifiq.inventory.reports.StockRegisterReportProvider
import com.zifiq.common.paging.PageRequest
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.reports.service.ExportService
import com.zifiq.reports.service.ReportCatalogueService
import com.zifiq.reports.service.ReportsSettings
import com.zifiq.reports.service.ReportsSupport
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Reports & Analytics slice 1: a provider surfaces in the catalogue for a permitted user, and the
 * export engine turns it into a real CSV and XLSX of exactly what the provider streams.
 */
class ReportExportIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val factory = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'rpt-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Reports Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin')")
                grant(admin, "Administrator", "select id from permission_catalog where code = 'inventory.stock.read'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'RP-F', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                for ((code, name) in listOf("FP-01" to "Citrus", "FP-02" to "Mango")) {
                    exec(
                        "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, " +
                            "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                            "('${UUID.randomUUID()}', '$tenantId', '$factory', 'FINISHED_PRODUCT', '$code', '$name', '$unit', 0, " +
                            "(select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                            "(select id from isic_sections where code = 'C'), '$admin', now())",
                    )
                }
            }
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
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), ReportsSettings()), tenantContext)
    private val support = ReportsSupport(tenantContext, actors, permissions, moduleSettings)
    private val sequences = DocumentSequenceService(actors, tx)
    private val provider = StockRegisterReportProvider(actors, permissions)
    private val catalogue = ReportCatalogueService(listOf(provider), tx)
    private val exports = ExportService(support, tx, sequences, listOf(provider))

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
    fun `the stock register appears in the catalogue and exports to CSV and XLSX`() {
        signIn()
        val catalog = catalogue.catalog(null)
        val stock = catalog.streams.single { it.key == "STOCK" }
        assertThat(stock.reports.map { it.key }).contains("inventory.stock-register")
        assertThat(stock.reports.single().formats).containsExactly("CSV", "XLSX")

        val csv = exports.exportSync("inventory.stock-register", emptyMap(), "CSV")
        val text = String(csv.bytes, Charsets.UTF_8)
        assertThat(csv.fileName).isEqualTo("Stock Register.csv")
        assertThat(text).contains("Code,Product,Type,Barcode,Status").contains("FP-01").contains("Citrus")

        val xlsx = exports.exportSync("inventory.stock-register", emptyMap(), "XLSX")
        assertThat(xlsx.contentType).contains("spreadsheetml")
        assertThat(String(xlsx.bytes, Charsets.ISO_8859_1).take(2)).isEqualTo("PK")   // a zip-based XLSX

        // Both exports were recorded as DONE for the caller.
        assertThat(exports.list(false, PageRequest.of(0, 50)).items.map { it["status"] }).contains("DONE").hasSizeGreaterThanOrEqualTo(2)
    }
}
