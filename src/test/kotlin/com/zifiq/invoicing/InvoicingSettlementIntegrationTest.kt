package com.zifiq.invoicing

import com.zifiq.common.paging.PageRequest
import com.zifiq.account.dto.BankAccountRequest
import com.zifiq.account.dto.CashAccountRequest
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.account.service.AccountSupport
import com.zifiq.account.service.AccountingSettings
import com.zifiq.account.service.BankAccountService
import com.zifiq.account.service.CashAccountService
import com.zifiq.account.service.ExpenseCategoryService
import com.zifiq.account.service.LocationVatService
import com.zifiq.account.service.MoneyMoves
import com.zifiq.account.service.ReceiptService
import com.zifiq.account.service.ReceiptWriter
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.json.Json
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
import com.zifiq.inventory.service.StockLocations
import com.zifiq.invoicing.dto.CreditNoteLineRequest
import com.zifiq.invoicing.dto.CreditNoteRequest
import com.zifiq.invoicing.dto.DeliveryRequest
import com.zifiq.invoicing.dto.RecordPaymentRequest
import com.zifiq.invoicing.dto.ReissueRequest
import com.zifiq.invoicing.service.CreditNoteService
import com.zifiq.invoicing.service.InvoiceBuilder
import com.zifiq.invoicing.service.InvoiceDeliveryOutcomes
import com.zifiq.invoicing.service.InvoiceDeliveryService
import com.zifiq.invoicing.service.InvoiceListing
import com.zifiq.invoicing.service.InvoicePaymentService
import com.zifiq.invoicing.service.InvoicePdfAttachments
import com.zifiq.invoicing.service.InvoiceQueryService
import com.zifiq.invoicing.service.InvoiceService
import com.zifiq.invoicing.service.InvoiceSupport
import com.zifiq.invoicing.service.InvoicingSettings
import com.zifiq.invoicing.service.NrsPreviewService
import com.zifiq.invoicing.service.OverdueSweep
import com.zifiq.invoicing.service.ReceiptReconciler
import com.zifiq.invoicing.service.ReceivablesService
import com.zifiq.invoicing.service.StatementAttachments
import com.zifiq.invoicing.spi.InvoiceLineRequest
import com.zifiq.invoicing.spi.InvoiceRef
import com.zifiq.invoicing.spi.InvoiceRequest
import com.zifiq.invoicing.spi.SourceType
import com.zifiq.invoicing.spi.WalkInBuyer
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.customfields.CustomFieldValidator
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.events.EventConsumerRunner
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.Recipients
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.settings.service.CompanyProfileService
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipInputStream
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

