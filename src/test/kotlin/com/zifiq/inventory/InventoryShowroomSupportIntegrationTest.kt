package com.zifiq.inventory

import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.inventory.dto.DriverRequest
import com.zifiq.inventory.dto.PriceEntryInput
import com.zifiq.inventory.dto.PriceEntryUpdateRequest
import com.zifiq.inventory.dto.PricePublishRequest
import com.zifiq.inventory.dto.QuantityAdjustmentRequest
import com.zifiq.inventory.dto.ReceiveLineInput
import com.zifiq.inventory.dto.StockTransferReceiveRequest
import com.zifiq.inventory.dto.StockTransferRequest
import com.zifiq.inventory.dto.TransferLineInput
import com.zifiq.inventory.repository.PriceListRepository
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.DriverService
import com.zifiq.inventory.service.GroupCardService
import com.zifiq.inventory.service.InventoryOperationsService
import com.zifiq.inventory.service.InventorySettings
import com.zifiq.inventory.service.PriceListService
import com.zifiq.inventory.service.StockLocations
import com.zifiq.inventory.service.WaybillService
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.Recipients
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.security.otp.OtpChallengeService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.AbstractObjectAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/** Inventory changes for Showroom (docs/05 §4.2–§4.13, §12) against real PostgreSQL with RLS. */
class InventoryShowroomSupportIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val keeper: UUID = UUID.randomUUID()
        private val factory: UUID = UUID.randomUUID()
        private val showroom: UUID = UUID.randomUUID()
        private val showroomWarehouse: UUID = UUID.randomUUID()
        private val showroomFloor: UUID = UUID.randomUUID()
        private val pending: UUID = UUID.randomUUID()
        private val suspended: UUID = UUID.randomUUID()
        private val cartons: UUID = UUID.randomUUID()
        private val pieces: UUID = UUID.randomUUID()
        private val drinks: UUID = UUID.randomUUID()
        private val mixed: UUID = UUID.randomUUID()
        private val citrus: UUID = UUID.randomUUID()
        private val malt: UUID = UUID.randomUUID()
        private val straws: UUID = UUID.randomUUID()
        private val directItem: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, keeper).forEach { exec("insert into app_users (id, email, display_name) values ('$it', 'inv-$it@test.com', 'User $it')") }
                exec("insert into tenants (id, name) values ('$tenantId', 'Apex Drinks')")
            }
            transaction(database) {
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                listOf(admin, keeper).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
                val adminRole = UUID.randomUUID()
                exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$adminRole', '$tenantId', 'Administrator', '$admin')")
                exec("insert into role_permissions (role_id, permission_id) select '$adminRole', id from permission_catalog")
                exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$admin', '$adminRole', '$admin')")
                val keeperRole = UUID.randomUUID()
                exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$keeperRole', '$tenantId', 'Keeper', '$admin')")
                exec("insert into role_permissions (role_id, permission_id) select '$keeperRole', id from permission_catalog where code in ('inventory.stock.read', 'inventory.pricing.read')")
                exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$keeper', '$keeperRole', '$admin')")

                fun location(id: UUID, type: String, code: String, status: String = "ACTIVE", parent: UUID? = null, kind: String? = null) =
                    exec("insert into locations (id, tenant_id, type, code, name, status, parent_location_id, area_kind, created_by_user_id) values " +
                        "('$id', '$tenantId', '$type', '$code', 'Loc $code', '$status', ${parent?.let { "'$it'" } ?: "null"}, ${kind?.let { "'$it'" } ?: "null"}, '$admin')")
                location(factory, "FACTORY", "FAC-1")
                location(showroom, "SHOWROOM", "SHR-1")
                location(showroomWarehouse, "STORAGE_AREA", "SHR-1-WH", parent = showroom, kind = "WAREHOUSE")
                location(showroomFloor, "STORAGE_AREA", "SHR-1-SF", parent = showroom, kind = "SHOP_FLOOR")
                location(pending, "SHOWROOM", "SHR-2", status = "PENDING")
                location(suspended, "SHOWROOM", "SHR-3", status = "SUSPENDED")

                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$cartons', '$tenantId', 'CTN', 'Cartons'), ('$pieces', '$tenantId', 'PCS', 'Pieces')")
                exec("insert into categories (id, tenant_id, name, type) values ('$drinks', '$tenantId', 'Soft drinks', 'FINISHED_PRODUCT'), ('$mixed', '$tenantId', 'Mixed', 'FINISHED_PRODUCT')")
                fun item(id: UUID, code: String, name: String, category: UUID, unit: UUID, cost: String, type: String = "FINISHED_PRODUCT", location: UUID = factory) =
                    exec("insert into stock_items (id, tenant_id, location_id, item_type, code, name, category_id, unit_of_measure_id, reorder_level, landing_cost, " +
                        "vat_category_id, hsn_section_id, isic_section_id, status, created_by_user_id, created_at) values ('$id', '$tenantId', '$location', '$type', '$code', '$name', " +
                        "'$category', '$unit', 0, $cost, (select id from vat_categories where code = 'STANDARD_RATE'), " +
                        (if (type == "DIRECT_ENTRY") "null, null" else "(select id from hsn_sections limit 1), (select id from isic_sections limit 1)") +
                        ", 'ACTIVE', '$admin', now())")
                item(citrus, "ITM-1", "Citrus Drink 50cl", drinks, cartons, "3000")
                item(malt, "ITM-2", "Malt Classic 33cl", drinks, cartons, "4000")
                item(straws, "ITM-3", "Straws", mixed, pieces, "10")
                item(directItem, "ITM-4", "Local Groundnuts", mixed, cartons, "200", type = "DIRECT_ENTRY", location = showroom)
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val hierarchy = LocationHierarchy()
    private val access = LocationAccessService(actors, hierarchy)
    private val events = DomainEvents(tenantContext, actors)
    private val notify = Notify(tenantContext, Recipients(tenantContext))
    private val settings = ModuleSettings(listOf(PlatformSettings(), InventorySettings()), tenantContext)
    private val sequences = DocumentSequenceService(actors, tx)
    private val ledger = StockLedgerRepository(notify, hierarchy)
    private val registry = DocumentRegistry(tenantContext, actors, events)
    private val drivers = DriverService(tx, tenantContext, actors, events)
    private val authorizer = PermissionAuthorizer(actors, tx)
    private val operations = InventoryOperationsService(
        actors, tx, sequences, ledger, OtpChallengeService(actors, tx, BCryptPasswordEncoder()), access, settings, registry,
        StockLocations(), hierarchy, drivers, events, notify,
    )
    private val prices = PriceListService(tx, tenantContext, actors, authorizer, access, settings, sequences, events, notify)
    private val groupCards = GroupCardService(tx, actors, access)
    private val waybills = WaybillService(tx, actors, operations, registry, settings, PdfRenderer())
    private val today = LocalDate.now(ZoneId.of("Africa/Lagos"))

    private fun signIn(user: UUID = admin) {
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

    private fun stockUp(location: UUID, item: UUID, qty: String) = tx.execute {
        ledger.postEntry(tenantId, location, item, "REFILL", "RF-TEST", qtyIn = BigDecimal(qty), performedByUserId = admin)
    }

    private fun balance(location: UUID, item: UUID): BigDecimal = tx.execute { ledger.currentBalance(location, item) }

    /** The ledger is the truth: every balance equals the sum of its entries. */
    private fun assertLedgerMatchesBalances() {
        val mismatches = tx.execute {
            TransactionManager.current().exec(
                """select count(*) from stock_balances b where b.quantity_on_hand <> coalesce((select sum(e.qty_in - e.qty_out)
                   from stock_ledger_entries e where e.location_id = b.location_id and e.stock_item_id = b.stock_item_id), 0)""",
            ) { rs -> rs.next(); rs.getLong(1) }!!
        }
        assertThat(mismatches).isZero()
    }

    private fun setFullReconciliation(on: Boolean) = tx.execute {
        settings.set(InventorySettings.RECEIVE_REQUIRE_FULL_RECONCILIATION, com.fasterxml.jackson.databind.node.BooleanNode.valueOf(on), admin)
    }

    @Test
    fun `rejected goods go back to the source and the books balance`() {
        signIn()
        stockUp(factory, citrus, "100")
        val driver = drivers.create(DriverRequest("Musa Bello", "0803 000 1111", "kn-482-abc"))
        val transfer = operations.dispatchTransfer(
            StockTransferRequest(factory, showroom, driver.id, null, null, null, listOf(TransferLineInput(citrus, BigDecimal(50), null, batchNo = "B-77"))),
        )
        assertThat(transfer.driverName).isEqualTo("Musa Bello")
        assertThat(transfer.driverVehicle).isEqualTo("KN-482-ABC")
        assertThat(balance(factory, citrus)).isEqualByComparingTo("50")

        val received = operations.receiveTransfer(transfer.id, StockTransferReceiveRequest(
            lines = listOf(ReceiveLineInput(transfer.lines.single().id, BigDecimal(48), BigDecimal(2), "Crushed in transit", showroomWarehouse)),
        ))
        assertThat(received.status).isEqualTo("RECEIVED")
        assertThat(received.totalRejected).isEqualByComparingTo("2")
        assertThat(balance(factory, citrus)).isEqualByComparingTo("52")
        assertThat(balance(showroomWarehouse, citrus)).isEqualByComparingTo("48")
        assertThat(balance(showroom, citrus)).isEqualByComparingTo("0")
        assertLedgerMatchesBalances()

        failsWith { operations.receiveTransfer(transfer.id, StockTransferReceiveRequest(lines = listOf(ReceiveLineInput(transfer.lines.single().id, BigDecimal.ONE)))) }
            .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION)

        // The waybill PDF shows the lines and is marked as a duplicate once a reprint is recorded.
        val text = { Loader.loadPDF(waybills.render(transfer.id).pdf).use { PDFTextStripper().getText(it) } }
        assertThat(text()).contains(transfer.waybillRef).contains("Citrus Drink 50cl").contains("Musa Bello").contains("Received").doesNotContain("DUPLICATE")
        tx.execute { registry.recordReprint(transfer.waybillRef, "Lost") }
        assertThat(text()).contains("DUPLICATE COPY")
    }

    @Test
    fun `receipts must reconcile, can be partial when allowed, and never exceed what was sent`() {
        signIn()
        stockUp(factory, malt, "100")
        val transfer = operations.dispatchTransfer(StockTransferRequest(factory, showroom, null, "Walk-in driver", null, null, listOf(TransferLineInput(malt, BigDecimal(50), null))))
        val line = transfer.lines.single().id

        failsWith { operations.receiveTransfer(transfer.id, StockTransferReceiveRequest(lines = listOf(ReceiveLineInput(line, BigDecimal(47), BigDecimal(2), "Dented")))) }
            .isEqualTo(ErrorCode.RECEIPT_NOT_RECONCILED)
        failsWith { operations.receiveTransfer(transfer.id, StockTransferReceiveRequest(lines = listOf(ReceiveLineInput(line, BigDecimal(49), BigDecimal(2), "Dented")))) }
            .isEqualTo(ErrorCode.RECEIPT_EXCEEDS_SENT)
        failsWith { operations.receiveTransfer(transfer.id, StockTransferReceiveRequest(lines = listOf(ReceiveLineInput(line, BigDecimal(48), BigDecimal(2))))) }
            .isEqualTo(ErrorCode.VALIDATION_FAILED) // rejection note required

        setFullReconciliation(false)
        try {
            assertThat(operations.receiveTransfer(transfer.id, StockTransferReceiveRequest(lines = listOf(ReceiveLineInput(line, BigDecimal(47), BigDecimal(2), "Dented")))).status)
                .isEqualTo("PARTIALLY_RECEIVED")
            val finished = operations.receiveTransfer(transfer.id, StockTransferReceiveRequest(lines = listOf(ReceiveLineInput(line, BigDecimal(1)))))
            assertThat(finished.status).isEqualTo("RECEIVED")
            assertThat(finished.lines.single().qtyReceived).isEqualByComparingTo("48")
        } finally {
            setFullReconciliation(true)
        }
        assertLedgerMatchesBalances()
    }

    @Test
    fun `location status and areas govern transfers`() {
        signIn()
        stockUp(showroomFloor, malt, "10")
        failsWith { operations.dispatchTransfer(StockTransferRequest(showroom, showroomWarehouse, null, null, null, null, listOf(TransferLineInput(malt, BigDecimal.ONE, null, fromLocationId = showroomFloor)))) }
            .isEqualTo(ErrorCode.SAME_LOCATION)
        failsWith { operations.dispatchTransfer(StockTransferRequest(showroom, suspended, null, null, null, null, listOf(TransferLineInput(malt, BigDecimal.ONE, null, fromLocationId = showroomFloor)))) }
            .isEqualTo(ErrorCode.LOCATION_SUSPENDED)
        failsWith { operations.dispatchTransfer(StockTransferRequest(pending, showroom, null, null, null, null, listOf(TransferLineInput(malt, BigDecimal.ONE, null)))) }
            .isEqualTo(ErrorCode.LOCATION_NOT_TRADING)
        // Availability is checked where the goods are: the shop floor has 10, the showroom itself none.
        failsWith { operations.dispatchTransfer(StockTransferRequest(showroom, pending, null, null, null, null, listOf(TransferLineInput(malt, BigDecimal(5), null)))) }
            .isEqualTo(ErrorCode.INSUFFICIENT_STOCK)
        // A pending showroom can receive.
        val toPending = operations.dispatchTransfer(StockTransferRequest(showroom, pending, null, null, null, null, listOf(TransferLineInput(malt, BigDecimal(5), null, fromLocationId = showroomFloor))))
        assertThat(toPending.lines.single().fromLocationName).isEqualTo("Loc SHR-1-SF")
        assertThat(balance(showroomFloor, malt)).isEqualByComparingTo("5")

        val incoming = operations.listTransfers(pending, setOf("DISPATCHED", "PARTIALLY_RECEIVED"), "INTO", null, null, null)
        assertThat(incoming.map { it.id }).contains(toPending.id)
    }

    @Test
    fun `direct-entry items stay at their showroom`() {
        signIn()
        stockUp(showroomFloor, directItem, "5") // its own showroom's area: fine
        failsWith { stockUp(factory, directItem, "5") }.isEqualTo(ErrorCode.ITEM_LOCATION_RESTRICTED)
    }

    @Test
    fun `a counted adjustment posts the difference from what is on hand`() {
        signIn()
        stockUp(showroomWarehouse, straws, "40")
        val counted = operations.postAdjustment(QuantityAdjustmentRequest(showroomWarehouse, straws, "COUNTED", countedQuantity = BigDecimal(32), reason = "STOCKTAKE_VARIANCE", note = "Shelf count", otpChallengeId = null))
        assertThat(counted.direction).isEqualTo("NEGATIVE")
        assertThat(counted.quantity).isEqualByComparingTo("8")
        assertThat(counted.recordedQuantity).isEqualByComparingTo("40")
        assertThat(counted.newQuantity).isEqualByComparingTo("32")
        assertThat(balance(showroomWarehouse, straws)).isEqualByComparingTo("32")
        failsWith { operations.postAdjustment(QuantityAdjustmentRequest(showroomWarehouse, straws, "COUNTED", countedQuantity = BigDecimal(32), reason = "STOCKTAKE_VARIANCE", note = null, otpChallengeId = null)) }
            .isEqualTo(ErrorCode.NO_ADJUSTMENT_NEEDED)

        // The report for the showroom includes its areas, with totals.
        val report = operations.listAdjustments(ListSearchRequest(params = mapOf("locationId" to listOf(showroom.toString()))))
        assertThat(report.items.map { it.ref }).contains(counted.ref)
        assertThat(report.items.first { it.ref == counted.ref }.rootLocationName).isEqualTo("Loc SHR-1")
        assertThat(report.summary!!["qtyRemoved"] as BigDecimal).isGreaterThanOrEqualTo(BigDecimal(8))
        assertThat(operations.listAdjustments(ListSearchRequest(q = "shelf")).items.map { it.ref }).contains(counted.ref)
    }

    @Test
    fun `prices are effective-dated, validated, and margins are hidden from those who can't see cost`() {
        signIn()
        failsWith { prices.publish(PricePublishRequest(today, null, listOf(PriceEntryInput(citrus, BigDecimal(4000), BigDecimal(4100))))) }
            .isEqualTo(ErrorCode.DEALER_PRICE_ABOVE_RETAIL)
        val published = prices.publish(PricePublishRequest(today, "October list", listOf(PriceEntryInput(citrus, BigDecimal(4500), BigDecimal(4100)), PriceEntryInput(malt, BigDecimal(3500), BigDecimal(3400)))))
        assertThat(published.batchRef).startsWith("PL-")
        assertThat(published.belowCostCount).isEqualTo(1) // malt costs 4000
        failsWith { prices.publish(PricePublishRequest(today, null, listOf(PriceEntryInput(citrus, BigDecimal(4600), BigDecimal(4200))))) }
            .isEqualTo(ErrorCode.PRICE_ALREADY_SET_FOR_DATE)
        prices.publish(PricePublishRequest(today.plusDays(7), null, listOf(PriceEntryInput(citrus, BigDecimal(4800), BigDecimal(4400)))))

        val current = tx.execute { PriceListRepository().currentPrice(citrus, today) }!!
        assertThat(current.retailPrice).isEqualByComparingTo("4500")
        assertThat(current.priceFor(dealer = true)).isEqualByComparingTo("4100")

        val row = prices.list(ListSearchRequest(q = "citrus")).items.single()
        assertThat(row.retailPrice).isEqualByComparingTo("4500")
        assertThat(row.nextChange!!.retailPrice).isEqualByComparingTo("4800")
        assertThat(row.margin).isEqualByComparingTo("1500")
        assertThat(prices.list(ListSearchRequest(params = mapOf("belowCost" to listOf("true")))).items.map { it.code }).containsExactly("ITM-2")

        val history = prices.itemHistory(citrus)
        assertThat(history.map { it.state }).containsExactly("SCHEDULED", "CURRENT")
        failsWith { prices.updateEntry(history.last().priceId, PriceEntryUpdateRequest(BigDecimal(1), BigDecimal(1))) }.isEqualTo(ErrorCode.PRICE_ALREADY_EFFECTIVE)
        assertThat(prices.updateEntry(history.first().priceId, PriceEntryUpdateRequest(BigDecimal(4900), BigDecimal(4500))).retailPrice).isEqualByComparingTo("4900")

        signIn(keeper)
        val masked = prices.list(ListSearchRequest(q = "citrus")).items.single()
        assertThat(masked.landingCost).isEqualByComparingTo("0")
        assertThat(masked.margin).isEqualByComparingTo("0")
        failsWith { prices.list(ListSearchRequest(params = mapOf("belowCost" to listOf("true")))) }.isEqualTo(ErrorCode.INVALID_FILTER)
    }

    @Test
    fun `group cards total a category and refuse mixed units`() {
        signIn()
        stockUp(showroomFloor, citrus, "7")
        stockUp(showroomFloor, malt, "3")
        val card = groupCards.stockCard("CATEGORY", drinks, showroom, null, null)
        assertThat(card.header.members.map { it.code }).containsExactly("ITM-1", "ITM-2")
        assertThat(card.header.closingBalance).isEqualByComparingTo(card.rows.last().qtyRemain)
        assertThat(card.header.closingBalance).isEqualByComparingTo(
            listOf(showroom, showroomWarehouse, showroomFloor).sumOf { balance(it, citrus) + balance(it, malt) },
        )
        failsWith { groupCards.stockCard("CATEGORY", mixed, null, null, null) }.isEqualTo(ErrorCode.GROUP_MIXED_UNITS)
        failsWith { groupCards.stockCard("PARENT_PRODUCT", drinks, null, null, null) }.isEqualTo(ErrorCode.INVALID_FILTER)
    }

    @Test
    fun `drivers are unique by name and vehicle`() {
        signIn()
        drivers.create(DriverRequest("Ade Ola", null, "LAG-1"))
        failsWith { drivers.create(DriverRequest("ade ola", null, "lag-1")) }.isEqualTo(ErrorCode.DUPLICATE_NAME)
        assertThat(drivers.create(DriverRequest("Ade Ola", null, "LAG-2")).vehicleNumber).isEqualTo("LAG-2")
        assertThat(drivers.list("ade", null).size).isGreaterThanOrEqualTo(2)
    }
}
