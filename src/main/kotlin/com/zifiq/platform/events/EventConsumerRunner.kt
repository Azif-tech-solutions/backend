package com.zifiq.platform.events

import com.zifiq.platform.events.model.DomainEventsTable
import com.zifiq.platform.events.model.EventConsumerCheckpointsTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** An event as consumers see it. */
data class DomainEventRecord(
    val id: UUID,
    val tenantId: UUID,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: UUID,
    val aggregateRef: String?,
    val locationId: UUID?,
    val actorUserId: UUID?,
    val actorLabel: String?,
    val occurredAt: Instant,
    val payload: Map<String, Any?>,
    val summary: String,
    val txid: Long,
    val seq: Long,
)

/**
 * A reaction to other modules' events (docs/03 §7.6) — the way a module responds to something an
 * earlier module did without depending on it. [handle] runs inside a transaction scoped to the
 * event's tenant, together with the checkpoint update, so its database effects happen exactly once.
 * It must still be idempotent for anything outside the database.
 */
interface EventConsumer {
    /** Stable name, used as the checkpoint key: never rename a deployed consumer. */
    val name: String
    val eventTypes: Set<String>
    fun handle(event: DomainEventRecord)
}

/**
 * Delivers domain events to every [EventConsumer], tenant by tenant, in commit-safe order.
 *
 * Ordering by `seq` alone can skip events: transaction A takes seq 9, transaction B takes seq 10
 * and commits first; a consumer that processed 10 would never see 9. So a consumer only reads
 * events written by transactions older than every transaction still in progress
 * (`txid < pg_snapshot_xmin(pg_current_snapshot())`), in `(txid, seq)` order. Once xmin has passed
 * a txid, no event with that txid can ever appear later, so the `(txid, seq)` checkpoint never
 * skips anything. Latency is a few seconds; consumers are for derived state and notifications,
 * never for enforcing an invariant.
 */
@Component
class EventConsumerRunner(
    private val database: Database,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val consumers: List<EventConsumer>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${zifiq.events.consumer-delay-ms:15000}", initialDelayString = "\${zifiq.events.consumer-initial-delay-ms:20000}")
    fun run() {
        if (consumers.isEmpty()) return
        val tenantIds = transaction(database) { TenantsTable.select(TenantsTable.id).map { it[TenantsTable.id].value } }
        tenantIds.forEach { tenantId -> consumers.forEach { consumer -> drain(consumer, tenantId) } }
    }

    /** Processes everything currently deliverable for one consumer and tenant. Visible for tests. */
    fun drain(consumer: EventConsumer, tenantId: UUID) {
        while (true) {
            val batch = tenantScopedTransaction.executeForTenant(tenantId) { nextBatch(consumer, tenantId) }
            if (batch.isEmpty()) return
            for (event in batch) {
                val handled = runCatching {
                    tenantScopedTransaction.executeForTenant(tenantId) {
                        consumer.handle(event)
                        saveCheckpoint(consumer.name, tenantId, event.txid, event.seq)
                    }
                }
                if (handled.isFailure) {
                    // Stop here and retry this event next round; later events wait so order holds.
                    logger.error("Event consumer {} failed on event {} ({}) for tenant {}", consumer.name, event.id, event.eventType, tenantId, handled.exceptionOrNull())
                    return
                }
            }
            if (batch.size < BATCH_SIZE) return
        }
    }

    private fun nextBatch(consumer: EventConsumer, tenantId: UUID): List<DomainEventRecord> {
        val checkpoint = EventConsumerCheckpointsTable.selectAll()
            .andWhere { EventConsumerCheckpointsTable.consumer eq consumer.name }
            .andWhere { EventConsumerCheckpointsTable.tenantId eq tenantId }
            .firstOrNull()
        val lastTxid = checkpoint?.get(EventConsumerCheckpointsTable.lastTxid) ?: 0L
        val lastSeq = checkpoint?.get(EventConsumerCheckpointsTable.lastSeq) ?: 0L
        val horizon = TransactionManager.current()
            .exec("select pg_snapshot_xmin(pg_current_snapshot())::text::bigint") { rs -> rs.next(); rs.getLong(1) }!!

        return DomainEventsTable.selectAll()
            .where {
                (DomainEventsTable.tenantId eq tenantId) and
                    (DomainEventsTable.eventType inList consumer.eventTypes) and
                    afterCheckpoint(lastTxid, lastSeq) and
                    (DomainEventsTable.txid less horizon)
            }
            .orderBy(DomainEventsTable.txid to SortOrder.ASC, DomainEventsTable.seq to SortOrder.ASC)
            .limit(BATCH_SIZE)
            .map(::toRecord)
    }

    private fun afterCheckpoint(lastTxid: Long, lastSeq: Long): Op<Boolean> = object : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
            +"("; +DomainEventsTable.txid; +", "; +DomainEventsTable.seq; +") > ("
            registerArgument(LongColumnType(), lastTxid); +", "; registerArgument(LongColumnType(), lastSeq); +")"
        }
    }

    private fun saveCheckpoint(consumer: String, tenantId: UUID, txid: Long, seq: Long) {
        TransactionManager.current().exec(
            """
            insert into event_consumer_checkpoints (consumer, tenant_id, last_txid, last_seq, updated_at)
            values (?, ?, ?, ?, now())
            on conflict (consumer, tenant_id) do update
                set last_txid = excluded.last_txid, last_seq = excluded.last_seq, updated_at = now()
            """.trimIndent(),
            listOf(VarCharColumnType() to consumer, UUIDColumnType() to tenantId, LongColumnType() to txid, LongColumnType() to seq),
        )
    }

    private fun toRecord(row: ResultRow) = DomainEventRecord(
        id = row[DomainEventsTable.id].value,
        tenantId = row[DomainEventsTable.tenantId].value,
        eventType = row[DomainEventsTable.eventType],
        aggregateType = row[DomainEventsTable.aggregateType],
        aggregateId = row[DomainEventsTable.aggregateId],
        aggregateRef = row[DomainEventsTable.aggregateRef],
        locationId = row[DomainEventsTable.locationId],
        actorUserId = row[DomainEventsTable.actorUserId],
        actorLabel = row[DomainEventsTable.actorLabel],
        occurredAt = row[DomainEventsTable.occurredAt],
        payload = row[DomainEventsTable.payload],
        summary = row[DomainEventsTable.summary],
        txid = row[DomainEventsTable.txid],
        seq = row[DomainEventsTable.seq],
    )

    private companion object {
        const val BATCH_SIZE = 100
    }
}
