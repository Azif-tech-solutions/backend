package com.zifiq.platform.notifications

import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private const val MAX_ATTEMPTS = 5

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
                            ),
                        )
                    }.onSuccess {
                        NotificationOutboxTable.update({ NotificationOutboxTable.id eq id }) {
                            it[NotificationOutboxTable.status] = "SENT"
                            it[NotificationOutboxTable.sentAt] = Instant.now()
                        }
                    }.onFailure { error ->
                        logger.warn("Notification outbox row {} failed to send (attempt {})", id, attempts + 1, error)
                        val nextAttempts = attempts + 1
                        NotificationOutboxTable.update({ NotificationOutboxTable.id eq id }) {
                            it[NotificationOutboxTable.attempts] = nextAttempts
                            it[NotificationOutboxTable.status] = if (nextAttempts >= MAX_ATTEMPTS) "FAILED" else "PENDING"
                        }
                    }
                }
            }
        }
    }
}
