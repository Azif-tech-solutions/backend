package com.zifiq.invoicing

import com.zifiq.platform.companyProfiles
import com.zifiq.account.repository.MoneyLedgerRepository
import com.zifiq.account.service.AccountSupport
import com.zifiq.account.service.AccountingSettings
import com.zifiq.account.service.LocationVatService
import com.zifiq.account.service.MoneyMoves
import com.zifiq.account.service.ReceiptService
import com.zifiq.account.service.ReceiptWriter
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.customers.service.CustomerListService
import com.zifiq.customers.service.CustomerSpiService
import com.zifiq.customers.service.CustomerSupport
import com.zifiq.customers.service.CustomersSettings
import com.zifiq.customers.service.WalletLedgerService
import com.zifiq.inventory.dto.StockItemClassificationRequest
import com.zifiq.inventory.service.StockItemClassificationService
import com.zifiq.inventory.service.StockLocations
import com.zifiq.invoicing.dto.DraftHeaderRequest
import com.zifiq.invoicing.dto.ReissueRequest
import com.zifiq.invoicing.service.CreditNoteService
import com.zifiq.invoicing.service.InvoiceBuilder
import com.zifiq.invoicing.service.InvoiceDeliveryService
import com.zifiq.invoicing.service.InvoiceListing
import com.zifiq.invoicing.service.InvoicePaymentService
import com.zifiq.invoicing.service.InvoiceQueryService
import com.zifiq.invoicing.service.InvoiceService
import com.zifiq.invoicing.service.InvoiceSupport
import com.zifiq.invoicing.service.InvoicingSettings
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
import java.math.BigDecimal
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

