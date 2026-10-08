package com.zifiq.platform

import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentService
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.documents.PdfRenderer
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
import java.util.UUID
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
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

/** Storage areas and location scope (docs/03 §11), the document registry (§8) and PDF rendering (§8.3). */
class StorageAreasAndDocumentsIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val keeper: UUID = UUID.randomUUID()
        private val showroomS: UUID = UUID.randomUUID()
        private val areaS: UUID = UUID.randomUUID()
        private val showroomT: UUID = UUID.randomUUID()
        private val areaT: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, keeper).forEach { exec("insert into app_users (id, email, display_name) values ('$it', 'sa-$it@test.com', 'User $it')") }
                exec("insert into tenants (id, name) values ('$tenantId', 'Areas Co')")
                // Arrives with the Invoicing module (doc 08); seeded so type-level visibility can be tested now.
                exec("insert into permission_catalog (code, description) values ('invoicing.invoice.read', 'Read invoices') on conflict (code) do nothing")
            }
            inTenant {
                listOf(admin, keeper).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
                val adminRole = UUID.randomUUID()
                exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$adminRole', '$tenantId', 'Administrator', '$admin')")
                exec("insert into role_permissions (role_id, permission_id) select '$adminRole', id from permission_catalog")
                exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$admin', '$adminRole', '$admin')")
                val keeperRole = UUID.randomUUID()
                exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$keeperRole', '$tenantId', 'Keeper', '$admin')")
                exec("insert into role_permissions (role_id, permission_id) select '$keeperRole', id from permission_catalog where code in ('inventory.stock.read', 'platform.documents.read')")
                exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$keeper', '$keeperRole', '$admin')")
                location(showroomS, "SHOWROOM", "SHR-1", null)
                location(areaS, "STORAGE_AREA", "SHR-1-WH", showroomS)
                location(showroomT, "SHOWROOM", "SHR-2", null)
                location(areaT, "STORAGE_AREA", "SHR-2-WH", showroomT)
                exec("insert into user_location_assignments (tenant_id, user_id, location_id, access_scope, assigned_by_user_id) values ('$tenantId', '$keeper', '$showroomS', 'SINGLE_LOCATION', '$admin')")
            }
        }

        private fun org.jetbrains.exposed.sql.Transaction.location(id: UUID, type: String, code: String, parent: UUID?) {
            val kind = if (parent != null) "'WAREHOUSE'" else "null"
            val parentSql = parent?.let { "'$it'" } ?: "null"
            exec("insert into locations (id, tenant_id, type, code, name, parent_location_id, area_kind, created_by_user_id) values ('$id', '$tenantId', '$type', '$code', 'Location $code', $parentSql, $kind, '$admin')")
        }

        private fun <T> inTenant(block: org.jetbrains.exposed.sql.Transaction.() -> T): T = transaction(database) {
            exec("select set_config('app.tenant_id', '$tenantId', true)")
            block()
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val access = LocationAccessService(actors, LocationHierarchy())
    private val registry = DocumentRegistry(tenantContext, actors, DomainEvents(tenantContext, actors))
    private val documents = DocumentService(
        tx, tenantContext, actors, PermissionAuthorizer(actors, tx), access,
        ModuleSettings(listOf(PlatformSettings()), tenantContext), registry, emptyList(),
    )

    private fun signIn(user: UUID) {
        tenantContext.set(tenantId)
        val jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    @Test
    fun `a user assigned to a showroom may act on its areas but not another showroom's`() {
        signIn(keeper)
        tx.execute {
            access.assertAccess(showroomS)
            access.assertAccess(areaS)
            assertThat(access.resolveFilterSet(null)).containsExactlyInAnyOrder(showroomS, areaS)
            assertThat(access.resolveFilterSet(areaS)).containsExactly(areaS)
        }
        failsWith { tx.execute { access.assertAccess(areaT) } }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
        failsWith { tx.execute { access.resolveFilterSet(showroomT) } }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)

        signIn(admin)
        tx.execute {
            assertThat(access.resolveFilterSet(null)).isNull()
            assertThat(access.resolveFilterSet(showroomT)).containsExactlyInAnyOrder(showroomT, areaT)
        }
    }

    @Test
    fun `the database refuses nested areas, orphan areas and users assigned to an area`() {
        assertThatThrownBy { inTenant { location(UUID.randomUUID(), "STORAGE_AREA", "NESTED", areaS) } }.hasMessageContaining("not another area")
        assertThatThrownBy { inTenant { exec("insert into locations (tenant_id, type, code, name, area_kind, created_by_user_id) values ('$tenantId', 'STORAGE_AREA', 'ORPHAN', 'Orphan', 'OTHER', '$admin')") } }
            .hasMessageContaining("locations_area_parent_check")
        assertThatThrownBy { inTenant { exec("update user_location_assignments set location_id = '$areaS' where user_id = '$keeper'") } }
            .hasMessageContaining("not to a storage area")
    }

    @Test
    fun `documents are listed by type permission and location, and reprints are counted`() {
        signIn(admin)
        val waybill = "WB-${UUID.randomUUID().toString().take(6)}"
        val elsewhere = "WB-${UUID.randomUUID().toString().take(6)}"
        val invoice = "INV-${UUID.randomUUID().toString().take(6)}"
        tx.execute {
            registry.register(waybill, DocumentType.WAYBILL, "inventory", UUID.randomUUID(), areaS, "Showroom Kano")
            registry.register(elsewhere, DocumentType.WAYBILL, "inventory", UUID.randomUUID(), showroomT, "Showroom Lagos")
            registry.register(invoice, DocumentType.INVOICE, "invoicing", UUID.randomUUID(), showroomS, "Okafor & Sons", valueAmount = BigDecimal("52890.00"))
        }
        assertThat(documents.list(ListSearchRequest(size = 200)).items.map { it.ref }).contains(waybill, elsewhere, invoice)
        assertThat(documents.list(ListSearchRequest(q = "52890")).items.map { it.ref }).containsExactly(invoice)

        // The keeper reads waybills (inventory.stock.read) but not invoices, and only at their showroom or its areas.
        signIn(keeper)
        val visible = documents.list(ListSearchRequest(size = 200)).items.map { it.ref }
        assertThat(visible).contains(waybill).doesNotContain(elsewhere, invoice)
        failsWith { documents.get(invoice) }.isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND)
        failsWith { documents.get(elsewhere) }.isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND)

        assertThat(documents.reprint(waybill, "Driver lost the copy").reprintCount).isEqualTo(1)
        assertThat(documents.reprint(waybill, null).reprintCount).isEqualTo(2)
        assertThat(tx.execute { registry.isReprint(waybill) }).isTrue()
        assertThat(documents.list(ListSearchRequest(params = mapOf("reprinted" to listOf("true")))).items.map { it.ref }).containsExactly(waybill)

        signIn(admin)
        tx.execute { registry.markVoided(invoice) }
        assertThat(documents.get(invoice).status).isEqualTo("VOIDED")
    }

    @Test
    fun `PDFs render with the bundled font, including the naira sign`() {
        val pdf = PdfRenderer().render("test-smoke", mapOf("title" to "Waybill WB-9014 <script>", "total" to "₦52,890.00", "duplicate" to true))
        assertThat(String(pdf, 0, 5)).isEqualTo("%PDF-")
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertThat(text).contains("Waybill WB-9014 <script>").contains("₦52,890.00").contains("DUPLICATE COPY")
    }
}
