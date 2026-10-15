package com.zifiq.invoicing.service

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.search.RawOp
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.invoicing.dto.DeliveryRequest
import com.zifiq.invoicing.dto.DeliveryView
import com.zifiq.invoicing.model.InvoiceDeliveriesTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.documents.AttachmentResolver
import com.zifiq.platform.documents.DocumentAttachment
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.NotificationOutcomeListener
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.settings.spi.CompanyProfileProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Invoice e-mail (docs/08 §2.6, §4.8). Every send is a notification-outbox row plus an
 * `invoice_deliveries` row; the PDF is not stored but rendered when the outbox sends it, from the
 * immutable invoice, so a retry or redirect never regenerates or renumbers anything. The delivery
 * status of an invoice is its latest outbox row's (Queued, Sent, Failed).
 */
@Service
class InvoiceDeliveryService(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
    private val listing: InvoiceListing,
    private val companyProfile: CompanyProfileProvider,
    private val customers: CustomerDirectory,
    private val pdfRenderer: PdfRenderer,
) {
    /** E-mail an issued invoice (or the customer's statement) now (HTTP). */
    fun send(invoiceId: UUID, request: DeliveryRequest): DeliveryView = tx.execute {
        val invoice = row(invoiceId)
        support.assertVisible(invoice[InvoicesTable.locationId].value)
        // A void invoice can still be downloaded, but never e-mailed; a draft isn't an invoice yet.
        if (invoice[InvoicesTable.status] != "ISSUED") throw ApiErrors.invoiceNotIssued()
        val buyerEmail = invoice[InvoicesTable.buyerEmail]?.takeIf { it.isNotBlank() }
        val recipient = request.recipientEmail?.trim()?.ifEmpty { null } ?: buyerEmail ?: throw ApiErrors.noEmailOnFile()
        val template = request.template ?: "STANDARD"
        if (template == "STATEMENT" && invoice[InvoicesTable.customerId] == null) {
            throw ApiErrors.validationFailed(mapOf("template" to "a statement needs a registered customer"))
        }
        val trigger = when {
            buyerEmail == null || !recipient.equals(buyerEmail, ignoreCase = true) -> "REDIRECT"
            latestStatus(invoiceId) == "FAILED" -> "RETRY"
            else -> "MANUAL"
        }
        view(queue(invoice, recipient, template, request.message?.trim()?.ifEmpty { null }, trigger, support.userId()))
    }

    fun history(invoiceId: UUID): List<DeliveryView> = tx.execute {
        support.assertVisible(row(invoiceId)[InvoicesTable.locationId].value)
        joined().selectAll().where { InvoiceDeliveriesTable.invoiceId eq invoiceId }
            .orderBy(InvoiceDeliveriesTable.requestedAt to SortOrder.DESC).map(::toView)
    }

    /** On issue, when `invoicing.autoEmailOnIssue` is on and the buyer has an e-mail (caller's transaction). */
    fun autoOnIssue(invoiceId: UUID) {
        if (!support.settings().boolean(InvoicingSettings.AUTO_EMAIL_ON_ISSUE)) return
        val invoice = row(invoiceId)
        val email = invoice[InvoicesTable.buyerEmail]?.takeIf { it.isNotBlank() } ?: return
        queue(invoice, email, "STANDARD", null, "ON_ISSUE", runCatching { support.userId() }.getOrNull())
    }

    /** Writes the outbox row and the delivery record (caller's transaction). */
    fun queue(invoice: ResultRow, recipient: String, template: String, message: String?, trigger: String, requestedBy: UUID?): UUID {
        val invoiceId = invoice[InvoicesTable.id].value
        val number = invoice[InvoicesTable.number]!!
        val company = companyProfile.get()
        val (subject, body) = compose(invoice, template, message, company.tradingName)
        val metadata = buildMap {
            put("source", InvoiceSupport.SOURCE_MODULE)
            put("invoiceId", invoiceId.toString())
            put(AttachmentResolver.METADATA_KEY, if (template == "STATEMENT") STATEMENT_ATTACHMENT else INVOICE_ATTACHMENT)
            invoice[InvoicesTable.customerId]?.let { put("customerId", it.toString()) }
        }
        val outboxId = NotificationOutboxTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[channel] = "EMAIL"
            it[NotificationOutboxTable.recipient] = recipient.take(320)
            it[NotificationOutboxTable.subject] = subject.take(300)
            it[NotificationOutboxTable.body] = body
            it[NotificationOutboxTable.metadata] = metadata
            it[status] = "PENDING"
            it[attempts] = 0
            it[createdAt] = Instant.now()
        }.value
        val id = InvoiceDeliveriesTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[InvoiceDeliveriesTable.invoiceId] = invoiceId
            it[recipientEmail] = recipient.take(320)
            it[InvoiceDeliveriesTable.template] = template
            it[InvoiceDeliveriesTable.message] = message?.take(1000)
            it[notificationOutboxId] = outboxId
            it[InvoiceDeliveriesTable.trigger] = trigger
            it[requestedByUserId] = requestedBy
            it[requestedAt] = Instant.now()
        }.value
        support.record("invoicing.delivery.queued", invoiceId, number, "$number e-mailed to $recipient (${template.lowercase()}, ${trigger.lowercase().replace('_', ' ')})",
            mapOf("deliveryId" to id, "template" to template, "trigger" to trigger), invoice[InvoicesTable.locationId].value)
        return id
    }

    /** The customer's statement of account: every open invoice with its ageing, as at today. */
    fun statementPdf(tenantId: UUID, customerId: UUID): DocumentAttachment = tx.executeForTenant(tenantId) {
        val d = listing.expressions()
        val customer = customers.get(customerId)
        val rows = InvoicesTable.select(InvoicesTable.columns + listOf(d.balance, d.daysOverdue, d.ageingBucket))
            .where { (InvoicesTable.tenantId eq tenantId) and (InvoicesTable.customerId eq customerId) and RawOp { +d.open } }
            .orderBy(InvoicesTable.issueDate).toList()
        val ageing = InvoiceExpressions.AGEING.associateWith { bucket ->
            rows.filter { it[d.ageingBucket] == bucket }.fold(BigDecimal.ZERO) { s, it -> s + it[d.balance] }
        }
        val company = companyProfile.get()
        val outstanding = rows.fold(BigDecimal.ZERO) { s, it -> s + it[d.balance] }
        val model = mapOf(
            "company" to company,
            "logo" to company.logoDataUri(),
            "customer" to customer,
            "asOf" to d.today.format(DATE),
            "rows" to rows.map {
                mapOf("number" to it[InvoicesTable.number], "issued" to it[InvoicesTable.issueDate]?.format(DATE), "due" to it[InvoicesTable.dueDate]?.format(DATE),
                    "total" to Money.naira(it[InvoicesTable.taxInclusiveAmount]),
                    "paid" to Money.naira(it[InvoicesTable.amountPaid] + it[InvoicesTable.amountCredited]),
                    "balance" to Money.naira(it[d.balance]), "overdue" to if (it[d.daysOverdue] > 0) "${it[d.daysOverdue]} days" else "—")
            },
            "ageing" to InvoiceExpressions.AGEING.map { mapOf("label" to InvoiceExpressions.ageingLabel(it), "amount" to Money.naira(ageing.getValue(it))) },
            "outstanding" to Money.naira(outstanding),
            "printedAt" to LocalDateTime.now(support.zone()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
        )
        DocumentAttachment("statement-${customer.code}-${d.today}.pdf", "application/pdf", pdfRenderer.render("statement", model))
    }

    // ---- Internals (caller's transaction) ----

    private fun compose(invoice: ResultRow, template: String, message: String?, company: String): Pair<String, String> {
        val number = invoice[InvoicesTable.number]!!
        val buyer = invoice[InvoicesTable.buyerName]
        val total = Money.naira(invoice[InvoicesTable.taxInclusiveAmount])
        val balance = Money.naira((invoice[InvoicesTable.taxInclusiveAmount] - invoice[InvoicesTable.amountPaid] - invoice[InvoicesTable.amountCredited]).max(BigDecimal.ZERO))
        val due = invoice[InvoicesTable.dueDate]?.format(DATE) ?: "on receipt"
        val note = message?.let { "\n\n$it" } ?: ""
        return when (template) {
            "REMINDER" -> "Payment reminder: $number from $company" to
                "Dear $buyer,\n\nThis is a reminder that invoice $number ($total) was due on $due and $balance is still outstanding. The invoice is attached.$note\n\n$company"
            "STATEMENT" -> "Statement of account from $company" to
                "Dear $buyer,\n\nPlease find attached your statement of account as at ${support.today().format(DATE)}.$note\n\n$company"
            else -> "Invoice $number from $company" to
                "Dear $buyer,\n\nPlease find attached invoice $number for $total, due $due.$note\n\n$company"
        }
    }

    private fun latestStatus(invoiceId: UUID): String? =
        joined().select(NotificationOutboxTable.status).where { InvoiceDeliveriesTable.invoiceId eq invoiceId }
            .orderBy(InvoiceDeliveriesTable.requestedAt to SortOrder.DESC).limit(1).firstOrNull()?.get(NotificationOutboxTable.status)

    private fun view(id: UUID): DeliveryView = toView(joined().selectAll().where { InvoiceDeliveriesTable.id eq id }.single())

    private fun toView(row: ResultRow): DeliveryView {
        return DeliveryView(
            id = row[InvoiceDeliveriesTable.id].value, invoice = Ref.invoice(row[InvoiceDeliveriesTable.invoiceId].value),
            recipient = row[InvoiceDeliveriesTable.recipientEmail], template = row[InvoiceDeliveriesTable.template], trigger = row[InvoiceDeliveriesTable.trigger],
            status = when (row[NotificationOutboxTable.status]) { "SENT" -> "Sent"; "FAILED" -> "Failed"; else -> "Queued" },
            requestedAt = row[InvoiceDeliveriesTable.requestedAt], sentAt = row[NotificationOutboxTable.sentAt],
            requestedBy = UserRef.of(row[InvoiceDeliveriesTable.requestedByUserId]?.value),
        )
    }

    private fun joined() = InvoiceDeliveriesTable
        .join(NotificationOutboxTable, JoinType.INNER, additionalConstraint = { RawOp { +InvoiceDeliveriesTable.notificationOutboxId; +" = "; +NotificationOutboxTable.id } })
        .join(InvoicesTable, JoinType.INNER, InvoiceDeliveriesTable.invoiceId, InvoicesTable.id)

    private fun row(id: UUID): ResultRow =
        InvoicesTable.selectAll().where { (InvoicesTable.id eq id) and (InvoicesTable.tenantId eq support.tenantId()) }.firstOrNull() ?: throw ApiErrors.invoiceNotFound()

    companion object {
        const val INVOICE_ATTACHMENT = "INVOICE_PDF"
        const val STATEMENT_ATTACHMENT = "CUSTOMER_STATEMENT"
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
    }
}