/** Electronic Invoicing slice 1 — drafts, issue, VAT, immutability, void and reissue, history (docs/08 §4.1–4.4, §4.7, §12). */
class InvoicingIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val admin: UUID = UUID.randomUUID()
        private val accounts: UUID = UUID.randomUUID()
        private val cashier: UUID = UUID.randomUUID()
        private val ikeja: UUID = UUID.randomUUID()
        private val ikejaFloor: UUID = UUID.randomUUID()
        private val lekki: UUID = UUID.randomUUID()
        private val surulere: UUID = UUID.randomUUID()
        private val pending: UUID = UUID.randomUUID()
        private val factory: UUID = UUID.randomUUID()
        private val cartons: UUID = UUID.randomUUID()
        private val citrus: UUID = UUID.randomUUID()
        private val rice: UUID = UUID.randomUUID()
        private val horse: UUID = UUID.randomUUID()
        private val consulting: UUID = UUID.randomUUID()
        private val retired: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, accounts, cashier).forEach {
                    exec("insert into app_users (id, email, display_name) values ('$it', 'inv-$it@test.com', 'User ${it.toString().take(4)}')")
                }
                exec("insert into tenants (id, name) values ('$tenantId', 'Invoicing Co')")
            }
            inTenant {
                listOf(admin, accounts, cashier).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
                grant(admin, "Administrator", "select id from permission_catalog")
                grant(accounts, "Accounts", "select id from permission_catalog where code like 'invoicing.%' or code in ('accounting.vat.manage', 'customers.read')")
                grant(cashier, "Cashier", "select id from permission_catalog where code in ('invoicing.invoice.read', 'invoicing.invoice.issue')")
                fun location(id: UUID, type: String, code: String, name: String, extra: String = "", values: String = "") =
                    exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id$extra) values ('$id', '$tenantId', '$type', '$code', '$name', '$admin'$values)")
                location(ikeja, "SHOWROOM", "SHR-1", "Showroom Ikeja")
                location(ikejaFloor, "STORAGE_AREA", "SHR-1-FL", "Ikeja floor", ", parent_location_id, area_kind", ", '$ikeja', 'SHOP_FLOOR'")
                location(lekki, "SHOWROOM", "SHR-2", "Showroom Lekki", ", vat_enabled", ", false")
                location(surulere, "SHOWROOM", "SHR-3", "Showroom Surulere")
                location(pending, "SHOWROOM", "SHR-4", "Showroom Ajah", ", status", ", 'PENDING'")
                location(factory, "FACTORY", "FAC-1", "Factory A")
                exec("insert into user_location_assignments (tenant_id, user_id, location_id, access_scope, assigned_by_user_id) values ('$tenantId', '$cashier', '$ikeja', 'SINGLE_LOCATION', '$admin')")
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$cartons', '$tenantId', 'CTN', 'Cartons')")
                fun item(id: UUID, code: String, name: String, vat: String, hsn: String, status: String = "ACTIVE") =
                    exec("insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, vat_category_id, " +
                        "hsn_section_id, isic_section_id, status, created_by_user_id, created_at) values ('$id', '$tenantId', '$factory', 'FINISHED_PRODUCT', '$code', '$name', " +
                        "'$cartons', 0, 1000, (select id from vat_categories where code = '$vat'), (select id from hsn_sections where code = '$hsn'), " +
                        "(select id from isic_sections where code = 'A'), '$status', '$admin', now())")
                item(citrus, "ZF-101", "Citrus Drink 50cl", "STANDARD_RATE", "IV")
                item(rice, "ZF-201", "Local Rice 50kg", "ZERO_RATED", "II")
                item(horse, "ZF-301", "Pure-bred Horse", "STANDARD_RATE", "I")
                item(consulting, "ZF-401", "Farm Consulting", "STANDARD_RATE", "I")
                item(retired, "ZF-501", "Old Lemon Drink", "STANDARD_RATE", "IV", status = "SUSPENDED")
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
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), CustomersSettings(), AccountingSettings(), InvoicingSettings()), tenantContext)
    private val events = DomainEvents(tenantContext, actors)
    private val sequences = DocumentSequenceService(actors, tx)
    private val recipients = Recipients(tenantContext)
    private val notify = Notify(tenantContext, recipients)
    private val registry = DocumentRegistry(tenantContext, actors, events)
    private val accountSupport = AccountSupport(tenantContext, actors, permissions, LocationAccessService(actors), moduleSettings, events)
    private val vat = LocationVatService(accountSupport, tx, database, notify)
    private val customerSupport = CustomerSupport(actors, permissions, tenantContext)
    private val customerDirectory = CustomerSpiService(customerSupport, tenantContext,
        CustomerListService(customerSupport, tx, LocationAccessService(actors), CustomFieldValidator(tenantContext), moduleSettings), sequences, moduleSettings, events)
    private val profiles = companyProfiles(tx, tenantContext, actors, events)
    private val support = InvoiceSupport(tenantContext, actors, permissions, LocationAccessService(actors), moduleSettings, events)
    private val builder = InvoiceBuilder(support, StockLocations(), vat, customerDirectory, profiles)
    private val listing = InvoiceListing(support)
    private val receipts = ReceiptService(accountSupport, tx, ReceiptWriter(accountSupport, MoneyMoves(accountSupport, MoneyLedgerRepository()), sequences, registry),
        emptyList(), registry, PdfRenderer(), profiles, notify, recipients)
    private val walletLedger = WalletLedgerService(tenantContext, actors, events)
    private val queries = InvoiceQueryService(support, tx, listing, registry, PdfRenderer(), profiles, customerDirectory, receipts)
    private val payments = InvoicePaymentService(support, tx, receipts, walletLedger, notify)
    private val deliveries = InvoiceDeliveryService(support, tx, listing, profiles, customerDirectory, PdfRenderer())
    private val creditNotes = CreditNoteService(support, tx, sequences, registry, walletLedger, PdfRenderer(), profiles)
    private val invoices = InvoiceService(support, tx, builder, sequences, registry, notify, payments, deliveries, creditNotes)
    private val classification = StockItemClassificationService(actors, tx, LocationAccessService(actors), events)

    // A fresh set of customers per test, so each test can filter to exactly its own invoices.
    private val ordinary: UUID = UUID.randomUUID()
    private val special: UUID = UUID.randomUUID()
    private val suspended: UUID = UUID.randomUUID()

    init {
        tx.executeForTenant(tenantId) { profiles.provision(tenantId, admin) }
        val tag = ordinary.toString().take(6)
        inTenant {
            exec("insert into customers (id, tenant_id, code, type, name, email, tin, created_by_user_id) values " +
                "('$ordinary', '$tenantId', 'CU-O$tag', 'ORDINARY_CUSTOMER', 'Okafor Stores $tag', 'orders-$tag@okafor.ng', '21993344-$tag', '$admin')")
            exec("insert into customers (id, tenant_id, code, type, name, created_by_user_id) values ('$special', '$tenantId', 'CU-S$tag', 'SPECIAL_CUSTOMER', 'Grace Foods $tag', '$admin')")
            exec("insert into customers (id, tenant_id, code, type, name, status, created_by_user_id) values ('$suspended', '$tenantId', 'CU-X$tag', 'ORDINARY_CUSTOMER', 'Dormant $tag', 'SUSPENDED', '$admin')")
        }
    }

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

    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Africa/Lagos"))
    private var counter = 0

    private fun line(item: UUID, quantity: String, price: String, discount: String = "0", rate: String? = null) =
        InvoiceLineRequest(item, BigDecimal(quantity), BigDecimal(price), BigDecimal(discount), rate?.let(::BigDecimal))

    private fun request(
        sourceType: SourceType = SourceType.POS_SALE,
        location: UUID = ikeja,
        customer: UUID? = ordinary,
        walkIn: WalkInBuyer? = null,
        lines: List<InvoiceLineRequest> = listOf(line(citrus, "12", "4100")),
        issueDate: LocalDate? = null,
        sourceDate: LocalDate = today,
        terms: Int? = null,
        sourceId: UUID = UUID.randomUUID(),
    ) = InvoiceRequest(
        sourceType = sourceType, sourceId = sourceId, sourceRef = (if (sourceType == SourceType.PRO_FORMA) "PF-" else "TXN-") + "${++counter}-${sourceId.toString().take(4)}",
        sourceDate = sourceDate, locationId = location, customerId = customer, walkInBuyer = walkIn, issueDate = issueDate, paymentTermsDays = terms, lines = lines,
    )

    private fun issue(req: InvoiceRequest): InvoiceRef = tx.execute { invoices.issue(req) }
    private fun draft(req: InvoiceRequest): InvoiceRef = tx.execute { invoices.createDraft(req) }

    private fun sql(statement: String) = tx.executeForTenant(tenantId) { TransactionManager.current().exec(statement) }

    private fun count(query: String): Long =
        tx.executeForTenant(tenantId) { TransactionManager.current().exec(query) { rs -> rs.next(); rs.getLong(1) }!! }

    private fun numberOf(ref: InvoiceRef): Long = ref.number!!.removePrefix("INV-").toLong()

    @Test
    fun `issue assigns the next number and prices VAT per line from the showroom`() {
        signIn(cashier)
        val first = issue(request(lines = listOf(line(citrus, "12", "4100"), line(rice, "10", "1000"))))
        val second = issue(request())

        assertThat(first.number).startsWith("INV-")
        assertThat(numberOf(second)).isEqualTo(numberOf(first) + 1)
        // A rolled-back issue doesn't use up a number: the sequence moves inside the issuing transaction.
        assertThat(runCatching { tx.execute { invoices.issue(request()); error("roll back") } }.isFailure).isTrue()
        assertThat(numberOf(issue(request()))).isEqualTo(numberOf(second) + 1)
        val detail = queries.detail(first.id)
        assertThat(detail.status).isEqualTo("ISSUED")
        assertThat(detail.displayStatus).isEqualTo("Issued")
        assertThat(detail.invoiceKind).isEqualTo("B2B")
        assertThat(detail.lines).hasSize(2)
        // Standard-rated at 7.5%: 12 × 4,100 = 49,200 + 3,690 VAT; zero-rated rice carries none.
        assertThat(detail.lines[0].lineNet).isEqualByComparingTo("49200")
        assertThat(detail.lines[0].vatAmount).isEqualByComparingTo("3690")
        assertThat(detail.lines[0].nrsTaxCategory).isEqualTo("STANDARD_VAT")
        assertThat(detail.lines[1].vatAmount).isEqualByComparingTo("0")
        assertThat(detail.lines[1].nrsTaxCategory).isEqualTo("ZERO_VAT")
        assertThat(detail.lines[0].unit).isEqualTo("Cartons")
        assertThat(detail.totals.lineExtension).isEqualByComparingTo("59200")
        assertThat(detail.totals.vat).isEqualByComparingTo("3690")
        assertThat(detail.totals.total).isEqualByComparingTo("62890")
        assertThat(detail.totals.balance).isEqualByComparingTo("62890")
        assertThat(detail.dueDate).isEqualTo(today.plusDays(30))
        assertThat(detail.issuer!!.locationName).isEqualTo("Showroom Ikeja")
        assertThat(detail.buyer.code).startsWith("CU-O")
        assertThat(detail.issuedBy).isNotNull()
        assertThat(count("select count(*) from issued_documents where ref = '${first.number}' and doc_type = 'INVOICE' and status = 'ISSUED'")).isEqualTo(1)

        // A percentage discount: 10% of 1 × 1,000 = 100 off; VAT on the 900 net.
        val discounted = queries.detail(issue(request(lines = listOf(line(citrus, "1", "1000", rate = "10")))).id)
        assertThat(discounted.lines.single().discountAmount).isEqualByComparingTo("100")
        assertThat(discounted.lines.single().lineNet).isEqualByComparingTo("900")
        assertThat(discounted.lines.single().vatAmount).isEqualByComparingTo("67.50")
    }

    @Test
    fun `a showroom not collecting VAT charges none, and only trading showrooms issue invoices`() {
        signIn(accounts)
        val atLekki = queries.detail(issue(request(location = lekki)).id)
        assertThat(atLekki.vat.enabled).isFalse()
        assertThat(atLekki.totals.vat).isEqualByComparingTo("0")
        assertThat(atLekki.totals.total).isEqualByComparingTo("49200")

        failsWith { issue(request(location = pending)) }.isEqualTo(ErrorCode.LOCATION_NOT_TRADING)
        failsWith { issue(request(location = factory)) }.isEqualTo(ErrorCode.INVALID_LOCATION)
        failsWith { issue(request(location = ikejaFloor)) }.isEqualTo(ErrorCode.INVALID_LOCATION)
    }

    @Test
    fun `an issued invoice cannot be changed even by direct SQL`() {
        signIn(accounts)
        val issued = issue(request())

        assertThatThrownBy { sql("update invoice_lines set unit_price = 1 where invoice_id = '${issued.id}'") }.hasMessageContaining("issued invoice")
        assertThatThrownBy { sql("update invoices set tax_inclusive_amount = 1 where id = '${issued.id}'") }.hasMessageContaining("immutable")
        assertThatThrownBy { sql("update invoices set buyer_name = 'Someone else' where id = '${issued.id}'") }.hasMessageContaining("immutable")
        assertThatThrownBy { sql("delete from invoices where id = '${issued.id}'") }.hasMessageContaining("can't be deleted")
        assertThatThrownBy { sql("update invoices set status = 'DRAFT' where id = '${issued.id}'") }.hasMessageContaining("can't go from")
        // Payment-tracking columns stay writable.
        sql("update invoices set amount_paid = 10 where id = '${issued.id}'")
        assertThat(queries.detail(issued.id).totals.paid).isEqualByComparingTo("10")
    }

    @Test
    fun `a draft has no number until issued, and is re-priced at the issue date's VAT rate`() {
        signIn(accounts)
        val req = request(sourceType = SourceType.PRO_FORMA, location = surulere)
        val drafted = draft(req)
        assertThat(drafted.number).isNull()
        assertThat(drafted.status).isEqualTo("DRAFT")
        assertThat(queries.detail(drafted.id).displayStatus).isEqualTo("Draft")
        assertThat(queries.detail(drafted.id).totals.vat).isEqualByComparingTo("3690")
        failsWith { draft(req.copy(sourceRef = "PF-again")) }.isEqualTo(ErrorCode.INVOICE_EXISTS_FOR_SOURCE)

        invoices.updateDraft(drafted.id, DraftHeaderRequest(paymentTermsDays = 14, paymentTermsNote = "Net 14", buyerEmail = "ap@grace.ng"))
        // Surulere's VAT goes to 10% today: the draft is issued at the new rate, prices kept.
        sql("insert into location_vat_settings (tenant_id, location_id, vat_enabled, vat_rate, effective_from, updated_by_user_id) " +
            "values ('$tenantId', '$surulere', true, 10.00, '$today', '$admin')")
        val issued = queries.detail(invoices.issueDraft(drafted.id, DraftHeaderRequest()))
        assertThat(issued.number).startsWith("INV-")
        assertThat(issued.status).isEqualTo("ISSUED")
        assertThat(issued.dueDate).isEqualTo(today.plusDays(14))
        assertThat(issued.paymentTermsNote).isEqualTo("Net 14")
        assertThat(issued.buyer.email).isEqualTo("ap@grace.ng")
        assertThat(issued.lines.single().unitPrice).isEqualByComparingTo("4100")
        assertThat(issued.lines.single().vatAmount).isEqualByComparingTo("4920")
        assertThat(issued.vat.rate).isEqualByComparingTo("10")
        failsWith { invoices.issueDraft(drafted.id, DraftHeaderRequest()) }.isEqualTo(ErrorCode.INVOICE_NOT_DRAFT)

        // Discarding a draft deletes it (it has no number) and tells Sales & POS.
        val discarded = draft(request(sourceType = SourceType.PRO_FORMA))
        invoices.discardDraft(discarded.id)
        failsWith { queries.detail(discarded.id) }.isEqualTo(ErrorCode.INVOICE_NOT_FOUND)
        assertThat(count("select count(*) from domain_events where event_type = 'invoicing.invoice.discarded' and aggregate_id = '${discarded.id}'")).isEqualTo(1)

        val sourceId = UUID.randomUUID()
        val abandoned = draft(request(sourceType = SourceType.PRO_FORMA, sourceId = sourceId))
        tx.execute { invoices.discardDraftForSource("PRO_FORMA", sourceId) }
        failsWith { queries.detail(abandoned.id) }.isEqualTo(ErrorCode.INVOICE_NOT_FOUND)
    }

    @Test
    fun `void needs nothing paid, and void-and-reissue links the old and new invoices`() {
        signIn(accounts)
        val wrong = issue(request(customer = special))
        invoices.void(wrong.id, "Wrong customer selected at the till", null)
        val voided = queries.detail(wrong.id)
        assertThat(voided.status).isEqualTo("VOID")
        assertThat(voided.displayStatus).isEqualTo("Void")
        assertThat(voided.voidReason).isEqualTo("Wrong customer selected at the till")
        assertThat(count("select count(*) from issued_documents where ref = '${wrong.number}' and status = 'VOIDED'")).isEqualTo(1)
        failsWith { invoices.void(wrong.id, "Second attempt to void", null) }.isEqualTo(ErrorCode.INVOICE_NOT_ISSUED)

        val paid = issue(request())
        sql("update invoices set amount_paid = 100 where id = '${paid.id}'")
        failsWith { invoices.void(paid.id, "Customer changed their mind", null) }.isEqualTo(ErrorCode.INVOICE_HAS_PAYMENTS)

        val source = UUID.randomUUID()
        val original = issue(request(customer = special, sourceId = source))
        val replacementId = invoices.void(original.id, "Billed to the wrong customer", ReissueRequest(customerId = ordinary))
        val replacement = queries.detail(replacementId)
        val old = queries.detail(original.id)
        assertThat(replacementId).isNotEqualTo(original.id)
        assertThat(replacement.number).isNotEqualTo(original.number)
        assertThat(replacement.buyer.customerId).isEqualTo(ordinary)
        assertThat(replacement.linked.reissueOf!!.id).isEqualTo(original.id)
        assertThat(replacement.linked.sourceRef).isEqualTo(old.linked.sourceRef)
        assertThat(old.status).isEqualTo("VOID")
        assertThat(old.linked.replacedBy!!.id).isEqualTo(replacementId)
        assertThat(tx.execute { invoices.bySource("POS_SALE", source) }!!.id).isEqualTo(replacementId)

        // A correction worth less than what was already paid needs a credit note instead.
        val overpaidSource = UUID.randomUUID()
        val overpaid = issue(request(sourceId = overpaidSource))
        sql("update invoices set amount_paid = tax_inclusive_amount where id = '${overpaid.id}'")
        failsWith {
            tx.execute { invoices.reissueForSource("POS_SALE", overpaidSource, request(lines = listOf(line(citrus, "1", "4100"))), "Fewer cartons delivered") }
        }.isEqualTo(ErrorCode.REISSUE_BELOW_PAID)

        // A sale void (doc 09) voids the invoice whatever was paid; its receipts were already voided.
        val saleSource = UUID.randomUUID()
        val sale = issue(request(sourceId = saleSource))
        sql("update invoices set amount_paid = 50 where id = '${sale.id}'")
        tx.execute { invoices.voidForSource("POS_SALE", saleSource, "Sale voided at the till") }
        val afterSaleVoid = queries.detail(sale.id)
        assertThat(afterSaleVoid.status).isEqualTo("VOID")
        assertThat(afterSaleVoid.totals.paid).isEqualByComparingTo("0")
    }

    @Test
    fun `history filters derived statuses in SQL and never shows another showroom to a restricted user`() {
        signIn(accounts)
        val overdue = issue(request(issueDate = today.minusDays(10), sourceDate = today.minusDays(10), terms = 0))
        val paid = issue(request())
        sql("update invoices set amount_paid = tax_inclusive_amount where id = '${paid.id}'")
        val partial = issue(request())
        sql("update invoices set amount_paid = 100 where id = '${partial.id}'")
        val unpaid = issue(request())
        val atLekki = issue(request(location = lekki))

        fun statuses(status: String) = queries.list(ListSearchRequest(params = mapOf("customerId" to listOf(ordinary.toString()), "status" to listOf(status)))).items
        assertThat(statuses("OVERDUE").map { it.id }).containsExactly(overdue.id)
        assertThat(statuses("OVERDUE").single().daysOverdue).isEqualTo(10)
        assertThat(statuses("PAID").map { it.id }).containsExactly(paid.id)
        assertThat(statuses("PARTIALLY_PAID").map { it.id }).containsExactly(partial.id)
        assertThat(statuses("ISSUED").map { it.id }).containsExactlyInAnyOrder(unpaid.id, atLekki.id)

        val all = queries.list(ListSearchRequest(params = mapOf("customerId" to listOf(ordinary.toString())), facets = listOf("status", "locationId")))
        assertThat(all.totalItems).isEqualTo(5)
        assertThat(all.summary!!["invoicesOnFile"]).isEqualTo(5L)
        assertThat(all.summary!!["fromPos"]).isEqualTo(5L)
        assertThat(all.facets!!["status"]!!.associate { it.label to it.count }).containsEntry("Issued", 2L).containsEntry("Overdue", 1L)
        val overdueBalance = queries.detail(overdue.id).totals.balance
        assertThat(all.summary!!["overdue"] as BigDecimal).isEqualByComparingTo(overdueBalance)

        // Number search finds it; the open-invoice picker leaves out the paid one.
        assertThat(queries.list(ListSearchRequest(q = unpaid.number)).items.first().id).isEqualTo(unpaid.id)
        assertThat(queries.lookup(null, openOnly = true, customerId = ordinary).map { it.id }).doesNotContain(paid.id).contains(unpaid.id)
        assertThat(queries.lookupSource(queries.detail(partial.id).linked.sourceRef).id).isEqualTo(partial.id)

        // The Ikeja cashier sees Ikeja's invoices only; Lekki's is "not found", not "forbidden".
        signIn(cashier)
        val seen = queries.list(ListSearchRequest(params = mapOf("customerId" to listOf(ordinary.toString())))).items.map { it.id }
        assertThat(seen).hasSize(4).doesNotContain(atLekki.id)
        failsWith { queries.detail(atLekki.id) }.isEqualTo(ErrorCode.INVOICE_NOT_FOUND)
    }

    @Test
    fun `buyer, item, discount and date rules are enforced on issue`() {
        signIn(accounts)
        failsWith { issue(request(customer = suspended)) }.isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE)
        failsWith { issue(request(customer = ordinary, walkIn = WalkInBuyer("Ada"))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { issue(request(customer = null)) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { issue(request(lines = listOf(line(citrus, "1", "1000", discount = "1000.01")))) }.isEqualTo(ErrorCode.DISCOUNT_EXCEEDS_LINE)
        failsWith { issue(request(lines = listOf(line(retired, "1", "1000")))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { issue(request(issueDate = today.minusDays(2), sourceDate = today.minusDays(1))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { issue(request(issueDate = today.plusDays(1))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)

        val walkIn = queries.detail(issue(request(customer = null, walkIn = WalkInBuyer("Ada Walk-in", phone = "08031234567"))).id)
        assertThat(walkIn.buyer.customerId).isNull()
        assertThat(walkIn.buyer.name).isEqualTo("Ada Walk-in")
        assertThat(walkIn.invoiceKind).isEqualTo("B2C")
    }

    @Test
    fun `an item's granular HS or ISIC code goes on the line, else its section`() {
        signIn(accounts)
        val unit = tx.executeForTenant(tenantId) { TransactionManager.current().exec("select code from nrs_unit_of_measure_codes order by code limit 1") { rs -> rs.next(); rs.getString(1) }!! }
        val classified = classification.update(horse, StockItemClassificationRequest(hsnCode = "0101.21", nrsUnitCode = unit))
        assertThat(classified.hsnCode).isEqualTo("0101.21")
        assertThat(classified.hsnSectionCode).isEqualTo("I")
        failsWith { classification.update(citrus, StockItemClassificationRequest(hsnCode = "0101.21")) }.isEqualTo(ErrorCode.HSN_SECTION_MISMATCH)
        failsWith { classification.update(horse, StockItemClassificationRequest(hsnCode = "9999.99")) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        classification.update(consulting, StockItemClassificationRequest(isService = true, isicCode = "0111"))

        val lines = queries.detail(issue(request(lines = listOf(line(horse, "1", "500000"), line(citrus, "1", "4100"), line(consulting, "1", "20000")))).id).lines
        assertThat(lines[0].hsnCode).isEqualTo("0101.21")
        assertThat(lines[0].productCategory).startsWith("Horses")
        assertThat(lines[0].nrsUnitCode).isEqualTo(unit)
        assertThat(lines[1].hsnCode).isNull()
        assertThat(lines[1].hsnSectionCode).isEqualTo("IV")
        assertThat(lines[1].productCategory).startsWith("Prepared foodstuffs")
        assertThat(lines[2].isService).isTrue()
        assertThat(lines[2].isicCode).isEqualTo("0111")
        assertThat(lines[2].hsnSectionCode).isNull()
        assertThat(lines[2].serviceCategory).startsWith("Growing of cereals")

        assertThat(classification.update(horse, StockItemClassificationRequest(hsnCode = "")).hsnCode).isNull()
    }

    @Test
    fun `the PDF renders for issued, draft and void invoices`() {
        signIn(accounts)
        val issued = issue(request(lines = listOf(line(citrus, "12", "4100"), line(rice, "2", "35000"))))
        val drafted = draft(request(sourceType = SourceType.PRO_FORMA))
        val voided = issue(request())
        invoices.void(voided.id, "Raised against the wrong sale", null)

        listOf(issued.id, drafted.id, voided.id).forEach { id ->
            val printed = queries.pdf(id)
            assertThat(String(printed.pdf.copyOfRange(0, 5))).isEqualTo("%PDF-")
            assertThat(printed.pdf.size).isGreaterThan(2000)
        }
        assertThat(queries.pdf(issued.id).fileName).isEqualTo("${issued.number}.pdf")
        assertThat(queries.pdf(drafted.id).fileName).startsWith("draft-invoice-")
    }
}
