package com.zifiq.barcode

import com.zifiq.barcode.dto.LabelPreviewRequest
import com.zifiq.barcode.dto.LabelRunItemInput
import com.zifiq.barcode.dto.LabelRunRequest
import com.zifiq.barcode.service.BarcodeSupport
import com.zifiq.barcode.service.BarcodeSymbols
import com.zifiq.barcode.service.LabelImages
import com.zifiq.barcode.service.LabelService
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.paging.PageRequest
import com.zifiq.inventory.codes.BarcodeGenerator
import com.zifiq.inventory.codes.BarcodeSettings
import com.zifiq.inventory.codes.StockItemCodeService
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
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
 * Barcode & QR slice 3: a label print run picks items, generates any missing code, snapshots what it
 * printed, and renders a real PDF (ZXing symbols) and ZPL — with the price shown but never the cost.
 */
class LabelIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val factory = UUID.randomUUID()
        private val withCode = UUID.randomUUID()
        private val withoutCode = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'lb-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Label Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin')")
                grant(admin, "Administrator", "select id from permission_catalog")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'LB-F', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                for ((id, code, name) in listOf(listOf(withCode, "FP-01", "Citrus Drink"), listOf(withoutCode, "FP-02", "Mango Drink"))) {
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

        private fun org.jetbrains.exposed.sql.Transaction.grant(user: UUID, roleName: String, permissionIds: String) {
            val role = UUID.randomUUID()
            org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenantId', '$roleName', '$admin')")
            org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec("insert into role_permissions (role_id, permission_id) " + permissionIds.replace("select id", "select '$role', id"))
            org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$user', '$role', '$admin')")
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), BarcodeSettings()), tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val support = BarcodeSupport(tenantContext, actors, permissions, LocationAccessService(actors, LocationHierarchy()), moduleSettings)
    private val sequences = DocumentSequenceService(actors, tx)
    private val codeService = StockItemCodeService(actors, BarcodeGenerator(moduleSettings, sequences), DomainEvents(tenantContext, actors))
    private val labels = LabelService(support, tx, codeService, sequences, BarcodeSymbols(), LabelImages(), PdfRenderer())

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

    @Test
    fun `a print run generates missing codes, renders a PDF and ZPL, and records history`() {
        signIn()
        tx.execute { codeService.generatePrimary(withCode, null) }

        // The picker separates items with and without a code.
        val items = labels.items(null, null, null, null, PageRequest.of(0, 50)).items.associateBy { it.stockItem.id }
        assertThat(items[withCode]!!.primaryCode).isNotBlank()
        assertThat(items[withoutCode]!!.primaryCode).isNull()

        // An un-coded item without generateMissing is refused (checked before it gets a code below).
        assertThatThrownBy {
            labels.createRun(LabelRunRequest(size = "58x40", items = listOf(LabelRunItemInput(withoutCode, 1)), generateMissing = false))
        }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }.isEqualTo(ErrorCode.ITEM_HAS_NO_CODE)

        // A run over one coded and one un-coded item with generateMissing creates one code and 30 labels.
        val run = labels.createRun(
            LabelRunRequest(
                size = "58x40", outputFormat = "PDF", generateMissing = true,
                items = listOf(LabelRunItemInput(withCode, 24), LabelRunItemInput(withoutCode, 6)),
            ),
        )
        assertThat(run.totalLabels).isEqualTo(30)
        assertThat(run.codesGenerated).isEqualTo(1)
        assertThat(run.downloadUrl).contains("/labels/runs/").endsWith(".pdf")

        val runId = UUID.fromString(run.downloadUrl.substringAfter("/runs/").substringBefore("/"))
        val pdf = labels.renderPdf(runId)
        assertThat(String(pdf, Charsets.ISO_8859_1)).startsWith("%PDF")
        assertThat(labels.renderZpl(runId)).contains("^XA").contains("^XZ")

        // History shows the run.
        assertThat(labels.history(PageRequest.of(0, 50)).items.map { it.ref }).contains(run.ref)

        // Preview renders a PNG label.
        assertThat(String(labels.preview(LabelPreviewRequest(withCode, "58x40")), Charsets.ISO_8859_1).take(8)).contains("PNG")
    }
}
