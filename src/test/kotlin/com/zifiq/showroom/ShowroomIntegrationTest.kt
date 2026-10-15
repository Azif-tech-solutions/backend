package com.zifiq.showroom

import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.tax.TaxClassificationRepository
import com.zifiq.customers.dto.CustomerRequest
import com.zifiq.customers.service.CustomerListService
import com.zifiq.customers.service.CustomerService
import com.zifiq.customers.service.CustomerSpiService
import com.zifiq.customers.service.CustomerSupport
import com.zifiq.customers.service.CustomersSettings
import com.zifiq.customers.service.WalletLedgerService
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.inventory.dto.PriceEntryInput
import com.zifiq.inventory.dto.PricePublishRequest
import com.zifiq.inventory.repository.PriceListRepository
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.InventorySettings
import com.zifiq.inventory.service.PriceListService
import com.zifiq.inventory.service.StockLocations
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.customfields.CustomFieldValidator
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
import com.zifiq.showroom.dto.AreaMoveLineInput
import com.zifiq.showroom.dto.AreaMoveRequest
import com.zifiq.showroom.dto.AreaRequest
import com.zifiq.showroom.dto.DirectEntryLineInput
import com.zifiq.showroom.dto.DirectEntryRequest
import com.zifiq.showroom.dto.OutsourceLineInput
import com.zifiq.showroom.dto.OutsourceReceiptRequest
import com.zifiq.showroom.dto.ReturnLineInput
import com.zifiq.showroom.dto.ReturnRequest
import com.zifiq.showroom.dto.ShowroomDetail
import com.zifiq.showroom.dto.ShowroomRequest
import com.zifiq.showroom.dto.ShowroomStatusRequest
import com.zifiq.showroom.dto.WalkInCustomer
import com.zifiq.showroom.service.AreaMoveService
import com.zifiq.showroom.service.CustomerReturnService
import com.zifiq.showroom.service.ReferenceNames
import com.zifiq.showroom.service.ShowroomRegisterService
import com.zifiq.showroom.service.ShowroomReportService
import com.zifiq.showroom.service.ShowroomSettings
import com.zifiq.showroom.service.StockIntakeService
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
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

