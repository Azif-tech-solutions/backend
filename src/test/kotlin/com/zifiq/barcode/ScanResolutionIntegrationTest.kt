package com.zifiq.barcode

import com.zifiq.barcode.dto.BatchScanRequest
import com.zifiq.barcode.dto.ScanRequest
import com.zifiq.barcode.service.BarcodeSupport
import com.zifiq.barcode.service.ScanLogService
import com.zifiq.barcode.service.ScanResolutionService
import com.zifiq.common.paging.PageRequest
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerType
import com.zifiq.inventory.codes.BarcodeGenerator
import com.zifiq.inventory.codes.BarcodeSettings
import com.zifiq.inventory.codes.CheckDigits
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
 * Barcode & QR slice 2: a scan resolves to exactly one item under the scanning screen's rules —
 * matched, not recognised, blocked (with the right reason) or misread — and every attempt is logged.
 */
class ScanResolutionIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val showroom = UUID.randomUUID()
        private val finished = UUID.randomUUID()
        private val rawItem = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'sc-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Scan Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin')")
                grant(admin, "Administrator", "select id from permission_catalog")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$showroom', '$tenantId', 'SHOWROOM', 'SC-S', 'Showroom', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                for ((id, type, code, name) in listOf(
                    listOf(finished, "FINISHED_PRODUCT", "FP-01", "Citrus Drink"),
                    listOf(rawItem, "RAW_MATERIAL", "RM-01", "Cocoa Powder"),
                )) {
                    exec(
                        "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, " +
                            "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                            "('$id', '$tenantId', '$showroom', '$type', '$code', '$name', '$unit', 0, " +
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
    private val hierarchy = LocationHierarchy()
    private val locationAccess = LocationAccessService(actors, hierarchy)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), BarcodeSettings()), tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val support = BarcodeSupport(tenantContext, actors, permissions, locationAccess, moduleSettings)
    private val sequences = DocumentSequenceService(actors, tx)
    private val events = DomainEvents(tenantContext, actors)
    private val codeService = StockItemCodeService(actors, BarcodeGenerator(moduleSettings, sequences), events)
    private val ledger = StockLedgerRepository(Notify(tenantContext, Recipients(tenantContext)))
    private val availability = StockAvailability(hierarchy, StockReservations(ledger, hierarchy))
    private val customers = object : CustomerDirectory {
        override fun get(id: UUID): CustomerRef = error("not used")
        override fun getForUpdate(id: UUID): CustomerRef = error("not used")
        override fun findActiveByPhone(phone: String): CustomerRef? = error("not used")
        override fun search(q: String, limit: Int, types: Set<CustomerType>?): List<CustomerRef> = error("not used")
    }
    private val scans = ScanResolutionService(support, tx, availability, customers, hierarchy, com.zifiq.barcode.service.BarcodeImageDecoder())
    private val scanLog = ScanLogService(support, tx)

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
    fun `scans resolve to matched, blocked, not-recognised and misread, and are logged`() {
        signIn()
        val finishedCode = tx.execute { codeService.generatePrimary(finished, null).code }
        val rawCode = tx.execute { codeService.generatePrimary(rawItem, null).code }

        // A plain lookup matches the finished product (no price set, so price is absent, not an error).
        val lookup = scans.resolve(ScanRequest(finishedCode, "LOOKUP"))
        assertThat(lookup.outcome).isEqualTo("MATCHED")
        assertThat(lookup.item!!.stockItem.id).isEqualTo(finished)
        assertThat(lookup.price).isNull()

        // At the POS the same item has no price -> blocked; the raw material is not sellable at all.
        assertThat(scans.resolve(ScanRequest(finishedCode, "POS", locationId = showroom)).let { it.outcome to it.blockReason })
            .isEqualTo("BLOCKED" to "NO_PRICE")
        assertThat(scans.resolve(ScanRequest(rawCode, "POS", locationId = showroom)).let { it.outcome to it.blockReason })
            .isEqualTo("BLOCKED" to "NOT_SELLABLE")

        // A well-formed but unknown code is NOT_RECOGNISED (and this admin may register it); a bad
        // check digit is a MISREAD that is never looked up.
        val unknown = CheckDigits.withCheckDigit("615110099999")
        val notRecognised = scans.resolve(ScanRequest(unknown, "LOOKUP"))
        assertThat(notRecognised.outcome).isEqualTo("NOT_RECOGNISED")
        assertThat(notRecognised.canRegister).isTrue()
        val valid = CheckDigits.withCheckDigit("615110010101")
        val misread = valid.dropLast(1) + ((valid.last() - '0' + 1) % 10)
        assertThat(scans.resolve(ScanRequest(misread, "LOOKUP")).outcome).isEqualTo("MISREAD")

        // Batch resolves each code and logs each one.
        val batch = scans.batch(BatchScanRequest("LOOKUP", codes = listOf(finishedCode, unknown)))
        assertThat(batch.map { it.outcome }).containsExactly("MATCHED", "NOT_RECOGNISED")

        // The scan log holds every outcome, with match-rate stats and the unrecognised codes to register.
        val page = scanLog.list(ScanLogService.Filter(), PageRequest.of(0, 50))
        assertThat(page.items).isNotEmpty()
        val stats = scanLog.stats(ScanLogService.Filter())
        assertThat(stats.scans).isGreaterThanOrEqualTo(6)
        assertThat(stats.misread).isGreaterThanOrEqualTo(1)
        assertThat(stats.notRecognised).isGreaterThanOrEqualTo(1)
        assertThat(scanLog.unrecognised(null, null).map { it.code }).contains(unknown)
    }
}
