package com.zifiq.platform.notifications

import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private const val MAX_ATTEMPTS = 5

/**
 * Told when an outbox row reaches a final state (SENT, or FAILED after the last retry) — e.g.
 * Invoicing flags a failed invoice e-mail (docs/08 §8). Called inside the dispatcher's transaction,
 * already scoped to [tenantId]; a listener failure is logged and never undoes the delivery result.
 */
interface NotificationOutcomeListener {
    fun onOutcome(tenantId: UUID, outboxId: UUID, status: String, metadata: Map<String, String>)
}

/**
 * Drains notification_outbox. Every module writes a PENDING row to the outbox inside its own
 * tenant-scoped transaction instead of calling NotificationDispatcher directly — see
 * docs/implemented/00-platform-foundation.md §5.2. notification_outbox is RLS-protected on tenant_id, so this
 * background job iterates known tenants and re-scopes app.tenant_id per tenant rather than using
 * a bypass-RLS role, preserving the "only zifiq_app, never a bypass-RLS role" security posture.
 */
@Component
class NotificationOutboxDispatcher(
    private val database: Database,
    private val notificationDispatcher: NotificationDispatcher,
    private val outcomeListeners: List<NotificationOutcomeListener> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 5000)
    fun drain() {
        val tenantIds = transaction(database) {
            TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value }
        }

        tenantIds.forEach { tenantId ->
            transaction(database) {
                exec(
                    "select set_config('app.tenant_id', ?, true)",
                    listOf(org.jetbrains.exposed.sql.VarCharColumnType() to tenantId.toString()),
                )
                val pending = NotificationOutboxTable
                    .selectAll()
                    .andWhere { NotificationOutboxTable.status eq "PENDING" }
                    .toList()

                pending.forEach { row ->
                    val id = row[NotificationOutboxTable.id].value
                    val attempts = row[NotificationOutboxTable.attempts]
                    runCatching {
                        notificationDispatcher.send(
                            channel = NotificationChannelType.valueOf(row[NotificationOutboxTable.channel]),
                            message = NotificationMessage(
                                tenantId = tenantId,
                                recipient = row[NotificationOutboxTable.recipient],
                                subject = row[NotificationOutboxTable.subject],
                                body = row[NotificationOutboxTable.body],
                                metadata = row[NotificationOutboxTable.metadata],
                            ),
                        )
                    }.onSuccess {
                        NotificationOutboxTable.update({ NotificationOutboxTable.id eq id }) {
                            it[NotificationOutboxTable.status] = "SENT"
                            it[NotificationOutboxTable.sentAt] = Instant.now()
                        }
                        notifyOutcome(tenantId, id, "SENT", row[NotificationOutboxTable.metadata])
                    }.onFailure { error ->
                        logger.warn("Notification outbox row {} failed to send (attempt {})", id, attempts + 1, error)
                        val nextAttempts = attempts + 1
                        NotificationOutboxTable.update({ NotificationOutboxTable.id eq id }) {
                            it[NotificationOutboxTable.attempts] = nextAttempts
                            it[NotificationOutboxTable.status] = if (nextAttempts >= MAX_ATTEMPTS) "FAILED" else "PENDING"
                        }
                        if (nextAttempts >= MAX_ATTEMPTS) notifyOutcome(tenantId, id, "FAILED", row[NotificationOutboxTable.metadata])
                    }
                }
            }
        }
    }

    private fun notifyOutcome(tenantId: UUID, outboxId: UUID, status: String, metadata: Map<String, String>) {
        if (outcomeListeners.isEmpty()) return
        val transaction = TransactionManager.current()
        outcomeListeners.forEach { listener ->
            // A savepoint, so a failing listener can't abort the transaction the other rows are drained in.
            transaction.exec("savepoint notification_outcome")
            runCatching { listener.onOutcome(tenantId, outboxId, status, metadata) }
                .onSuccess { transaction.exec("release savepoint notification_outcome") }
                .onFailure {
                    transaction.exec("rollback to savepoint notification_outcome")
                    logger.warn("Notification outcome listener {} failed for outbox row {}", listener.javaClass.simpleName, outboxId, it)
                }
        }
    }
}