/** Showroom module (docs/05 §12) against real PostgreSQL with RLS. */
class ShowroomIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val keeper: UUID = UUID.randomUUID()
        private val factory: UUID = UUID.randomUUID()
        private val cartons: UUID = UUID.randomUUID()
        private val citrus: UUID = UUID.randomUUID()
        private val outsourced: UUID = UUID.randomUUID()
        private val outsourcedNoCost: UUID = UUID.randomUUID()
        private val supplier: UUID = UUID.randomUUID()
        private val partner: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, keeper).forEach { exec("insert into app_users (id, email, display_name) values ('$it', 'sh-$it@test.com', 'User $it')") }
                exec("insert into tenants (id, name) values ('$tenantId', 'Showroom Co')")
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
                exec("insert into role_permissions (role_id, permission_id) select '$keeperRole', id from permission_catalog where code in " +
                    "('showroom.stock.read', 'showroom.stock.manage', 'showroom.intake.manage', 'showroom.returns.manage', 'showroom.returns.read', 'customers.read')")
                exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$keeper', '$keeperRole', '$admin')")
                exec("insert into tenant_vat_categories (tenant_id, vat_category_id, activated_by_user_id) select '$tenantId', id, '$admin' from vat_categories where code = 'STANDARD_RATE'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'FAC-1', 'Factory A', '$admin')")
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$cartons', '$tenantId', 'CTN', 'Cartons')")
                exec("insert into suppliers (id, tenant_id, code, company_name) values ('$supplier', '$tenantId', 'SUP-1', 'Kano Wholesale')")
                exec("insert into manufacturers (id, tenant_id, code, name) values ('$partner', '$tenantId', 'MFR-1', 'Partner Bottlers')")
                fun item(id: UUID, code: String, name: String, type: String, cost: String?) =
                    exec("insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, vat_category_id, " +
                        "hsn_section_id, isic_section_id, status, created_by_user_id, created_at) values ('$id', '$tenantId', '$factory', '$type', '$code', '$name', '$cartons', 0, " +
                        "${cost ?: "null"}, (select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections limit 1), (select id from isic_sections limit 1), 'ACTIVE', '$admin', now())")
                item(citrus, "ITM-1", "Citrus Drink 50cl", "FINISHED_PRODUCT", "3000")
                item(outsourced, "ITM-2", "Partner Cola", "OUTSOURCED", "2000")
                item(outsourcedNoCost, "ITM-3", "Partner Juice", "OUTSOURCED", null)
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
    private val settings = ModuleSettings(listOf(PlatformSettings(), InventorySettings(), ShowroomSettings(), CustomersSettings()), tenantContext)
    private val sequences = DocumentSequenceService(actors, tx)
    private val authorizer = PermissionAuthorizer(actors, tx)
    private val ledger = StockLedgerRepository(notify, hierarchy)
    private val prices = PriceListRepository()
    private val stockLocations = StockLocations()
    private val register = ShowroomRegisterService(tx, tenantContext, actors, access, settings, sequences, events)
    private val intake = StockIntakeService(tx, tenantContext, actors, authorizer, access, settings, sequences, ledger, prices, stockLocations,
        com.zifiq.inventory.service.StockItemCodes(sequences, tenantContext), hierarchy, register, ReferenceNames(), TaxClassificationRepository(), events)
    private val moves = AreaMoveService(tx, tenantContext, actors, access, settings, sequences, ledger, stockLocations, events)
    private val support = CustomerSupport(actors, authorizer, tenantContext)
    private val customFields = CustomFieldValidator(tenantContext)
    private val customers = CustomerService(support, tx, access, customFields, sequences, events, notify)
    private val customerSpi = CustomerSpiService(support, tenantContext, CustomerListService(support, tx, access, customFields, settings), sequences, settings, events)
    private val wallets = WalletLedgerService(tenantContext, actors, events)
    private val returns = CustomerReturnService(tx, tenantContext, actors, authorizer, access, settings, sequences, ledger, prices, stockLocations, hierarchy,
        register, customerSpi, customerSpi, wallets, events, notify)
    private val reports = ShowroomReportService(tx, tenantContext, actors, authorizer, access, settings, hierarchy, register, events)
    private val priceList = PriceListService(tx, tenantContext, actors, authorizer, access, settings, sequences, events, notify)
    private val today = LocalDate.now(ZoneId.of("Africa/Lagos"))

    private fun signIn(user: UUID = admin) {
        tenantContext.set(tenantId)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build())
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    private fun showroom(name: String, active: Boolean = true) =
        register.create(ShowroomRequest(name, "NG-LA", "48 Allen Ave", "Ikeja", "0803 000 1111", active = active))

    private fun area(showroom: ShowroomDetail, kind: String) = showroom.areas.first { it.kind == kind }.id

    private fun balance(location: UUID, item: UUID) = tx.execute { ledger.currentBalance(location, item) }

    private fun stock(location: UUID, item: UUID, qty: String) = tx.execute {
        ledger.postEntry(tenantId, location, item, "REFILL", "RF-T", qtyIn = BigDecimal(qty), performedByUserId = admin)
    }

    private fun publish(item: UUID, retail: String, dealer: String) = runCatching {
        priceList.publish(PricePublishRequest(today, null, listOf(PriceEntryInput(item, BigDecimal(retail), BigDecimal(dealer)))))
    }

    @Test
    fun `a new showroom gets its areas, defaults and a unique name`() {
        signIn()
        val kano = showroom("Showroom Kano", active = false)
        assertThat(kano.code).startsWith("SHR-")
        assertThat(kano.status).isEqualTo("PENDING")
        assertThat(kano.areas.map { it.code }).containsExactly("${kano.code}-SF", "${kano.code}-WH")
        assertThat(kano.defaultReceivingLocationId).isEqualTo(area(kano, "WAREHOUSE"))
        assertThat(kano.defaultSellingLocationId).isEqualTo(area(kano, "SHOP_FLOOR"))
        failsWith { showroom("showroom kano") }.isEqualTo(ErrorCode.DUPLICATE_NAME)
        failsWith { register.create(ShowroomRequest("Bad State", "NG-XX", "1 Road", "Town", "08030000000")) }.isEqualTo(ErrorCode.INVALID_STATE_CODE)

        val extra = register.createArea(kano.id, AreaRequest("Cold Room", "OTHER"))
        assertThat(extra.areas.map { it.code }).contains("${kano.code}-A1")
        stock(area(kano, "WAREHOUSE"), citrus, "5")
        failsWith { register.changeAreaStatus(area(kano, "WAREHOUSE"), "SUSPENDED") }.isEqualTo(ErrorCode.AREA_HAS_STOCK)
        val suspended = register.changeStatus(kano.id, ShowroomStatusRequest("SUSPENDED", "Renovation"))
        assertThat(suspended.warnings.single()).contains("5 units")
    }

    @Test
    fun `direct entries create showroom-only items, restock the same item and price it`() {
        signIn()
        val ikeja = showroom("Direct Ikeja")
        val other = showroom("Direct Lekki")
        val request = DirectEntryRequest(ikeja.id, null, null, "Independent local vendor", "INV-77", null, today, null,
            listOf(DirectEntryLineInput("Local Groundnuts 500g", BigDecimal(20), cartons, BigDecimal(800), BigDecimal("7.5"), BigDecimal(1200), BigDecimal(1100))))
        val first = intake.directEntry(request)
        assertThat(first.ref).startsWith("DE-")
        assertThat(first.intoLocationId).isEqualTo(area(ikeja, "WAREHOUSE"))
        assertThat(first.totalInputVat).isEqualByComparingTo("1200") // 16,000 × 7.5%
        val itemId = first.lines.single().stockItemId
        val second = intake.directEntry(request.copy(lines = listOf(request.lines.single().copy(quantity = BigDecimal(5), description = "local groundnuts 500G"))))
        assertThat(second.lines.single().stockItemId).isEqualTo(itemId)
        assertThat(balance(area(ikeja, "WAREHOUSE"), itemId)).isEqualByComparingTo("25")
        assertThat(tx.execute { prices.currentPrice(itemId, today) }!!.dealerPrice).isEqualByComparingTo("1100")

        // A different price on the same day can't replace one already in force: warned, not changed.
        val repriced = intake.directEntry(request.copy(lines = listOf(request.lines.single().copy(sellingPrice = BigDecimal(1300), dealerPrice = BigDecimal(1200)))))
        assertThat(repriced.warnings).hasSize(1)
        assertThat(tx.execute { prices.currentPrice(itemId, today) }!!.retailPrice).isEqualByComparingTo("1200")

        failsWith { stock(area(other, "WAREHOUSE"), itemId, "1") }.isEqualTo(ErrorCode.ITEM_LOCATION_RESTRICTED)
        failsWith { intake.directEntry(request.copy(supplierName = null)) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        assertThat(intake.list("DIRECT_ENTRY", ListSearchRequest(q = "groundnuts")).items.map { it.ref }).contains(first.ref, second.ref)
    }

    @Test
    fun `outsource receipts accept only registered outsourced products at their registered cost`() {
        signIn()
        val ikeja = showroom("Outsource Ikeja")
        val receipt = intake.outsourceReceipt(OutsourceReceiptRequest(ikeja.id, null, partner, "PW-55", "Musa", today,
            listOf(OutsourceLineInput(outsourced, BigDecimal(10), batchNo = "B1", expiryDate = today.plusMonths(6)))))
        assertThat(receipt.ref).startsWith("OS-")
        assertThat(receipt.lines.single().unitCost).isEqualByComparingTo("2000")
        assertThat(receipt.partnerName).isEqualTo("Partner Bottlers")
        failsWith { intake.outsourceReceipt(OutsourceReceiptRequest(ikeja.id, null, partner, null, null, today, listOf(OutsourceLineInput(citrus, BigDecimal.ONE)))) }
            .isEqualTo(ErrorCode.ITEM_NOT_OUTSOURCED)
        failsWith { intake.outsourceReceipt(OutsourceReceiptRequest(ikeja.id, null, partner, null, null, today, listOf(OutsourceLineInput(outsourcedNoCost, BigDecimal.ONE)))) }
            .isEqualTo(ErrorCode.OUTSOURCE_COST_NOT_SET)
        failsWith { intake.outsourceReceipt(OutsourceReceiptRequest(ikeja.id, null, partner, null, null, today, listOf(OutsourceLineInput(outsourced, BigDecimal.ONE, expiryDate = today)))) }
            .isEqualTo(ErrorCode.INVALID_EXPIRY)
    }

    @Test
    fun `area moves stay within one showroom and quarantine needs a reason to release`() {
        signIn()
        val ikeja = showroom("Moves Ikeja")
        val other = showroom("Moves Lekki")
        stock(area(ikeja, "WAREHOUSE"), citrus, "30")
        val move = moves.move(AreaMoveRequest(area(ikeja, "WAREHOUSE"), area(ikeja, "SHOP_FLOOR"), null, null, listOf(AreaMoveLineInput(citrus, BigDecimal(20)))))
        assertThat(move.ref).startsWith("AM-")
        assertThat(balance(area(ikeja, "SHOP_FLOOR"), citrus)).isEqualByComparingTo("20")
        assertThat(balance(area(ikeja, "WAREHOUSE"), citrus)).isEqualByComparingTo("10")
        failsWith { moves.move(AreaMoveRequest(area(ikeja, "WAREHOUSE"), area(other, "SHOP_FLOOR"), null, null, listOf(AreaMoveLineInput(citrus, BigDecimal.ONE)))) }
            .isEqualTo(ErrorCode.NOT_SAME_LOCATION)
        failsWith { moves.move(AreaMoveRequest(area(ikeja, "WAREHOUSE"), area(ikeja, "SHOP_FLOOR"), null, null, listOf(AreaMoveLineInput(citrus, BigDecimal(99))))) }
            .isEqualTo(ErrorCode.INSUFFICIENT_STOCK)

        val quarantine = tx.execute { register.quarantineArea(ikeja.id) }
        stock(quarantine, citrus, "2")
        failsWith { moves.move(AreaMoveRequest(quarantine, area(ikeja, "SHOP_FLOOR"), null, null, listOf(AreaMoveLineInput(citrus, BigDecimal.ONE)))) }
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
        assertThat(moves.move(AreaMoveRequest(quarantine, area(ikeja, "SHOP_FLOOR"), null, "Inspected, fine", listOf(AreaMoveLineInput(citrus, BigDecimal.ONE)))).ref).startsWith("AM-")
    }

    @Test
    fun `returns credit the wallet at today's price, restock resaleable goods and can be reversed only while unspent`() {
        signIn()
        val ikeja = showroom("Returns Ikeja")
        publish(citrus, "4500", "4100")

        // Walk-in with no record: a temporary customer is created and credited for every line, write-offs included.
        val walkIn = returns.post(ReturnRequest("NON_DEALER", ikeja.id, null, WalkInCustomer("Ada Nwosu", "0809 111 2299"), "RCT-552", null, null, listOf(
            ReturnLineInput(citrus, BigDecimal.ONE, "PRODUCT_DEFECT", "RESALEABLE"),
            ReturnLineInput(citrus, BigDecimal.ONE, "DAMAGED_IN_TRANSIT", "DAMAGED_WRITE_OFF"),
        )))
        assertThat(walkIn.ref).startsWith("RT-")
        assertThat(walkIn.customer.temporary).isTrue()
        assertThat(walkIn.walletCredit).isEqualByComparingTo("9000")
        assertThat(walkIn.writeOffValue).isEqualByComparingTo("4500")
        assertThat(balance(area(ikeja, "WAREHOUSE"), citrus)).isEqualByComparingTo("1")

        // Dealers are credited at the dealer price; quarantined goods go to the (auto-created) quarantine area.
        val dealerId = customers.register(CustomerRequest(type = CustomerType.AUTHORISED_DEALER, name = "Returns Dealer Ltd", contactPerson = "Bola",
            phone = "08031234500", streetAddress = "1 Road", stateCode = "NG-LA", dealerCategoryId = goldCategory())).id
        val dealerReturn = returns.post(ReturnRequest("DEALER", ikeja.id, dealerId, null, "INV-9", null, null,
            listOf(ReturnLineInput(citrus, BigDecimal(2), "EXPIRED_OR_NEAR_EXPIRY", "QUARANTINE"))))
        assertThat(dealerReturn.totalValue).isEqualByComparingTo("8200")
        assertThat(dealerReturn.lines.single().intoLocationName).endsWith("Quarantine")
        failsWith { returns.post(ReturnRequest("NON_DEALER", ikeja.id, dealerId, null, null, null, null, listOf(ReturnLineInput(citrus, BigDecimal.ONE, "PRODUCT_DEFECT", "RESALEABLE")))) }
            .isEqualTo(ErrorCode.USE_DEALER_RETURN)
        failsWith { returns.post(ReturnRequest("DEALER", ikeja.id, walkIn.customer.id, null, null, null, null, listOf(ReturnLineInput(citrus, BigDecimal.ONE, "PRODUCT_DEFECT", "RESALEABLE")))) }
            .isEqualTo(ErrorCode.NOT_A_DEALER)
        failsWith { returns.post(ReturnRequest("NON_DEALER", ikeja.id, null, WalkInCustomer("Bo", "08090000001"), null, null, null, listOf(ReturnLineInput(outsourcedNoCost, BigDecimal.ONE, "PRODUCT_DEFECT", "RESALEABLE")))) }
            .isEqualTo(ErrorCode.PRICE_NOT_SET)
        val pending = showroom("Returns Pending", active = false)
        failsWith { returns.post(ReturnRequest("DEALER", pending.id, dealerId, null, null, null, null, listOf(ReturnLineInput(citrus, BigDecimal.ONE, "PRODUCT_DEFECT", "RESALEABLE")))) }
            .isEqualTo(ErrorCode.LOCATION_NOT_TRADING)

        // Reversal fails once the credit is spent, and nothing changes.
        tx.execute { wallets.debit(LedgerPosting(walkIn.customer.id, BalanceType.WALLET, LedgerEntryType.PURCHASE_DEBIT, BigDecimal(5000), "TXN-1", "Sale", "Wallet", "test", "POS_SALE", null, ikeja.id)) }
        failsWith { returns.reverse(walkIn.id, "Entered by mistake") }.isEqualTo(ErrorCode.INSUFFICIENT_WALLET_BALANCE)
        assertThat(returns.get(walkIn.id).status).isEqualTo("POSTED")
        assertThat(balance(area(ikeja, "WAREHOUSE"), citrus)).isEqualByComparingTo("1")

        // The dealer's credit is unspent: reversal takes the money and the goods back.
        val reversed = returns.reverse(dealerReturn.id, "Wrong customer")
        assertThat(reversed.status).isEqualTo("REVERSED")
        assertThat(balance(dealerReturn.lines.single().intoLocationId!!, citrus)).isEqualByComparingTo("0")
        failsWith { returns.reverse(dealerReturn.id, "Again") }.isEqualTo(ErrorCode.RETURN_ALREADY_REVERSED)

        // Return Reports: posted returns by default, with totals by reason.
        val report = returns.list(ListSearchRequest(params = mapOf("locationId" to listOf(ikeja.id.toString()))))
        assertThat(report.items.map { it.ref }.toSet()).containsExactly(walkIn.ref)
        assertThat(report.items.map { it.creditedTo }.toSet()).containsExactly("Temporary wallet")
        assertThat(report.summary!!["writeOffValue"] as BigDecimal).isEqualByComparingTo("4500")
        assertThat(returns.list(ListSearchRequest(q = "ada")).items.map { it.ref }).contains(walkIn.ref)
    }

    @Test
    fun `the comprehensive stock report shows areas, retail value, and costs only to those who may see them`() {
        signIn()
        val ikeja = showroom("Report Ikeja")
        publish(citrus, "4500", "4100")
        stock(area(ikeja, "WAREHOUSE"), citrus, "29")
        stock(area(ikeja, "SHOP_FLOOR"), citrus, "20")

        val asAdmin = reports.comprehensiveStock(ListSearchRequest(params = mapOf("locationId" to listOf(ikeja.id.toString()))))
        assertThat(asAdmin.items.map { it.area to it.qty.toInt() }).containsExactlyInAnyOrder("Warehouse" to 29, "Shop Floor" to 20)
        assertThat(asAdmin.summary!!["costVisible"]).isEqualTo(true)
        assertThat(asAdmin.items.first().unitCost).isEqualByComparingTo("3000")
        assertThat(asAdmin.summary!!["retailValue"] as BigDecimal).isEqualByComparingTo("220500")

        signIn(keeper)
        val asKeeper = reports.comprehensiveStock(ListSearchRequest(params = mapOf("locationId" to listOf(ikeja.id.toString()))))
        assertThat(asKeeper.summary!!["costVisible"]).isEqualTo(false)
        assertThat(asKeeper.items.map { it.unitCost.signum() }.toSet()).containsExactly(0)
        assertThat(asKeeper.items.size).isEqualTo(asAdmin.items.size)
    }

    @Test
    fun `a single-location user only sees and acts at their showroom`() {
        signIn()
        val mine = showroom("Scope Mine")
        val theirs = showroom("Scope Theirs")
        transaction(database) {
            exec("select set_config('app.tenant_id', '$tenantId', true)")
            exec("insert into user_location_assignments (tenant_id, user_id, location_id, access_scope, assigned_by_user_id) values ('$tenantId', '$keeper', '${mine.id}', 'SINGLE_LOCATION', '$admin') " +
                "on conflict (tenant_id, user_id) do update set location_id = excluded.location_id, access_scope = excluded.access_scope")
            // Written directly, not through MemberService, so the cached scope is cleared by hand.
            com.zifiq.platform.security.AccessCaches.invalidateAll()
        }
        stock(area(theirs, "WAREHOUSE"), citrus, "5")
        signIn(keeper)
        assertThat(register.list(null, emptySet(), emptySet()).items.map { it.id }).containsExactly(mine.id)
        failsWith { register.get(theirs.id) }.isEqualTo(ErrorCode.SHOWROOM_NOT_FOUND)
        failsWith { moves.move(AreaMoveRequest(area(theirs, "WAREHOUSE"), area(theirs, "SHOP_FLOOR"), null, null, listOf(AreaMoveLineInput(citrus, BigDecimal.ONE)))) }
            .isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
        transaction(database) {
            exec("select set_config('app.tenant_id', '$tenantId', true)")
            exec("delete from user_location_assignments where user_id = '$keeper'")
            com.zifiq.platform.security.AccessCaches.invalidateAll()
        }
    }

    private fun goldCategory(): UUID = transaction(database) {
        exec("select set_config('app.tenant_id', '$tenantId', true)")
        exec("insert into dealer_categories (tenant_id, name, discount_pct, suggested_credit_limit, sort_order, created_by_user_id) values " +
            "('$tenantId', 'Gold', 3.5, 1500000, 3, '$admin') on conflict do nothing")
        exec("select id from dealer_categories where name = 'Gold'") { rs -> rs.next(); UUID.fromString(rs.getString(1)) }!!
    }
}
