package com.zifiq.cashier

import com.zifiq.cashier.service.ProFormaFilter
import com.zifiq.cashier.dto.LoadProFormaRequest
import com.zifiq.cashier.dto.ProFormaConvertRequest
import com.zifiq.cashier.dto.ProFormaLineInput
import com.zifiq.cashier.dto.ProFormaRequest
import com.zifiq.cashier.dto.InstantDiscountRequest
import com.zifiq.cashier.service.SalesReportService
import com.zifiq.cashier.service.ProFormaService
import com.zifiq.cashier.service.SaleDiscounts
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import com.zifiq.inventory.dto.DriverRequest
import com.zifiq.cashier.dto.SaleSupplyLine
import com.zifiq.cashier.dto.WaybillAcknowledgeRequest
import com.zifiq.cashier.dto.WaybillCancelRequest
import com.zifiq.cashier.dto.WaybillLineInput
import com.zifiq.cashier.dto.WaybillRequest
import com.zifiq.cashier.service.PendingSupplyFilter
import com.zifiq.cashier.service.WaybillFilter
import com.zifiq.cashier.service.PendingSupplyService
import com.zifiq.cashier.service.CustomerWaybillService
import com.zifiq.inventory.service.DriverService
import com.zifiq.inventory.service.StockReservations
import com.zifiq.account.dto.BankAccountRequest
import com.zifiq.account.dto.CashAccountRequest
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.account.service.AccountSupport
import com.zifiq.account.service.AccountingSettings
import com.zifiq.account.service.BankAccountService
import com.zifiq.account.service.CashAccountService
import com.zifiq.account.service.CashAccountsSpi
import com.zifiq.account.service.ExpenseCategoryService
import com.zifiq.account.service.LocationVatService
import com.zifiq.account.service.MoneyMoves
import com.zifiq.account.service.ReceiptService
import com.zifiq.account.service.ReceiptWriter
import com.zifiq.cashier.dto.SaleBuyerInput
import com.zifiq.cashier.dto.SaleCompleteRequest
import com.zifiq.cashier.dto.SaleCustomerRequest
import com.zifiq.cashier.dto.SaleInvoiceRequest
import com.zifiq.cashier.dto.SaleLineInput
import com.zifiq.cashier.dto.SaleLinesRequest
import com.zifiq.cashier.dto.SalePaymentInput
import com.zifiq.cashier.dto.SaleStartRequest
import com.zifiq.cashier.dto.SaleVersionRequest
import com.zifiq.cashier.dto.SaleView
import com.zifiq.cashier.dto.SaleVoidRequest
import com.zifiq.cashier.dto.TillCloseRequest
import com.zifiq.cashier.dto.TillOpenRequest
import com.zifiq.cashier.service.CashierSettings
import com.zifiq.cashier.service.CashierSupport
import com.zifiq.cashier.service.CatalogueService
import com.zifiq.cashier.service.SaleCheckout
import com.zifiq.cashier.service.SaleDocuments
import com.zifiq.cashier.service.SaleFilter
import com.zifiq.cashier.service.SalePricing
import com.zifiq.cashier.service.SaleService
import com.zifiq.cashier.service.SaleViews
import com.zifiq.cashier.service.SaleVoids
import com.zifiq.cashier.service.StaleSaleSweep
import com.zifiq.cashier.service.TillSessionService
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.paging.PageRequest
import com.zifiq.customers.service.CustomerListService
import com.zifiq.customers.service.CustomerSpiService
import com.zifiq.customers.service.CustomerSupport
import com.zifiq.customers.service.CustomersSettings
import com.zifiq.customers.service.WalletLedgerService
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.CorrespondentRef
import com.zifiq.customers.spi.Correspondents
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.inventory.repository.PriceListRepository
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.inventory.service.StockAvailability
import com.zifiq.inventory.service.StockLocations
import com.zifiq.invoicing.service.CreditNoteService
import com.zifiq.invoicing.service.InvoiceBuilder
import com.zifiq.invoicing.service.InvoiceDeliveryService
import com.zifiq.invoicing.service.InvoiceListing
import com.zifiq.invoicing.service.InvoicePaymentService
import com.zifiq.invoicing.service.InvoiceService
import com.zifiq.invoicing.service.InvoiceSupport
import com.zifiq.invoicing.service.InvoicingSettings
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.companyProfiles
import com.zifiq.platform.customfields.CustomFieldValidator
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.PdfRenderer
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.assertj.core.api.AbstractObjectAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Sales & POS slice 1 (docs/09 §4.1–4.2): till sessions, the server-side cart, checkout (stock out,
 * receipts, wallet debits, invoice — all or nothing), void within the window, receipt PDF, and the
 * cashier's view limited to their own sales.
 */
class SalesPosIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val cashierA: UUID = UUID.randomUUID()
        private val cashierB: UUID = UUID.randomUUID()
        private val ikeja: UUID = UUID.randomUUID()
        private val floor: UUID = UUID.randomUUID()
        private val warehouse: UUID = UUID.randomUUID()
        private val factory: UUID = UUID.randomUUID()
        private val cartons: UUID = UUID.randomUUID()
        private val citrus: UUID = UUID.randomUUID()
        private val rice: UUID = UUID.randomUUID()
        private val unpriced: UUID = UUID.randomUUID()
        private val dealer: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin to "Ada Admin", cashierA to "Femi Cashier", cashierB to "Bisi Cashier").forEach { (id, name) ->
                    exec("insert into app_users (id, email, display_name) values ('$id', 'pos-$id@test.com', '$name')")
                }
                exec("insert into tenants (id, name) values ('$tenantId', 'POS Co')")
            }
            inTenant {
                listOf(admin, cashierA, cashierB).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
                grant(admin, "Administrator", "select id from permission_catalog")
                grant(cashierA, "Cashier", "select id from permission_catalog where code in ('cashier.sales.read', 'cashier.sales.manage', 'invoicing.invoice.issue')")
                grant(cashierB, "Cashier B", "select id from permission_catalog where code in ('cashier.sales.read', 'cashier.sales.manage')")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$ikeja', '$tenantId', 'SHOWROOM', 'SHR-1', 'Showroom Ikeja', '$admin')")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id, parent_location_id, area_kind) values " +
                    "('$floor', '$tenantId', 'STORAGE_AREA', 'SHR-1-SF', 'Ikeja shop floor', '$admin', '$ikeja', 'SHOP_FLOOR'), " +
                    "('$warehouse', '$tenantId', 'STORAGE_AREA', 'SHR-1-WH', 'Ikeja warehouse', '$admin', '$ikeja', 'WAREHOUSE')")
                exec("update locations set default_selling_location_id = '$floor' where id = '$ikeja'")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'FAC-1', 'Factory A', '$admin')")
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$cartons', '$tenantId', 'CTN', 'Cartons')")
                fun item(id: UUID, code: String, name: String, vat: String, hsn: String) =
                    exec("insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, vat_category_id, " +
                        "hsn_section_id, isic_section_id, status, created_by_user_id, created_at) values ('$id', '$tenantId', '$factory', 'FINISHED_PRODUCT', '$code', '$name', " +
                        "'$cartons', 0, 1000, (select id from vat_categories where code = '$vat'), (select id from hsn_sections where code = '$hsn'), " +
                        "(select id from isic_sections where code = 'A'), 'ACTIVE', '$admin', now())")
                item(citrus, "ZF-101", "Citrus Drink 50cl", "STANDARD_RATE", "IV")
                item(rice, "ZF-201", "Local Rice 50kg", "ZERO_RATED", "II")
                item(unpriced, "ZF-301", "New Lemonade", "STANDARD_RATE", "IV")
                fun price(id: UUID, retail: String, dealerPrice: String) =
                    exec("insert into stock_item_prices (tenant_id, stock_item_id, retail_price, dealer_price, effective_from, batch_ref, set_by_user_id) " +
                        "values ('$tenantId', '$id', $retail, $dealerPrice, current_date - 30, 'PL-1', '$admin')")
                price(citrus, "4500", "4100")
                price(rice, "30000", "28000")
                val gold = UUID.randomUUID()
                exec("insert into dealer_categories (id, tenant_id, name, created_by_user_id) values ('$gold', '$tenantId', 'Gold', '$admin')")
                exec("insert into customers (id, tenant_id, code, type, name, credit_limit, dealer_category_id, created_by_user_id) values " +
                    "('$dealer', '$tenantId', 'DLR-42', 'AUTHORISED_DEALER', 'Okafor & Sons', 100000, '$gold', '$admin')")
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
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), CustomersSettings(), AccountingSettings(), InvoicingSettings(), CashierSettings()), tenantContext)
    private val events = DomainEvents(tenantContext, actors)
    private val sequences = DocumentSequenceService(actors, tx)
    private val recipients = Recipients(tenantContext)
    private val notify = Notify(tenantContext, recipients)
    private val registry = DocumentRegistry(tenantContext, actors, events)
    private val locationAccess = LocationAccessService(actors)
    private val accountSupport = AccountSupport(tenantContext, actors, permissions, locationAccess, moduleSettings, events)
    private val ledger = MoneyLedgerRepository()
    private val categories = ExpenseCategoryService(accountSupport, tx, sequences)
    private val banks = BankAccountService(accountSupport, tx, ledger, sequences, categories)
    private val cash = CashAccountService(accountSupport, tx, ledger, sequences, categories, notify)
    private val vat = LocationVatService(accountSupport, tx, database, notify)
    private val customerSupport = CustomerSupport(actors, permissions, tenantContext)
    private val customerDirectory = CustomerSpiService(customerSupport, tenantContext,
        CustomerListService(customerSupport, tx, locationAccess, CustomFieldValidator(tenantContext), moduleSettings), sequences, moduleSettings, events)
    private val profiles = companyProfiles(tx, tenantContext, actors, events)
    private val walletLedger = WalletLedgerService(tenantContext, actors, events)
    private val receipts = ReceiptService(accountSupport, tx, ReceiptWriter(accountSupport, MoneyMoves(accountSupport, ledger), sequences, registry),
        emptyList(), registry, PdfRenderer(), profiles, notify, recipients)
    private val noCorrespondents = object : Correspondents {
        override fun get(id: UUID): CorrespondentRef = throw IllegalStateException("no correspondents in this test")
        override fun names(ids: Collection<UUID>): Map<UUID, String> = emptyMap()
        override fun creditableFor(customerId: UUID): CorrespondentRef? = null
        override fun listAssigned(locationId: UUID?): List<CorrespondentRef> = emptyList()
    }
    private val invoiceSupport = InvoiceSupport(tenantContext, actors, permissions, locationAccess, moduleSettings, events)
    private val listing = InvoiceListing(invoiceSupport)
    private val invoicePayments = InvoicePaymentService(invoiceSupport, tx, receipts, walletLedger, notify)
    private val invoices = InvoiceService(invoiceSupport, tx, InvoiceBuilder(invoiceSupport, StockLocations(), vat, customerDirectory, profiles), sequences, registry, notify,
        invoicePayments, InvoiceDeliveryService(invoiceSupport, tx, listing, profiles, customerDirectory, PdfRenderer()),
        CreditNoteService(invoiceSupport, tx, sequences, registry, walletLedger, PdfRenderer(), profiles))

    private val stockLedger = StockLedgerRepository(notify)
    private val hierarchy = LocationHierarchy()
    private val reservations = StockReservations(stockLedger, hierarchy)
    private val availability = StockAvailability(hierarchy, reservations)
    private val drivers = DriverService(tx, tenantContext, actors, events)
    private val support = CashierSupport(tenantContext, actors, permissions, locationAccess, moduleSettings, events)
    private val cashAccounts = CashAccountsSpi(accountSupport)
    private val tills = TillSessionService(support, tx, cashAccounts, StockLocations(), notify)
    private val views = SaleViews(support, customerDirectory, invoices, receipts, cashAccounts, availability)
    private val discounts = SaleDiscounts(support, tx, views, sequences, notify)
    private val sales = SaleService(support, tx, tills, SalePricing(PriceListRepository(), vat), views, customerDirectory, discounts)
    private val checkout = SaleCheckout(support, tx, sales, tills, views, customerDirectory, walletLedger, noCorrespondents, customerDirectory, invoices, receipts,
        cashAccounts, invoices, availability, stockLedger, reservations, discounts, StockLocations(), sequences, registry)
    private val voids = SaleVoids(support, tx, views, stockLedger, reservations, discounts, receipts, walletLedger, invoices, customerDirectory, registry, notify)
    private val documents = SaleDocuments(support, tx, views, checkout, invoices, registry, profiles, PdfRenderer())
    private val catalogue = CatalogueService(support, tx, hierarchy, availability, customerDirectory)
    private val waybills = CustomerWaybillService(support, tx, views, reservations, stockLedger, drivers, StockLocations(), invoices, registry, sequences, PdfRenderer(), profiles)
    private val pendingSupply = PendingSupplyService(support, tx)
    private val proFormas = ProFormaService(support, tx, PriceListRepository(), vat, customerDirectory, availability, reservations, StockLocations(), invoices, invoices, sequences, registry, PdfRenderer(), profiles)
    private val reports = SalesReportService(support, tx)
    private val sweep = StaleSaleSweep(database, tx, support)

    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Africa/Lagos"))

    init {
        tx.executeForTenant(tenantId) { profiles.provision(tenantId, admin) }
    }

    private fun signIn(user: UUID) {
        tenantContext.set(tenantId)
        val jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).claim("email", "$user@test.com").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    /** Every test leaves both cashiers' tills closed, even when it fails half-way. */
    @AfterEach
    fun signOut() {
        listOf(cashierA, cashierB).forEach { cashier -> runCatching { signIn(cashier); closeTill() } }
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    private fun <T> sql(query: String, read: (java.sql.ResultSet) -> T): T =
        tx.executeForTenant(tenantId) { TransactionManager.current().exec(query) { rs -> rs.next(); read(rs) }!! }

    private fun onHand(location: UUID, item: UUID): BigDecimal =
        sql("select coalesce((select quantity_on_hand from stock_balances where location_id = '$location' and stock_item_id = '$item'), 0)") { it.getBigDecimal(1) }

    /** A fresh till at Ikeja with stock on the shop floor, opened by [cashier]. */
    private fun openTill(cashier: UUID = cashierA, float: String = "20000"): UUID {
        signIn(admin)
        val till = cash.register(CashAccountRequest("T-${UUID.randomUUID().toString().take(6)}", "Till", "TILL", ikeja, openingBalance = BigDecimal.ZERO)).id
        stock(citrus, "100")
        stock(rice, "10")
        signIn(cashier)
        tills.open(TillOpenRequest(till, BigDecimal(float)))
        return till
    }

    private fun stock(item: UUID, qty: String, at: UUID = floor) = tx.executeForTenant(tenantId) {
        stockLedger.postEntry(tenantId, at, item, "REFILL", "SEED", qtyIn = BigDecimal(qty), performedByUserId = admin, entryDate = today)
    }

    /** A brand-new priced finished product, so a test's stock assertions don't depend on what other tests left behind. */
    private fun freshItem(retail: String, dealerPrice: String, floorQty: String = "0"): UUID {
        val id = UUID.randomUUID()
        val code = "IT-" + id.toString().take(8)
        tx.executeForTenant(tenantId) {
            TransactionManager.current().exec("insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, vat_category_id, " +
                "hsn_section_id, isic_section_id, status, created_by_user_id, created_at) values ('$id', '$tenantId', '$factory', 'FINISHED_PRODUCT', '$code', 'Item $code', " +
                "'$cartons', 0, 1000, (select id from vat_categories where code = 'STANDARD_RATE'), (select id from hsn_sections where code = 'IV'), " +
                "(select id from isic_sections where code = 'A'), 'ACTIVE', '$admin', now())")
            TransactionManager.current().exec("insert into stock_item_prices (tenant_id, stock_item_id, retail_price, dealer_price, effective_from, batch_ref, set_by_user_id) " +
                "values ('$tenantId', '$id', $retail, $dealerPrice, current_date - 30, 'PL-$code', '$admin')")
        }
        if (BigDecimal(floorQty).signum() > 0) stock(id, floorQty)
        return id
    }

    /** A brand-new authorised dealer with its own clean credit, so credit maths don't depend on other tests' debt. */
    private fun newDealer(limit: String): UUID {
        val id = UUID.randomUUID()
        val cat = UUID.randomUUID()
        tx.executeForTenant(tenantId) {
            TransactionManager.current().exec("insert into dealer_categories (id, tenant_id, name, created_by_user_id) values ('$cat', '$tenantId', 'Cat ${cat.toString().take(6)}', '$admin')")
            TransactionManager.current().exec("insert into customers (id, tenant_id, code, type, name, credit_limit, dealer_category_id, created_by_user_id) values " +
                "('$id', '$tenantId', 'DLR-${id.toString().take(6)}', 'AUTHORISED_DEALER', 'Dealer ${id.toString().take(4)}', $limit, '$cat', '$admin')")
        }
        return id
    }

    private fun closeTill() {
        val current = runCatching { tills.current() }.getOrNull() ?: return
        sales.list(SaleFilter(status = "OPEN"), PageRequest.of(0, 50)).items.forEach { sales.abandon(it.id, SaleVersionRequest(sales.get(it.id).version)) }
        tills.close(current.id, TillCloseRequest(current.running!!.expectedCashNow))
    }

    private fun cart(customer: UUID? = null, vararg lines: Pair<UUID, String>): SaleView {
        val sale = sales.start(SaleStartRequest(customer))
        return sales.setLines(sale.id, SaleLinesRequest(sale.version, lines.map { SaleLineInput(it.first, BigDecimal(it.second)) }))
    }

    private fun cashFor(sale: SaleView, tendered: String? = null) =
        SaleCompleteRequest(sale.version, listOf(SalePaymentInput("CASH", sale.totals.total, tendered?.let(::BigDecimal))))

    @Test
    fun `a walk-in cash sale moves stock off the shop floor, takes cash into the till, and the till closes balanced`() {
        openTill()
        val before = onHand(floor, citrus)
        val sale = cart(null, citrus to "2", rice to "1", citrus to "1")
        // Lines for the same item are merged; prices come from the price list; rice is zero-rated.
        assertThat(sale.lines).hasSize(2)
        val citrusLine = sale.lines.first { it.code == "ZF-101" }
        assertThat(citrusLine.quantity).isEqualByComparingTo("3")
        assertThat(citrusLine.unitPrice).isEqualByComparingTo("4500")
        assertThat(citrusLine.lineNet).isEqualByComparingTo("13500")
        assertThat(sale.lines.first { it.code == "ZF-201" }.vatAmount).isEqualByComparingTo("0")
        assertThat(sale.totals.total).isEqualByComparingTo(sale.lines.fold(BigDecimal.ZERO) { s, l -> s + l.lineTotal })
        assertThat(sale.allowedMethods).containsExactly("CASH", "POS", "FUND_TRANSFER")

        val done = checkout.complete(sale.id, cashFor(sale, tendered = "60000"))
        assertThat(done.sale.status).isEqualTo("COMPLETED")
        assertThat(done.sale.ref).startsWith("TXN-")
        assertThat(done.invoice).isNull()
        assertThat(done.changeGiven).isEqualByComparingTo(BigDecimal("60000") - sale.totals.total)
        assertThat(done.sale.payments.single().receiptRef).startsWith("RCT-")
        assertThat(onHand(floor, citrus)).isEqualByComparingTo(before - BigDecimal(3))

        val session = tills.current()
        assertThat(session.running!!.salesCount).isEqualTo(1)
        assertThat(session.running!!.cashTaken).isEqualByComparingTo(sale.totals.total)
        assertThat(session.running!!.expectedCashNow).isEqualByComparingTo(BigDecimal("20000") + sale.totals.total)

        assertThat(String(documents.receipt(sale.id).pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
        // An invoice on request afterwards, from the sale's own lines.
        val invoiced = documents.invoice(sale.id, SaleInvoiceRequest(SaleBuyerInput("Mrs Adeyemi", phone = "08031234567")))
        assertThat(invoiced.invoiceNumber).startsWith("INV-")
        failsWith { documents.invoice(sale.id, SaleInvoiceRequest(SaleBuyerInput("Again"))) }.isEqualTo(ErrorCode.INVOICE_EXISTS_FOR_SOURCE)

        val closed = tills.close(session.id, TillCloseRequest(BigDecimal("20000") + sale.totals.total))
        assertThat(closed.variance).isEqualByComparingTo("0")
        assertThat(closed.status).isEqualTo("CLOSED")
    }

    @Test
    fun `a dealer pays dealer prices from the wallet and on account, is invoiced to the kobo, and the credit limit holds`() {
        openTill()
        val d = newDealer("100000")
        val item = freshItem("4500", "4100", floorQty = "100")
        signIn(admin)
        tx.execute {
            walletLedger.credit(LedgerPosting(d, BalanceType.WALLET, LedgerEntryType.FUNDING, BigDecimal("20000"), "WF-T", "Test funding", "CASH", "account", "WALLET_FUNDING", null, ikeja))
        }
        signIn(cashierA)
        val sale = cart(d, item to "10")
        assertThat(sale.priceBasis).isEqualTo("DEALER")
        assertThat(sale.lines.single().unitPrice).isEqualByComparingTo("4100")
        assertThat(sale.allowedMethods).contains("WALLET", "ON_ACCOUNT")
        val total = sale.totals.total
        val wallet = BigDecimal("20000")

        // Over the ₦100,000 credit limit: refused, nothing written (the sale stays open, stock untouched).
        val big = sales.setLines(sale.id, SaleLinesRequest(sale.version, listOf(SaleLineInput(item, BigDecimal("40")))))
        val stockBefore = onHand(floor, item)
        failsWith {
            checkout.complete(big.id, SaleCompleteRequest(big.version, listOf(SalePaymentInput("WALLET", wallet)), onAccount = big.totals.total - wallet))
        }.isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED)
        assertThat(sales.get(sale.id).status).isEqualTo("OPEN")
        assertThat(onHand(floor, item)).isEqualByComparingTo(stockBefore)

        val back = sales.setLines(sale.id, SaleLinesRequest(big.version, listOf(SaleLineInput(item, BigDecimal("10")))))
        failsWith { checkout.complete(back.id, SaleCompleteRequest(back.version, listOf(SalePaymentInput("WALLET", wallet)), onAccount = BigDecimal.ONE)) }
            .isEqualTo(ErrorCode.PAYMENT_NOT_SETTLED)
        val done = checkout.complete(back.id, SaleCompleteRequest(back.version, listOf(SalePaymentInput("WALLET", wallet)), onAccount = total - wallet))
        assertThat(done.invoice).isNotNull
        assertThat(done.sale.invoiceNumber).startsWith("INV-")
        assertThat(done.sale.onAccount).isEqualByComparingTo(total - wallet)
        signIn(admin)
        assertThat(customerDirectory.let { tx.execute { it.get(d).walletBalance } }).isEqualByComparingTo("0")
        assertThat(sql("select tax_inclusive_amount from invoices where id = '${done.invoice!!.id}'") { it.getBigDecimal(1) }).isEqualByComparingTo(total)
        assertThat(tx.execute { invoices.outstandingFor(d) }).isEqualByComparingTo(total - wallet)

        // Void within the window: stock back on the floor, wallet refunded, invoice void.
        val floorBefore = onHand(floor, item)
        val voided = voids.void(done.sale.id, SaleVoidRequest("Customer changed their mind at the door"))
        assertThat(voided.status).isEqualTo("VOIDED")
        assertThat(onHand(floor, item)).isEqualByComparingTo(floorBefore + BigDecimal(10))
        assertThat(tx.execute { customerDirectory.get(d).walletBalance }).isEqualByComparingTo("20000")
        assertThat(sql("select status from invoices where id = '${done.invoice!!.id}'") { it.getString(1) }).isEqualTo("VOID")
        failsWith { voids.void(done.sale.id, SaleVoidRequest("Second attempt at voiding")) }.isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION)
        signIn(cashierA)
        closeTill()
    }

    @Test
    fun `stock, prices, versions and payments are all checked at completion, and a refusal leaves nothing behind`() {
        openTill()
        val item = freshItem("30000", "28000", floorQty = "0")
        val sale = cart(null, item to "50")
        assertThat(sale.lines.single().insufficient).isTrue()
        failsWith { checkout.complete(sale.id, cashFor(sale)) }.isEqualTo(ErrorCode.INSUFFICIENT_STOCK)
        // Stock in the warehouse isn't on the shop floor: selling from the floor is short there.
        stock(item, "100", at = warehouse)
        signIn(cashierA)
        failsWith { checkout.complete(sale.id, cashFor(sale)) }.isEqualTo(ErrorCode.INSUFFICIENT_STOCK_AT_AREA)
        // …unless the cashier says the goods leave from the warehouse.
        val fromWarehouse = cashFor(sale).copy(fromLocationId = warehouse)
        failsWith { checkout.complete(sale.id, fromWarehouse.copy(version = sale.version + 7)) }.isEqualTo(ErrorCode.SALE_CHANGED)
        failsWith { checkout.complete(sale.id, fromWarehouse.copy(payments = listOf(SalePaymentInput("WALLET", sale.totals.total)))) }.isEqualTo(ErrorCode.PAYMENT_METHOD_NOT_ALLOWED)

        // A new price published for today: the cart is re-priced and the cashier must confirm.
        signIn(admin)
        tx.execute { TransactionManager.current().exec("insert into stock_item_prices (tenant_id, stock_item_id, retail_price, dealer_price, effective_from, batch_ref, set_by_user_id) " +
            "values ('$tenantId', '$item', 31000, 29000, current_date, 'PL-2', '$admin')") }
        signIn(cashierA)
        try {
            failsWith { checkout.complete(sale.id, fromWarehouse) }.isEqualTo(ErrorCode.PRICES_CHANGED)
            val repriced = sales.get(sale.id)
            assertThat(repriced.lines.single().unitPrice).isEqualByComparingTo("31000")
            val warehouseBefore = onHand(warehouse, item)
            val done = checkout.complete(sale.id, cashFor(repriced).copy(fromLocationId = warehouse))
            assertThat(done.sale.status).isEqualTo("COMPLETED")
            assertThat(onHand(warehouse, item)).isEqualByComparingTo(warehouseBefore - BigDecimal(50))
        } finally {
            signIn(admin)
            tx.execute { TransactionManager.current().exec("delete from stock_item_prices where batch_ref = 'PL-2' and stock_item_id = '$item'") }
            signIn(cashierA)
        }

        // Unpriced items can't go in the cart at all.
        val other = sales.start(SaleStartRequest())
        failsWith { sales.setLines(other.id, SaleLinesRequest(other.version, listOf(SaleLineInput(unpriced, BigDecimal.ONE)))) }.isEqualTo(ErrorCode.NO_PRICE_SET)
        // A till with an open sale can't close.
        failsWith { tills.close(tills.current().id, TillCloseRequest(BigDecimal.ZERO)) }.isEqualTo(ErrorCode.OPEN_SALES_EXIST)
        closeTill()
    }

    @Test
    fun `a cashier sees and touches only their own sales and till, and the catalogue shows what the showroom can sell`() {
        openTill(cashierB)
        val bSale = cart(null, citrus to "1")
        val page = catalogue.catalogue(null, "zf-10", null, null, false, PageRequest.of(0, 20))
        assertThat(page.items.map { it.code }).containsExactly("ZF-101")
        assertThat(page.items.single().available).isGreaterThan(BigDecimal.ZERO)
        assertThat(catalogue.catalogue(null, null, null, dealer, false, PageRequest.of(0, 20)).items.first { it.code == "ZF-101" }.price).isEqualByComparingTo("4100")
        // The unpriced item never appears; nor does anything with no stock unless asked.
        assertThat(catalogue.catalogue(null, null, null, null, true, PageRequest.of(0, 50)).items.map { it.code }).doesNotContain("ZF-301")

        openTill(cashierA)
        failsWith { sales.get(bSale.id) }.isEqualTo(ErrorCode.SALE_NOT_FOUND)
        failsWith { sales.setLines(bSale.id, SaleLinesRequest(bSale.version, emptyList())) }.isEqualTo(ErrorCode.SALE_NOT_FOUND)
        failsWith { tills.open(TillOpenRequest(tills.current().till.id, BigDecimal.ZERO)) }.isEqualTo(ErrorCode.TILL_ALREADY_OPEN)
        signIn(admin)
        val freeTill = cash.register(CashAccountRequest("T-${UUID.randomUUID().toString().take(6)}", "Spare till", "TILL", ikeja, openingBalance = BigDecimal.ZERO)).id
        signIn(cashierA)
        failsWith { tills.open(TillOpenRequest(freeTill, BigDecimal.ZERO)) }.isEqualTo(ErrorCode.CASHIER_SESSION_OPEN)
        closeTill()

        // An administrator sees everyone's; an untouched cart is cleared by the sweep.
        signIn(admin)
        assertThat(sales.get(bSale.id).status).isEqualTo("OPEN")
        tx.execute { sweep.sweep(Instant.now().plusSeconds(13 * 3600)) }
        assertThat(sales.get(bSale.id).status).isEqualTo("ABANDONED")
        signIn(cashierB)
        closeTill()
    }

    @Test
    fun `a sale can defer part of its goods - the rest is reserved, drops availability, and a waybill releases it`() {
        openTill()
        val d = newDealer("1000000")
        val item = freshItem("4500", "4100", floorQty = "100")
        val sale = cart(d, item to "10")
        val total = sale.totals.total
        // Supply 4 now on account; reserve 6 for later delivery.
        val done = checkout.complete(sale.id, SaleCompleteRequest(sale.version, emptyList(), onAccount = total,
            supply = listOf(SaleSupplyLine(1, BigDecimal("4"))), invoiceBuyer = null, creditOverrideReason = null))
        assertThat(done.sale.supplyStatus).isEqualTo("PARTIALLY_SUPPLIED")
        assertThat(done.sale.lines.single().outstanding).isEqualByComparingTo("6")
        assertThat(onHand(floor, item)).isEqualByComparingTo("96")
        signIn(admin)
        // Reserved goods are still on hand (a stocktake counts them) but not available to sell.
        assertThat(tx.execute { availability.onHand(ikeja, listOf(item)) }[item]).isEqualByComparingTo("96")
        assertThat(tx.execute { availability.available(ikeja, listOf(item)) }[item]).isEqualByComparingTo("90")
        signIn(cashierA)

        val position = waybills.lookup(done.sale.ref!!)
        assertThat(position.totals.outstanding).isEqualByComparingTo("6")
        assertThat(position.fullySupplied).isFalse()

        // Release 2 by collection, then the last 4 by delivery.
        val wb1 = waybills.create(WaybillRequest(sale.id, "COLLECTION", null, null, null, "K. Okafor", null, listOf(WaybillLineInput(saleLineId(sale.id, 1), BigDecimal("2")))))
        assertThat(wb1.ref).startsWith("WB-")
        assertThat(onHand(floor, item)).isEqualByComparingTo("94")
        assertThat(sales.get(sale.id).supplyStatus).isEqualTo("PARTIALLY_SUPPLIED")

        signIn(admin)
        val driver = DriverService(tx, tenantContext, actors, events).create(DriverRequest("Musa Bello", "08030000000", "LSR-442-XA"))
        signIn(cashierA)
        val wb2 = waybills.create(WaybillRequest(sale.id, "DELIVERY", null, driver.id, null, null, "14 Ikorodu Rd", listOf(WaybillLineInput(saleLineId(sale.id, 1), BigDecimal("4")))))
        assertThat(sales.get(sale.id).supplyStatus).isEqualTo("FULLY_SUPPLIED")
        assertThat(onHand(floor, item)).isEqualByComparingTo("90")
        failsWith { waybills.create(WaybillRequest(sale.id, "COLLECTION", null, null, null, "Someone", null, listOf(WaybillLineInput(saleLineId(sale.id, 1), BigDecimal.ONE)))) }
            .isEqualTo(ErrorCode.RELEASE_EXCEEDS_OUTSTANDING)

        assertThat(String(waybills.pdf(wb2.id).pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
        val ack = waybills.acknowledge(wb2.id, WaybillAcknowledgeRequest("K. Okafor", null, null))
        assertThat(ack.status).isEqualTo("DELIVERED")
        // Fully-supplied sales drop out of the default pending list.
        assertThat(pendingSupply.report(PendingSupplyFilter(), PageRequest.of(0, 50)).items.map { it.saleRef }).doesNotContain(sale.ref)
        closeTill()
    }

    @Test
    fun `voiding cancels reservations, a released sale cannot be voided, and a cancelled waybill restores stock`() {
        openTill()
        val d = newDealer("1000000")
        val item = freshItem("4500", "4100", floorQty = "100")
        val a = cart(d, item to "10")
        val doneA = checkout.complete(a.id, SaleCompleteRequest(a.version, emptyList(), onAccount = a.totals.total, supply = listOf(SaleSupplyLine(1, BigDecimal("0")))))
        assertThat(doneA.sale.supplyStatus).isEqualTo("NOT_SUPPLIED")
        signIn(admin); assertThat(tx.execute { availability.available(ikeja, listOf(item)) }[item]).isEqualByComparingTo("90"); signIn(cashierA)
        // Void releases the reservation: availability returns to full, nothing was collected to reverse.
        voids.void(a.id, SaleVoidRequest("Order cancelled before any delivery"))
        signIn(admin); assertThat(tx.execute { availability.available(ikeja, listOf(item)) }[item]).isEqualByComparingTo("100"); signIn(cashierA)

        // A second deferred sale, partly released, can't be voided.
        val b = cart(d, item to "4")
        checkout.complete(b.id, SaleCompleteRequest(b.version, emptyList(), onAccount = b.totals.total, supply = listOf(SaleSupplyLine(1, BigDecimal("0")))))
        val wb = waybills.create(WaybillRequest(b.id, "COLLECTION", null, null, null, "Picker", null, listOf(WaybillLineInput(saleLineId(b.id, 1), BigDecimal("4")))))
        assertThat(onHand(floor, item)).isEqualByComparingTo("96")
        failsWith { voids.void(b.id, SaleVoidRequest("Trying to void after releasing goods")) }.isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION)
        // Cancelling the waybill (same day) puts the stock and the reservation back.
        waybills.cancel(wb.id, WaybillCancelRequest("Driver returned, customer not in"))
        assertThat(onHand(floor, item)).isEqualByComparingTo("100")
        assertThat(sales.get(b.id).supplyStatus).isEqualTo("NOT_SUPPLIED")
        closeTill()
    }

    @Test
    fun `a walk-in leaving goods for later must give a contact`() {
        openTill()
        val item = freshItem("4500", "4100", floorQty = "100")
        val sale = cart(null, item to "5")
        failsWith { checkout.complete(sale.id, SaleCompleteRequest(sale.version, listOf(SalePaymentInput("CASH", sale.totals.total)), supply = listOf(SaleSupplyLine(1, BigDecimal("2"))))) }
            .isEqualTo(ErrorCode.WALKIN_DELIVERY_NEEDS_CONTACT)
        // With invoice details (name + phone) it goes through.
        val done = checkout.complete(sale.id, SaleCompleteRequest(sale.version, listOf(SalePaymentInput("CASH", sale.totals.total, sale.totals.total)),
            supply = listOf(SaleSupplyLine(1, BigDecimal("2"))), invoiceBuyer = SaleBuyerInput("Mrs Bello", phone = "08031234567")))
        assertThat(done.sale.supplyStatus).isEqualTo("PARTIALLY_SUPPLIED")
        assertThat(done.invoice).isNotNull
        closeTill()
    }

    @Test
    fun `Instant Discount applies below the ceiling, needs approval above it, blocks completion while pending, and withdraws`() {
        openTill()
        val item = freshItem("1000", "1000", floorQty = "100")
        val sale = cart(null, item to "10")
        assertThat(sale.totals.total).isEqualByComparingTo("10750")  // 10000 + 7.5% VAT

        // 5% is within the 10% ceiling: applied at once by the Administrator.
        signIn(admin)
        val d1 = discounts.apply(InstantDiscountRequest(sale.id, "PERCENT", BigDecimal("5"), "LOYALTY", null, sale.version))
        assertThat(d1.status).isEqualTo("APPLIED")
        val afterDiscount = sales.get(sale.id)
        assertThat(afterDiscount.totals.instantDiscount).isEqualByComparingTo("500")
        assertThat(afterDiscount.totals.net).isEqualByComparingTo("9500")
        assertThat(afterDiscount.totals.vat).isEqualByComparingTo("712.50")
        assertThat(afterDiscount.totals.total).isEqualByComparingTo("10212.50")
        assertThat(afterDiscount.instantDiscount?.ref).startsWith("IDS-")
        failsWith { discounts.apply(InstantDiscountRequest(sale.id, "AMOUNT", BigDecimal("100"), "BULK", null, afterDiscount.version)) }.isEqualTo(ErrorCode.DISCOUNT_ALREADY_ACTIVE)

        // Withdraw restores the full price.
        discounts.withdraw(d1.id)
        val restored = sales.get(sale.id)
        assertThat(restored.totals.instantDiscount).isEqualByComparingTo("0")
        assertThat(restored.totals.total).isEqualByComparingTo("10750")

        // 20% is over the ceiling: a cashier's request waits for Management; completion is blocked meanwhile.
        signIn(cashierA)
        val d2 = discounts.apply(InstantDiscountRequest(sale.id, "PERCENT", BigDecimal("20"), "CLEARANCE", "MD approved", restored.version))
        assertThat(d2.status).isEqualTo("PENDING_APPROVAL")
        failsWith { checkout.complete(sale.id, cashFor(sales.get(sale.id))) }.isEqualTo(ErrorCode.DISCOUNT_PENDING_APPROVAL)
        signIn(admin)
        discounts.approve(d2.id)
        signIn(cashierA)
        val approved = sales.get(sale.id)
        assertThat(approved.totals.instantDiscount).isEqualByComparingTo("2000")
        assertThat(approved.totals.total).isEqualByComparingTo("8600")  // 8000 + 7.5%
        val done = checkout.complete(sale.id, cashFor(approved, tendered = "10000"))
        assertThat(done.sale.status).isEqualTo("COMPLETED")
        closeTill()
    }

    @Test
    fun `a pro-forma quotes dealer prices, converts to a reserved on-account order, loads into a till sale, and shows on the reports`() {
        openTill()
        val item = freshItem("1000", "900", floorQty = "100")
        val d = newDealer("1000000")

        // Create and send a quotation with a line discount.
        val pf = proFormas.create(ProFormaRequest(ikeja, d, null, null, null, "50% deposit", "Delivered", null,
            listOf(ProFormaLineInput(item, BigDecimal("10"), BigDecimal("500")))), send = true)
        assertThat(pf.status).isEqualTo("SENT")
        assertThat(pf.lines.single().unitPrice).isEqualByComparingTo("900")   // dealer price
        assertThat(pf.totals.discount).isEqualByComparingTo("500")
        assertThat(String(proFormas.pdf(pf.id).pdf.copyOfRange(0, 4))).isEqualTo("%PDF")

        // Convert to an order: a reserved PRO_FORMA_ORDER sale invoiced on account.
        val converted = proFormas.convert(pf.id, ProFormaConvertRequest("ISSUED", 30, null, null))
        assertThat(converted.status).isEqualTo("CONVERTED")
        assertThat(converted.convertedSale).isNotNull
        signIn(admin)
        assertThat(tx.execute { availability.available(ikeja, listOf(item)) }[item]).isEqualByComparingTo("90")  // 10 reserved, none moved
        signIn(cashierA)

        // Load a second quotation into a till sale and complete it.
        val pf2 = proFormas.create(ProFormaRequest(ikeja, d, null, null, null, null, null, null,
            listOf(ProFormaLineInput(item, BigDecimal("2"), null))), send = true)
        val open = sales.start(SaleStartRequest())
        val loaded = sales.loadProForma(open.id, LoadProFormaRequest(pf2.id, open.version))
        assertThat(loaded.customerName).contains("Dealer")
        assertThat(loaded.lines.single().unitPrice).isEqualByComparingTo("900")
        val saleDone = checkout.complete(open.id, SaleCompleteRequest(loaded.version, emptyList(), onAccount = loaded.totals.total, supply = listOf(SaleSupplyLine(1, BigDecimal("2")))))
        assertThat(saleDone.sale.status).isEqualTo("COMPLETED")
        assertThat(proFormas.get(pf2.id).status).isEqualTo("CONVERTED")
        assertThat(proFormas.list(ProFormaFilter(customerId = d), PageRequest.of(0, 50)).items.map { it.ref }).contains(pf.ref, pf2.ref)

        // Both completed dealer sales show on the reports.
        signIn(admin)
        val report = reports.salesDaily(ikeja, null, null, today, today)
        assertThat(report.stats.transactions).isGreaterThanOrEqualTo(2)
        assertThat(report.rows.map { it.date }).contains(today)
        val activity = reports.customerActivity("DISTRIBUTORS", d, ikeja, today, today)
        assertThat(activity.rows).hasSize(2)
        assertThat(activity.rows.map { it.onAccount }.all { it.signum() > 0 }).isTrue()
        signIn(cashierA)
        closeTill()
    }

    /** The id of a sale's line by its 1-based number. */
    private fun saleLineId(saleId: UUID, lineNo: Int): UUID =
        tx.execute { com.zifiq.cashier.model.SaleLinesTable.select(com.zifiq.cashier.model.SaleLinesTable.id)
            .where { (com.zifiq.cashier.model.SaleLinesTable.saleId eq saleId) and (com.zifiq.cashier.model.SaleLinesTable.lineNo eq lineNo) }
            .single()[com.zifiq.cashier.model.SaleLinesTable.id].value }
}
