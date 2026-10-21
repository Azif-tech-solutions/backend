package com.zifiq.cashier

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
            exec("select set_config('app.tenant_id', '$tenantId', true)")
            block()
        }

        private fun org.jetbrains.exposed.sql.Transaction.grant(user: UUID, roleName: String, permissionIds: String) {
            val role = UUID.randomUUID()
            exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenantId', '$roleName', '$admin')")
            exec("insert into role_permissions (role_id, permission_id) " + permissionIds.replace("select id", "select '$role', id"))
            exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$user', '$role', '$admin')")
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
    private val availability = StockAvailability(LocationHierarchy())
    private val support = CashierSupport(tenantContext, actors, permissions, locationAccess, moduleSettings, events)
    private val cashAccounts = CashAccountsSpi(accountSupport)
    private val tills = TillSessionService(support, tx, cashAccounts, StockLocations(), notify)
    private val views = SaleViews(support, customerDirectory, invoices, receipts, cashAccounts, availability)
    private val sales = SaleService(support, tx, tills, SalePricing(PriceListRepository(), vat), views, customerDirectory)
    private val checkout = SaleCheckout(support, tx, sales, tills, views, customerDirectory, walletLedger, noCorrespondents, customerDirectory, invoices, receipts,
        cashAccounts, invoices, availability, stockLedger, StockLocations(), sequences, registry)
    private val voids = SaleVoids(support, tx, views, stockLedger, receipts, walletLedger, invoices, customerDirectory, registry, notify)
    private val documents = SaleDocuments(support, tx, views, checkout, invoices, registry, profiles, PdfRenderer())
    private val catalogue = CatalogueService(support, tx, LocationHierarchy(), availability, customerDirectory)
    private val sweep = StaleSaleSweep(database, tx, support)

    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Africa/Lagos"))

    private fun signIn(user: UUID) {
        tenantContext.set(tenantId)
        val jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).claim("email", "$user@test.com").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @AfterEach
    fun signOut() {
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
        signIn(admin)
        tx.execute {
            walletLedger.credit(LedgerPosting(dealer, BalanceType.WALLET, LedgerEntryType.FUNDING, BigDecimal("20000"), "WF-T", "Test funding", "CASH", "account", "WALLET_FUNDING", null, ikeja))
        }
        signIn(cashierA)
        val sale = cart(dealer, citrus to "10")
        assertThat(sale.priceBasis).isEqualTo("DEALER")
        assertThat(sale.lines.single().unitPrice).isEqualByComparingTo("4100")
        assertThat(sale.allowedMethods).contains("WALLET", "ON_ACCOUNT")
        val total = sale.totals.total
        val wallet = BigDecimal("20000")

        // Over the ₦100,000 credit limit: refused, nothing written (the sale stays open, stock untouched).
        val big = sales.setLines(sale.id, SaleLinesRequest(sale.version, listOf(SaleLineInput(citrus, BigDecimal("40")))))
        val stockBefore = onHand(floor, citrus)
        failsWith {
            checkout.complete(big.id, SaleCompleteRequest(big.version, listOf(SalePaymentInput("WALLET", wallet)), onAccount = big.totals.total - wallet))
        }.isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED)
        assertThat(sales.get(sale.id).status).isEqualTo("OPEN")
        assertThat(onHand(floor, citrus)).isEqualByComparingTo(stockBefore)

        val back = sales.setLines(sale.id, SaleLinesRequest(big.version, listOf(SaleLineInput(citrus, BigDecimal("10")))))
        failsWith { checkout.complete(back.id, SaleCompleteRequest(back.version, listOf(SalePaymentInput("WALLET", wallet)), onAccount = BigDecimal.ONE)) }
            .isEqualTo(ErrorCode.PAYMENT_NOT_SETTLED)
        val done = checkout.complete(back.id, SaleCompleteRequest(back.version, listOf(SalePaymentInput("WALLET", wallet)), onAccount = total - wallet))
        assertThat(done.invoice).isNotNull
        assertThat(done.sale.invoiceNumber).startsWith("INV-")
        assertThat(done.sale.onAccount).isEqualByComparingTo(total - wallet)
        signIn(admin)
        assertThat(customerDirectory.let { tx.execute { it.get(dealer).walletBalance } }).isEqualByComparingTo("0")
        assertThat(sql("select tax_inclusive_amount from invoices where id = '${done.invoice!!.id}'") { it.getBigDecimal(1) }).isEqualByComparingTo(total)
        assertThat(tx.execute { invoices.outstandingFor(dealer) }).isEqualByComparingTo(total - wallet)

        // Void within the window: stock back on the floor, wallet refunded, invoice void.
        val floorBefore = onHand(floor, citrus)
        val voided = voids.void(done.sale.id, SaleVoidRequest("Customer changed their mind at the door"))
        assertThat(voided.status).isEqualTo("VOIDED")
        assertThat(onHand(floor, citrus)).isEqualByComparingTo(floorBefore + BigDecimal(10))
        assertThat(tx.execute { customerDirectory.get(dealer).walletBalance }).isEqualByComparingTo("20000")
        assertThat(sql("select status from invoices where id = '${done.invoice!!.id}'") { it.getString(1) }).isEqualTo("VOID")
        failsWith { voids.void(done.sale.id, SaleVoidRequest("Second attempt at voiding")) }.isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION)
        signIn(cashierA)
        closeTill()
    }

    @Test
    fun `stock, prices, versions and payments are all checked at completion, and a refusal leaves nothing behind`() {
        openTill()
        val sale = cart(null, rice to "50")
        assertThat(sale.lines.single().insufficient).isTrue()
        failsWith { checkout.complete(sale.id, cashFor(sale)) }.isEqualTo(ErrorCode.INSUFFICIENT_STOCK)
        // Stock in the warehouse isn't on the shop floor: it can't be sold from the till.
        stock(rice, "100", at = warehouse)
        signIn(cashierA)
        failsWith { checkout.complete(sale.id, cashFor(sale)) }.isEqualTo(ErrorCode.INSUFFICIENT_STOCK)
        // …unless the cashier says the goods leave from the warehouse.
        val fromWarehouse = cashFor(sale).copy(fromLocationId = warehouse)
        failsWith { checkout.complete(sale.id, fromWarehouse.copy(version = sale.version + 7)) }.isEqualTo(ErrorCode.SALE_CHANGED)
        failsWith { checkout.complete(sale.id, fromWarehouse.copy(payments = listOf(SalePaymentInput("WALLET", sale.totals.total)))) }.isEqualTo(ErrorCode.PAYMENT_METHOD_NOT_ALLOWED)

        // A new price published for today: the cart is re-priced and the cashier must confirm.
        signIn(admin)
        tx.execute { TransactionManager.current().exec("insert into stock_item_prices (tenant_id, stock_item_id, retail_price, dealer_price, effective_from, batch_ref, set_by_user_id) " +
            "values ('$tenantId', '$rice', 31000, 29000, current_date, 'PL-2', '$admin')") }
        signIn(cashierA)
        try {
            failsWith { checkout.complete(sale.id, fromWarehouse) }.isEqualTo(ErrorCode.PRICES_CHANGED)
            val repriced = sales.get(sale.id)
            assertThat(repriced.lines.single().unitPrice).isEqualByComparingTo("31000")
            val warehouseBefore = onHand(warehouse, rice)
            val done = checkout.complete(sale.id, cashFor(repriced).copy(fromLocationId = warehouse))
            assertThat(done.sale.status).isEqualTo("COMPLETED")
            assertThat(onHand(warehouse, rice)).isEqualByComparingTo(warehouseBefore - BigDecimal(50))
        } finally {
            signIn(admin)
            tx.execute { TransactionManager.current().exec("delete from stock_item_prices where batch_ref = 'PL-2' and stock_item_id = '$rice'") }
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
        failsWith { tills.open(TillOpenRequest(tills.current().till.id, BigDecimal.ZERO)) }.isEqualTo(ErrorCode.CASHIER_SESSION_OPEN)
        closeTill()

        // An administrator sees everyone's; an untouched cart is cleared by the sweep.
        signIn(admin)
        assertThat(sales.get(bSale.id).status).isEqualTo("OPEN")
        tx.execute { sweep.sweep(Instant.now().plusSeconds(13 * 3600)) }
        assertThat(sales.get(bSale.id).status).isEqualTo("ABANDONED")
        signIn(cashierB)
        closeTill()
    }
}
