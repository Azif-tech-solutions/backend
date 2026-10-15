package com.zifiq.invoicing.service

import com.zifiq.common.money.Money
import com.zifiq.common.search.RawOp
import com.zifiq.customers.spi.Correspondents
import com.zifiq.invoicing.model.InvoiceDeliveriesTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Overdue sweep (docs/08 §4.11). "Overdue" itself is derived (past due with a balance), so the
 * sweep only tells people: once per invoice it emits `invoicing.invoice.overdue` and alerts holders
 * of `invoicing.receivables.read` at the showroom and the customer's correspondent; and on each day
 * listed in `invoicing.overdueReminderDays` it e-mails the buyer a REMINDER. It runs hourly and acts
 * from 06:00 tenant time; both steps are idempotent, so extra runs change nothing.
 */
@Component
class OverdueSweep(
    private val database: Database,
    private val tx: TenantScopedTransaction,
    private val support: InvoiceSupport,
    private val listing: InvoiceListing,
    private val deliveries: InvoiceDeliveryService,
    private val notify: Notify,
    private val correspondents: Correspondents,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${zifiq.invoicing.overdue-cron:0 10 * * * *}")
    fun run() {
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId ->
            runCatching { tx.executeForTenant(tenantId) { if (LocalTime.now(support.zone()).hour >= START_HOUR) sweep() } }
                .onFailure { logger.warn("Overdue sweep failed for tenant {}: {}", tenantId, it.message) }
        }
    }

    /** One pass for the current tenant (caller's transaction). Visible for tests. Returns (alerts, reminders). */
    fun sweep(): Pair<Int, Int> {
        val d = listing.expressions()
        val overdue = InvoicesTable.select(InvoicesTable.columns + listOf(d.balance, d.daysOverdue))
            .where { (InvoicesTable.tenantId eq support.tenantId()) and RawOp { +d.open; +" and "; +d.daysOverdue; +" > 0" } }
            .toList()

        var alerts = 0
        overdue.filter { it[InvoicesTable.overdueNotifiedAt] == null }.forEach { invoice ->
            val id = invoice[InvoicesTable.id].value
            val claimed = InvoicesTable.update({ (InvoicesTable.id eq id) and InvoicesTable.overdueNotifiedAt.isNull() }) { it[overdueNotifiedAt] = Instant.now() }
            if (claimed == 0) return@forEach
            alerts++
            val number = invoice[InvoicesTable.number]!!
            val balance = Money.naira(invoice[d.balance])
            val locationId = invoice[InvoicesTable.locationId].value
            val eventId = support.record("invoicing.invoice.overdue", id, number, "$number overdue: $balance outstanding from ${invoice[InvoicesTable.buyerName]}",
                mapOf("balance" to invoice[d.balance], "dueDate" to invoice[InvoicesTable.dueDate].toString(), "customerId" to invoice[InvoicesTable.customerId]), locationId)
            val title = "Invoice overdue — $number"
            val body = "${invoice[InvoicesTable.buyerName]}: $balance outstanding, due ${invoice[InvoicesTable.dueDate]}."
            notify.holdersOf("invoicing.receivables.read", locationId, NotificationCategory.ALERT, title, body, "invoice", id.toString(), eventId)
            val rep = invoice[InvoicesTable.correspondentId]?.let { runCatching { correspondents.get(it).userId }.getOrNull() }
            if (rep != null) notify.inApp(setOf(rep), NotificationCategory.ALERT, title, body, "invoice", id.toString(), eventId)
        }

        val reminderDays = support.settings().stringList(InvoicingSettings.OVERDUE_REMINDER_DAYS).mapNotNull { it.trim().toIntOrNull() }.toSet()
        val startOfToday = d.today.atStartOfDay(support.zone()).toInstant()
        var reminders = 0
        overdue.filter { it[d.daysOverdue] in reminderDays && !it[InvoicesTable.buyerEmail].isNullOrBlank() }.forEach { invoice ->
            val id = invoice[InvoicesTable.id].value
            if (remindedSince(id, startOfToday)) return@forEach
            deliveries.queue(invoice, invoice[InvoicesTable.buyerEmail]!!, "REMINDER", null, "OVERDUE_REMINDER", null)
            reminders++
        }
        return alerts to reminders
    }

    private fun remindedSince(invoiceId: UUID, since: Instant): Boolean =
        InvoiceDeliveriesTable.select(InvoiceDeliveriesTable.id)
            .where { (InvoiceDeliveriesTable.invoiceId eq invoiceId) and (InvoiceDeliveriesTable.trigger eq "OVERDUE_REMINDER") and (InvoiceDeliveriesTable.requestedAt greaterEq since) }
            .any()

    private companion object {
        const val START_HOUR = 6
    }
}