/** Electronic Invoicing slice 2 — payments, reconciliation, credit notes, delivery, receivables, debtors, overdue, NRS preview (docs/08 §4.5–4.12, §12). */
class InvoicingSettlementIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val ikeja: UUID = UUID.randomUUID()
        private val factory: UUID = UUID.randomUUID()
        private val cartons: UUID = UUID.randomUUID()
        private val citrus: UUID = UUID.randomUUID()
        private val rice: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'settle-$admin@test.com', 'Ada Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Settlement Co')")
            }
            inTenant {
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin')")
                val role = UUID.randomUUID()
                exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenantId', 'Administrator', '$admin')")
                exec("insert into role_permissions (role_id, permission_id) select '$role', id from permission_catalog")
                exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$admin', '$role', '$admin')")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$ikeja', '$tenantId', 'SHOWROOM', 'SHR-1', 'Showroom Ikeja', '$admin')")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'FAC-1', 'Factory A', '$admin')")
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$cartons', '$tenantId', 'CTN', 'Cartons')")
                fun item(id: UUID, code: String, name: String, vat: String, hsn: String) =
                    exec("insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, vat_category_id, " +
                        "hsn_section_id, isic_section_id, status, created_by_user_id, created_at) values ('$id', '$tenantId', '$factory', 'FINISHED_PRODUCT', '$code', '$name', " +
                        "'$cartons', 0, 1000, (select id from vat_categories where code = '$vat'), (select id from hsn_sections where code = '$hsn'), " +
                        "(select id from isic_sections where code = 'A'), 'ACTIVE', '$admin', now())")
                item(citrus, "ZF-101", "Citrus Drink 50cl", "STANDARD_RATE", "IV")
                item(rice, "ZF-201", "Local Rice 50kg", "ZERO_RATED", "II")
            }
        }

        private fun inTenant(block: org.jetbrains.exposed.sql.Transaction.() -> Unit) = transaction(database) {
            exec("select set_config('app.tenant_id', '$tenantId', true)")
            block()
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), CustomersSettings(), AccountingSettings(), InvoicingSettings()), tenantContext)
    private val events = DomainEvents(tenantContext, actors)
    private val sequences = DocumentSequenceService(actors, tx)
    private val recipients = Recipients(tenantContext)
    private val notify = Notify(tenantContext, recipients)
    private val registry = DocumentRegistry(tenantContext, actors, events)
    private val accountSupport = AccountSupport(tenantContext, actors, permissions, LocationAccessService(actors), moduleSettings, events)
    private val ledger = MoneyLedgerRepository()
    private val categories = ExpenseCategoryService(accountSupport, tx, sequences)
    private val banks = BankAccountService(accountSupport, tx, ledger, sequences, categories)
    private val cash = CashAccountService(accountSupport, tx, ledger, sequences, categories, notify)
    private val vat = LocationVatService(accountSupport, tx, database, notify)
    private val customerSupport = CustomerSupport(actors, permissions, tenantContext)
    private val customerDirectory = CustomerSpiService(customerSupport, tenantContext,
        CustomerListService(customerSupport, tx, LocationAccessService(actors), CustomFieldValidator(tenantContext), moduleSettings), sequences, moduleSettings, events)
    private val profiles = CompanyProfileService(tx, tenantContext, actors, events)
    private val walletLedger = WalletLedgerService(tenantContext, actors, events)
    private val receipts = ReceiptService(accountSupport, tx, ReceiptWriter(accountSupport, MoneyMoves(accountSupport, ledger), sequences, registry),
        emptyList(), registry, PdfRenderer(), profiles, notify, recipients)
    private val noCorrespondents = object : Correspondents {
        override fun get(id: UUID): CorrespondentRef = throw IllegalStateException("no correspondents in this test")
        override fun names(ids: Collection<UUID>): Map<UUID, String> = emptyMap()
        override fun creditableFor(customerId: UUID): CorrespondentRef? = null
        override fun listAssigned(locationId: UUID?): List<CorrespondentRef> = emptyList()
    }

    private val support = InvoiceSupport(tenantContext, actors, permissions, LocationAccessService(actors), moduleSettings, events)
    private val listing = InvoiceListing(support)
    private val builder = InvoiceBuilder(support, StockLocations(), vat, customerDirectory, profiles)
    private val queries = InvoiceQueryService(support, tx, listing, registry, PdfRenderer(), profiles, customerDirectory, receipts)
    private val payments = InvoicePaymentService(support, tx, receipts, walletLedger, notify)
    private val deliveries = InvoiceDeliveryService(support, tx, listing, profiles, customerDirectory, PdfRenderer())
    private val creditNotes = CreditNoteService(support, tx, sequences, registry, walletLedger, PdfRenderer(), profiles)
    private val invoices = InvoiceService(support, tx, builder, sequences, registry, notify, payments, deliveries, creditNotes)
    private val receivables = ReceivablesService(support, tx, listing, customerDirectory, noCorrespondents)
    private val nrs = NrsPreviewService(support, tx)
    private val sweep = OverdueSweep(database, tx, support, listing, deliveries, notify, noCorrespondents)
    private val reconciler = ReceiptReconciler(payments)
    private val runner = EventConsumerRunner(database, tx, listOf(reconciler))

    private val ordinary: UUID = UUID.randomUUID()
    private val special: UUID = UUID.randomUUID()
    private val tag = ordinary.toString().take(6)

    init {
        tx.executeForTenant(tenantId) { profiles.provision(tenantId, admin) }
        inTenant {
            exec("insert into customers (id, tenant_id, code, type, name, email, tin, credit_limit, created_by_user_id) values " +
                "('$ordinary', '$tenantId', 'CU-O$tag', 'ORDINARY_CUSTOMER', 'Okafor Stores $tag', 'orders-$tag@okafor.ng', '2199-$tag', 50000, '$admin')")
            exec("insert into customers (id, tenant_id, code, type, name, created_by_user_id) values ('$special', '$tenantId', 'CU-S$tag', 'SPECIAL_CUSTOMER', 'Grace Foods $tag', '$admin')")
        }
    }

    private fun signIn() {
        tenantContext.set(tenantId)
        val jwt = Jwt.withTokenValue("test").header("alg", "none").subject(admin.toString()).claim("email", "$admin@test.com").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    /** Scheduled jobs and the outbox run with no one signed in. */
    private fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    @AfterEach
    fun cleanUp() = signOut()

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Africa/Lagos"))
    private var counter = 0

    private fun line(item: UUID, quantity: String, price: String) = InvoiceLineRequest(item, BigDecimal(quantity), BigDecimal(price))

    private fun issue(
        customer: UUID? = ordinary,
        walkIn: WalkInBuyer? = null,
        lines: List<InvoiceLineRequest> = listOf(line(citrus, "12", "4100")),
        issueDate: LocalDate = today,
        terms: Int? = null,
        sourceId: UUID = UUID.randomUUID(),
    ): InvoiceRef = tx.execute {
        invoices.issue(InvoiceRequest(SourceType.POS_SALE, sourceId, "TXN-${++counter}-${sourceId.toString().take(4)}", issueDate, null, ikeja,
            customer, walkIn, issueDate, terms, null, lines))
    }

    private fun till(): UUID = cash.register(CashAccountRequest("T-${UUID.randomUUID().toString().take(6)}", "Till", "TILL", ikeja, openingBalance = BigDecimal.ZERO)).id

    private fun bank(): UUID = banks.register(BankAccountRequest(bankName = "GTBank", accountName = "Settlement Co", accountNumber = "55${(10000000..99999999).random()}",
        accountType = "COLLECTIONS", openingBalance = BigDecimal("1000000"), openingBalanceDate = today)).id

    private fun cashPayment(invoice: UUID, amount: String, till: UUID) = payments.record(invoice, RecordPaymentRequest("CASH", BigDecimal(amount), cashAccountId = till))

    private fun sql(statement: String) = tx.executeForTenant(tenantId) { TransactionManager.current().exec(statement) }

    private fun count(query: String): Long = tx.executeForTenant(tenantId) { TransactionManager.current().exec(query) { rs -> rs.next(); rs.getLong(1) }!! }

    private fun walletOf(customer: UUID): BigDecimal =
        tx.executeForTenant(tenantId) { TransactionManager.current().exec("select wallet_balance from customers where id = '$customer'") { rs -> rs.next(); rs.getBigDecimal(1) }!! }

    private fun events(type: String, invoice: UUID) = count("select count(*) from domain_events where event_type = '$type' and aggregate_id = '$invoice'")

    @Test
    fun `cash and transfer payments settle an invoice, and a declined transfer brings the balance back`() {
        signIn()
        val till = till()
        val bank = bank()
        val invoice = issue()

        val first = cashPayment(invoice.id, "30000", till)
        assertThat(first.confirmationStatus).isEqualTo("NOT_REQUIRED")
        queries.detail(invoice.id).let {
            assertThat(it.displayStatus).isEqualTo("Partially Paid")
            assertThat(it.totals.paid).isEqualByComparingTo("30000")
            assertThat(it.totals.balance).isEqualByComparingTo("22890")
        }
        failsWith { cashPayment(invoice.id, "22890.01", till) }.isEqualTo(ErrorCode.OVERPAYMENT)

        val transfer = payments.record(invoice.id, RecordPaymentRequest("FUND_TRANSFER", BigDecimal("22890"), receivingBankAccountId = bank, payerReference = "GTB/77310"))
        assertThat(transfer.confirmationStatus).isEqualTo("PENDING")
        queries.detail(invoice.id).let {
            assertThat(it.displayStatus).isEqualTo("Paid")
            assertThat(it.totals.pendingConfirmation).isEqualByComparingTo("22890")
            assertThat(it.linked.payments.map { p -> p.status }).containsExactly("Confirmed", "Pending confirmation")
        }
        failsWith { cashPayment(invoice.id, "1", till) }.isEqualTo(ErrorCode.INVOICE_ALREADY_PAID)
        assertThat(events("invoicing.invoice.paid", invoice.id)).isEqualTo(1)
        assertThat(count("select count(*) from payment_receipts where source_type = 'INVOICE_PAYMENT' and source_id = '${invoice.id}'")).isEqualTo(2)

        // Accounts finds no transfer on the statement: the reconciler puts the balance back.
        receipts.decline(transfer.receiptId, "No credit on the statement")
        signOut()
        runner.drain(reconciler, tenantId)
        runner.drain(reconciler, tenantId)
        signIn()
        queries.detail(invoice.id).let {
            assertThat(it.totals.paid).isEqualByComparingTo("30000")
            assertThat(it.displayStatus).isEqualTo("Partially Paid")
            assertThat(it.linked.payments.map { p -> p.status }).containsExactly("Confirmed", "Declined")
        }
        assertThat(events("invoicing.payment.reversed", invoice.id)).isEqualTo(1)
    }

    @Test
    fun `a wallet payment debits the wallet, and reversing it credits the wallet back`() {
        signIn()
        tx.execute {
            // A non-dealer's wallet holds return credits (only dealers fund theirs directly).
            walletLedger.credit(LedgerPosting(ordinary, BalanceType.WALLET, LedgerEntryType.RETURN_CREDIT, BigDecimal("10000"), "RT-T$tag", "Test return credit", null,
                "showroom", "CUSTOMER_RETURN", UUID.randomUUID(), ikeja))
        }
        val invoice = issue()
        failsWith { payments.record(invoice.id, RecordPaymentRequest("WALLET", BigDecimal("20000"))) }.isEqualTo(ErrorCode.INSUFFICIENT_WALLET_BALANCE)
        val paid = payments.record(invoice.id, RecordPaymentRequest("WALLET", BigDecimal("10000")))
        assertThat(walletOf(ordinary)).isEqualByComparingTo("0")
        assertThat(queries.detail(invoice.id).totals.paid).isEqualByComparingTo("10000")

        val walkIn = issue(customer = null, walkIn = WalkInBuyer("Ada Walk-in"))
        failsWith { payments.record(walkIn.id, RecordPaymentRequest("WALLET", BigDecimal("100"))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)

        val after = queries.detail(payments.reverse(invoice.id, paid.paymentId, "Charged to the wrong invoice"))
        assertThat(walletOf(ordinary)).isEqualByComparingTo("10000")
        assertThat(after.totals.paid).isEqualByComparingTo("0")
        assertThat(after.linked.payments.single().status).isEqualTo("Voided")
        assertThat(count("select count(*) from payment_receipts where id = '${paid.receiptId}' and status = 'VOIDED'")).isEqualTo(1)
        failsWith { payments.reverse(invoice.id, paid.paymentId, "Again") }.isEqualTo(ErrorCode.ALREADY_REVERSED)
    }

    @Test
    fun `void-and-reissue moves the payments to the replacement, and a sale void voids them`() {
        signIn()
        val till = till()
        val source = UUID.randomUUID()
        val original = issue(sourceId = source)
        val payment = cashPayment(original.id, "10000", till)
        failsWith { invoices.void(original.id, "Customer changed their mind", null) }.isEqualTo(ErrorCode.INVOICE_HAS_PAYMENTS)

        val replacementId = invoices.void(original.id, "Billed to the wrong customer", ReissueRequest(customerId = special))
        val replacement = queries.detail(replacementId)
        assertThat(replacement.buyer.customerId).isEqualTo(special)
        assertThat(replacement.totals.paid).isEqualByComparingTo("10000")
        assertThat(replacement.linked.payments.single().source).isEqualTo("REISSUE_TRANSFER")
        assertThat(replacement.linked.payments.single().receiptId).isEqualTo(payment.receiptId)

        tx.execute { invoices.voidForSource("POS_SALE", source, "Sale voided at the till") }
        val voided = queries.detail(replacementId)
        assertThat(voided.status).isEqualTo("VOID")
        assertThat(voided.totals.paid).isEqualByComparingTo("0")
        assertThat(voided.linked.payments.single().status).isEqualTo("Voided")
    }

    @Test
    fun `credit notes reduce the balance, never exceed a line, and send any excess to the wallet`() {
        signIn()
        val till = till()
        val invoice = issue(lines = listOf(line(citrus, "12", "4100"), line(rice, "10", "1000")))
        val lines = queries.detail(invoice.id).lines
        val byValue = creditNotes.get(creditNotes.issue(invoice.id, CreditNoteRequest("PRICING_ERROR", "Dealer price should have applied",
            listOf(CreditNoteLineRequest(lines[0].id, amount = BigDecimal("1200"))))))
        assertThat(byValue.number).startsWith("CN-")
        assertThat(byValue.vat).isEqualByComparingTo("90")
        assertThat(byValue.total).isEqualByComparingTo("1290")
        assertThat(byValue.appliedToInvoice).isEqualByComparingTo("1290")
        val byQuantity = creditNotes.get(creditNotes.issue(invoice.id, CreditNoteRequest("OVERCHARGE", "Two bags short",
            listOf(CreditNoteLineRequest(lines[1].id, quantity = BigDecimal("2"))))))
        assertThat(byQuantity.lineExtension).isEqualByComparingTo("2000")
        assertThat(byQuantity.vat).isEqualByComparingTo("0")
        queries.detail(invoice.id).let {
            assertThat(it.totals.credited).isEqualByComparingTo("3290")
            assertThat(it.displayStatus).isEqualTo("Partially Paid")
            assertThat(it.linked.creditNotes).hasSize(2)
        }
        failsWith { creditNotes.issue(invoice.id, CreditNoteRequest("OTHER", "Too many", listOf(CreditNoteLineRequest(lines[1].id, quantity = BigDecimal("9"))))) }
            .isEqualTo(ErrorCode.CREDIT_EXCEEDS_LINE)
        failsWith { creditNotes.issue(invoice.id, CreditNoteRequest("OTHER", "Too much", listOf(CreditNoteLineRequest(lines[0].id, amount = BigDecimal("48000.01"))))) }
            .isEqualTo(ErrorCode.CREDIT_EXCEEDS_LINE)
        failsWith { creditNotes.issue(invoice.id, CreditNoteRequest("OTHER", "Both", listOf(CreditNoteLineRequest(lines[0].id, quantity = BigDecimal("1"), amount = BigDecimal("1"))))) }
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { invoices.void(invoice.id, "Wrong customer selected", null) }.isEqualTo(ErrorCode.INVOICE_HAS_PAYMENTS)
        assertThatThrownBy { sql("update credit_notes set total = 1 where id = '${byValue.id}'") }.hasMessageContaining("can't be changed")
        assertThat(count("select count(*) from issued_documents where ref = '${byValue.number}' and doc_type = 'CREDIT_NOTE'")).isEqualTo(1)
        assertThat(String(creditNotes.pdf(byValue.id).pdf.copyOfRange(0, 5))).isEqualTo("%PDF-")
        assertThat(tx.execute { invoices.creditNoteLines(ikeja, today, today) }.filter { it.invoiceNumber == invoice.number }).hasSize(2)

        // Already paid in full: the whole credit is beyond the balance and goes to the wallet.
        val paid = issue(lines = listOf(line(citrus, "1", "4000")))
        cashPayment(paid.id, "4300", till)
        val before = walletOf(ordinary)
        val excess = creditNotes.get(creditNotes.issue(paid.id, CreditNoteRequest("GOODWILL", "Late delivery",
            listOf(CreditNoteLineRequest(queries.detail(paid.id).lines.single().id, amount = BigDecimal("1000"))))))
        assertThat(excess.appliedToInvoice).isEqualByComparingTo("0")
        assertThat(excess.excessRefunded).isEqualByComparingTo("1075")
        assertThat(walletOf(ordinary) - before).isEqualByComparingTo("1075")
        assertThat(count("select count(*) from customer_ledger_entries where reference = '${excess.number}' and entry_type = 'CREDIT_NOTE'")).isEqualTo(1)

        val walkIn = issue(customer = null, walkIn = WalkInBuyer("Ada Walk-in"), lines = listOf(line(rice, "1", "1000")))
        cashPayment(walkIn.id, "1000", till)
        failsWith { creditNotes.issue(walkIn.id, CreditNoteRequest("GOODWILL", "Sorry", listOf(CreditNoteLineRequest(queries.detail(walkIn.id).lines.single().id, amount = BigDecimal("100"))))) }
            .isEqualTo(ErrorCode.CREDIT_EXCEEDS_BALANCE)
    }

    @Test
    fun `invoices are e-mailed through the outbox, and the PDF is rendered when it is sent`() {
        signIn()
        val invoice = issue()
        // invoicing.autoEmailOnIssue: the buyer has an e-mail, so issuing queued one.
        assertThat(count("select count(*) from invoice_deliveries where invoice_id = '${invoice.id}' and trigger = 'ON_ISSUE'")).isEqualTo(1)
        assertThat(count("select count(*) from notification_outbox o join invoice_deliveries d on d.notification_outbox_id = o.id " +
            "where d.invoice_id = '${invoice.id}' and o.metadata->>'attachment' = 'INVOICE_PDF' and o.status = 'PENDING'")).isEqualTo(1)
        assertThat(queries.detail(invoice.id).delivery.status).isEqualTo("Queued")

        val redirect = deliveries.send(invoice.id, DeliveryRequest(recipientEmail = "accounts@elsewhere.ng"))
        assertThat(redirect.trigger).isEqualTo("REDIRECT")
        assertThat(deliveries.history(invoice.id)).hasSize(2)

        val walkIn = issue(customer = null, walkIn = WalkInBuyer("Ada Walk-in"))
        failsWith { deliveries.send(walkIn.id, DeliveryRequest()) }.isEqualTo(ErrorCode.NO_EMAIL_ON_FILE)
        assertThat(queries.deliveries(ListSearchRequest(params = mapOf("delivery" to listOf("NO_EMAIL")))).items.map { it.invoiceId }).contains(walkIn.id)

        sql("update notification_outbox set status = 'SENT', sent_at = now() where id = (select notification_outbox_id from invoice_deliveries where id = '${redirect.id}')")
        assertThat(queries.detail(invoice.id).delivery.code).isEqualTo("SENT")

        // The outbox gives up on the first one: the person who issued it is told.
        val outboxId = tx.execute { TransactionManager.current().exec("select notification_outbox_id from invoice_deliveries where invoice_id = '${invoice.id}' and trigger = 'ON_ISSUE'") { rs -> rs.next(); UUID.fromString(rs.getString(1)) }!! }
        signOut()
        tx.executeForTenant(tenantId) {
            InvoiceDeliveryOutcomes(support, tx, notify).onOutcome(tenantId, outboxId, "FAILED", mapOf("source" to "invoicing", "invoiceId" to invoice.id.toString()))
        }
        assertThat(events("invoicing.delivery.failed", invoice.id)).isEqualTo(1)
        assertThat(count("select count(*) from user_notifications where user_id = '$admin' and title like 'Invoice e-mail failed%'")).isGreaterThanOrEqualTo(1)

        // Attachments render with no one signed in, as the outbox dispatcher runs.
        tx.executeForTenant(tenantId) {
            val pdf = InvoicePdfAttachments(queries).resolve(tenantId, mapOf("invoiceId" to invoice.id.toString()))!!
            assertThat(String(pdf.bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
            assertThat(pdf.fileName).isEqualTo("${invoice.number}.pdf")
            val statement = StatementAttachments(deliveries).resolve(tenantId, mapOf("customerId" to ordinary.toString()))!!
            assertThat(String(statement.bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
        }
    }

    @Test
    fun `receivables, debtors and the overdue sweep`() {
        signIn()
        val old = issue(issueDate = today.minusDays(40), terms = 0)
        val recent = issue(issueDate = today.minusDays(1), terms = 0)
        val notDue = issue(terms = 30)
        issue(customer = special, terms = 30)

        val page = receivables.receivables(ListSearchRequest(params = mapOf("customerId" to listOf(ordinary.toString()))))
        assertThat(page.items.map { it.id }).containsExactly(old.id, recent.id, notDue.id)
        assertThat(page.items.map { it.ageingBucket }).containsExactly("DAYS_31_60", "WITHIN_30", "NOT_DUE")
        assertThat(page.summary!!["outstandingTotal"] as BigDecimal).isEqualByComparingTo(page.items.fold(BigDecimal.ZERO) { s, it -> s + it.balance })
        assertThat(page.summary!!["overdueCount"]).isEqualTo(2L)
        assertThat(receivables.receivables(ListSearchRequest(params = mapOf("customerId" to listOf(ordinary.toString()), "ageingBucket" to listOf("DAYS_31_60")))).items)
            .extracting("id").containsExactly(old.id)

        val debtors = receivables.debtors(ListSearchRequest(q = "CU-O$tag"))
        val row = debtors.items.single { it.customerId == ordinary }
        assertThat(row.openInvoices).isEqualTo(3)
        assertThat(row.outstanding).isEqualByComparingTo("158670")
        assertThat(row.overLimit).isTrue()
        assertThat(row.ageing).isEqualTo("DAYS_31_60")
        assertThat(receivables.debtors(ListSearchRequest(params = mapOf("overLimitOnly" to listOf("true")))).items.map { it.customerId }).contains(ordinary)
        val account = receivables.debtor(ordinary, null, null, openOnly = true, PageRequest.of(0, 2))
        assertThat(account.warning).isNotNull()
        assertThat(account.transactions.items).hasSize(2)
        assertThat(account.transactions.totalItems).isEqualTo(3)
        assertThat(account.account.outstanding).isEqualByComparingTo("158670")

        // The sweep alerts once per overdue invoice and reminds by e-mail on the configured days (1, 7, 30).
        signOut()
        tx.executeForTenant(tenantId) { sweep.sweep() }
        tx.executeForTenant(tenantId) { sweep.sweep() }
        assertThat(events("invoicing.invoice.overdue", old.id)).isEqualTo(1)
        assertThat(events("invoicing.invoice.overdue", recent.id)).isEqualTo(1)
        assertThat(events("invoicing.invoice.overdue", notDue.id)).isEqualTo(0)
        assertThat(count("select count(*) from invoice_deliveries where invoice_id = '${recent.id}' and trigger = 'OVERDUE_REMINDER'")).isEqualTo(1)
        assertThat(count("select count(*) from invoice_deliveries where invoice_id = '${old.id}' and trigger = 'OVERDUE_REMINDER'")).isEqualTo(0)
    }

    @Test
    fun `the debtor read model follows issues, payments and the paid tolerance, and matches a rebuild`() {
        signIn()
        val till = till()
        val first = issue()
        val second = issue()
        fun debtor() = receivables.debtors(ListSearchRequest(q = "CU-O$tag")).items.singleOrNull { it.customerId == ordinary }
        assertThat(debtor()!!.openInvoices).isEqualTo(2)
        assertThat(tx.execute { invoices.outstandingFor(ordinary) }).isEqualByComparingTo(first.total + second.total)
        assertThat(tx.execute { invoices.openInvoicesFor(ordinary) }.map { it.id }).containsExactly(first.id, second.id)

        cashPayment(first.id, first.total.toPlainString(), till)
        assertThat(debtor()!!.openInvoices).isEqualTo(1)
        assertThat(debtor()!!.outstanding).isEqualByComparingTo(second.total)
        assertThat(debtor()!!.lastPaymentAt).isNotNull()

        // 0.50 left is within the default paid tolerance (1.00): no longer a debtor.
        cashPayment(second.id, (second.total - BigDecimal("0.50")).toPlainString(), till)
        assertThat(debtor()).isNull()
        assertThat(tx.execute { invoices.outstandingFor(ordinary) }).isEqualByComparingTo("0")

        // A stricter tolerance makes the 0.50 open again: the change rebuilds the tenant's rows.
        tx.execute { moduleSettings.set(InvoicingSettings.PAID_TOLERANCE, Json.mapper.valueToTree(BigDecimal("0.10")), admin) }
        try {
            assertThat(debtor()!!.outstanding).isEqualByComparingTo("0.50")
        } finally {
            tx.execute { moduleSettings.reset(InvoicingSettings.PAID_TOLERANCE) }
        }
        assertThat(debtor()).isNull()

        // The SQL fallback tolerance is the module setting's default.
        val default = InvoicingSettings().settings().single { it.key == InvoicingSettings.PAID_TOLERANCE }.default as BigDecimal
        assertThat(count("select (invoicing_paid_tolerance('$tenantId') * 100)::bigint")).isEqualTo(default.movePointRight(2).toLong())

        // What the trigger maintained row by row equals a set-based rebuild from the invoices.
        val drift = tx.executeForTenant(tenantId) {
            val t = TransactionManager.current()
            val columns = "customer_id, location_id, open_invoices, outstanding, oldest_open_issue_date, last_payment_at"
            t.exec("create temp table maintained on commit drop as select $columns from debtor_balances where open_invoices > 0 or last_payment_at is not null")
            t.exec("select debtor_balances_rebuild('$tenantId')") { }
            t.exec("select count(*) from ((select * from maintained except select $columns from debtor_balances where open_invoices > 0 or last_payment_at is not null) " +
                "union all (select $columns from debtor_balances where open_invoices > 0 or last_payment_at is not null except select * from maintained)) d") { rs -> rs.next(); rs.getLong(1) }!!
        }
        assertThat(drift).isZero()
    }

    @Test
    fun `NRS preview and bulk export`() {
        signIn()
        val till = till()
        val invoice = issue(customer = special, lines = listOf(line(citrus, "2", "4100"), line(rice, "1", "1000")))
        cashPayment(invoice.id, "5000", till)
        val preview = nrs.preview(invoice.id)
        assertThat(preview.ready).isFalse()
        assertThat(preview.missing).contains("issuer.nrsBusinessId", "lines[1].hsn_code (fallback used)", "lines[1].price_unit")
        assertThat(preview.invoice["invoice_type_code"]).isEqualTo("381")
        assertThat(preview.invoice["payment_status"]).isEqualTo("PENDING")
        @Suppress("UNCHECKED_CAST")
        val means = preview.invoice["payment_means"] as List<Map<String, Any?>>
        assertThat(means.single()["payment_means_code"]).isEqualTo("10")
        @Suppress("UNCHECKED_CAST")
        val subtotals = ((preview.invoice["tax_total"] as List<Map<String, Any?>>).single()["tax_subtotal"] as List<Map<String, Any?>>)
        assertThat(subtotals).hasSize(2)

        val draft = tx.execute {
            invoices.createDraft(InvoiceRequest(SourceType.PRO_FORMA, UUID.randomUUID(), "PF-$tag", today, null, ikeja, special, null, null, null, null, listOf(line(rice, "1", "1000"))))
        }
        failsWith { nrs.preview(draft.id) }.isEqualTo(ErrorCode.INVOICE_NOT_ISSUED)

        issue(customer = special)
        val (name, zip) = queries.exportZip(ListSearchRequest(params = mapOf("customerId" to listOf(special.toString()))))
        assertThat(name).endsWith(".zip")
        val entries = ZipInputStream(ByteArrayInputStream(zip)).use { generateSequence { it.nextEntry }.map { e -> e.name }.toList() }
        assertThat(entries).hasSize(2).allMatch { it.startsWith("INV-") }
        tx.execute { moduleSettings.set(InvoicingSettings.BULK_EXPORT_MAX_INVOICES, Json.mapper.valueToTree(1), admin) }
        try {
            failsWith { queries.exportZip(ListSearchRequest(params = mapOf("customerId" to listOf(special.toString())))) }.isEqualTo(ErrorCode.EXPORT_TOO_LARGE)
        } finally {
            tx.execute { moduleSettings.reset(InvoicingSettings.BULK_EXPORT_MAX_INVOICES) }
        }
    }
}
