package com.zifiq.barcode

import com.zifiq.barcode.service.BarcodeImageDecoder
import com.zifiq.barcode.service.BarcodeSearchProvider
import com.zifiq.barcode.service.BarcodeSupport
import com.zifiq.barcode.service.BarcodeSymbols
import com.zifiq.barcode.service.ScanResolutionService
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerType
import com.zifiq.inventory.codes.BarcodeGenerator
import com.zifiq.inventory.codes.BarcodeSettings
import com.zifiq.inventory.codes.StockItemCodeService
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.StockAvailability
import com.zifiq.inventory.service.StockReservations
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.Recipients
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
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Barcode & QR slice 4: a photographed label decodes to the same code a scanner would read and
 * resolves identically, and an exact code in the global header search resolves to its item.
 */
class BarcodeFinishIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val factory = UUID.randomUUID()
        private val item = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'bf-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Finish Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin')")
                grant(admin, "Administrator", "select id from permission_catalog")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'BF-F', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                exec(
                    "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, " +
                        "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                        "('$item', '$tenantId', '$factory', 'FINISHED_PRODUCT', 'FP-01', 'Citrus Drink', '$unit', 0, " +
                        "(select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                        "(select id from isic_sections where code = 'C'), '$admin', now())",
                )
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
    private val hierarchy = LocationHierarchy()
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), BarcodeSettings()), tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val support = BarcodeSupport(tenantContext, actors, permissions, LocationAccessService(actors, hierarchy), moduleSettings)
    private val sequences = DocumentSequenceService(actors, tx)
    private val codeService = StockItemCodeService(actors, BarcodeGenerator(moduleSettings, sequences), DomainEvents(tenantContext, actors))
    private val symbols = BarcodeSymbols()
    private val decoder = BarcodeImageDecoder()
    private val ledger = StockLedgerRepository(Notify(tenantContext, Recipients(tenantContext)))
    private val customers = object : CustomerDirectory {
        override fun get(id: UUID): CustomerRef = error("not used")
        override fun getForUpdate(id: UUID): CustomerRef = error("not used")
        override fun findActiveByPhone(phone: String): CustomerRef? = error("not used")
        override fun search(q: String, limit: Int, types: Set<CustomerType>?): List<CustomerRef> = error("not used")
    }
    private val scans = ScanResolutionService(support, tx, StockAvailability(hierarchy, StockReservations(ledger, hierarchy)), customers, hierarchy, decoder)
    private val searchProvider = BarcodeSearchProvider(support)

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
    fun `a rendered symbol decodes back to its code and resolves, and global search finds the item`() {
        signIn()
        val code = tx.execute { codeService.generatePrimary(item, "EAN_13").code }

        // Render the EAN-13 symbol, then decode the image: the scanner and the camera agree.
        val png = symbols.png(code, "EAN_13")
        assertThat(decoder.decode(png)).contains(code)

        // Resolving that image matches the same item a typed scan would.
        val resolved = scans.resolveImage("LOOKUP", png, null, null)
        assertThat(resolved).hasSize(1)
        assertThat(resolved.single().outcome).isEqualTo("MATCHED")
        assertThat(resolved.single().item!!.stockItem.id).isEqualTo(item)

        // Global search resolves the exact code to its item.
        val hits = tx.execute { searchProvider.search(code, 5) }
        assertThat(hits.map { it.id }).containsExactly(item)
        assertThat(hits.single().ref).isEqualTo("FP-01")
    }
}