/** Renders an invoice e-mail's PDF at send time, so a retry sends the identical document. */
@Component
class InvoicePdfAttachments(private val queries: InvoiceQueryService) : AttachmentResolver {
    override val kind = InvoiceDeliveryService.INVOICE_ATTACHMENT

    override fun resolve(tenantId: UUID, metadata: Map<String, String>): DocumentAttachment? {
        val invoiceId = metadata["invoiceId"]?.let(UUID::fromString) ?: return null
        val printed = queries.pdfForAttachment(tenantId, invoiceId)
        return DocumentAttachment(printed.fileName, "application/pdf", printed.pdf)
    }
}

/** Renders the customer statement a STATEMENT e-mail carries. */
@Component
class StatementAttachments(private val deliveries: InvoiceDeliveryService) : AttachmentResolver {
    override val kind = InvoiceDeliveryService.STATEMENT_ATTACHMENT

    override fun resolve(tenantId: UUID, metadata: Map<String, String>): DocumentAttachment? {
        val customerId = metadata["customerId"]?.let(UUID::fromString) ?: return null
        return deliveries.statementPdf(tenantId, customerId)
    }
}

/**
 * `invoicing.delivery.failed` (docs/08 §8): when the outbox gives up on an invoice e-mail, the
 * person who asked for it is told in-app; for automatic sends, holders of `invoicing.delivery.manage`
 * at the showroom are.
 */
@Component
class InvoiceDeliveryOutcomes(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
    private val notify: Notify,
) : NotificationOutcomeListener {
    override fun onOutcome(tenantId: UUID, outboxId: UUID, status: String, metadata: Map<String, String>) {
        if (status != "FAILED" || metadata["source"] != InvoiceSupport.SOURCE_MODULE) return
        tx.executeForTenant(tenantId) {
            val delivery = InvoiceDeliveriesTable.join(InvoicesTable, JoinType.INNER, InvoiceDeliveriesTable.invoiceId, InvoicesTable.id)
                .selectAll().where { InvoiceDeliveriesTable.notificationOutboxId eq outboxId }.firstOrNull() ?: return@executeForTenant
            val invoiceId = delivery[InvoicesTable.id].value
            val number = delivery[InvoicesTable.number]
            val locationId = delivery[InvoicesTable.locationId].value
            val recipient = delivery[InvoiceDeliveriesTable.recipientEmail]
            val eventId = support.record("invoicing.delivery.failed", invoiceId, number, "$number could not be e-mailed to $recipient",
                mapOf("deliveryId" to delivery[InvoiceDeliveriesTable.id].value, "recipient" to recipient), locationId)
            val title = "Invoice e-mail failed — $number"
            val body = "$number could not be delivered to $recipient. Check the address and resend; the invoice and its number don't change."
            val requester = delivery[InvoiceDeliveriesTable.requestedByUserId]?.value
            if (requester != null) {
                notify.inApp(setOf(requester), NotificationCategory.ALERT, title, body, "invoice", invoiceId.toString(), eventId)
            } else {
                notify.holdersOf("invoicing.delivery.manage", locationId, NotificationCategory.ALERT, title, body, "invoice", invoiceId.toString(), eventId)
            }
        }
    }
}
