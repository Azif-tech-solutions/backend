package com.zifiq.platform.events.model

import com.zifiq.common.json.Json
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

/** See V7_1__events_notifications_settings.sql. `txid`, `seq` and `search_vector` are database-generated. */
object DomainEventsTable : UUIDTable("domain_events") {
    val tenantId = reference("tenant_id", TenantsTable)
    val eventType = varchar("event_type", 80)
    val aggregateType = varchar("aggregate_type", 60)
    val aggregateId = uuid("aggregate_id")
    val aggregateRef = varchar("aggregate_ref", 60).nullable()
    val locationId = uuid("location_id").nullable()
    val actorUserId = uuid("actor_user_id").nullable()
    val actorLabel = varchar("actor_label", 120).nullable()
    val occurredAt = timestamp("occurred_at")
    val payload = jsonb<Map<String, Any?>>("payload", Json::write, Json::readMap)
    val summary = varchar("summary", 300)
    val txid = long("txid").databaseGenerated()
    val seq = long("seq").databaseGenerated()
}

object EventConsumerCheckpointsTable : Table("event_consumer_checkpoints") {
    val consumer = varchar("consumer", 60)
    val tenantId = uuid("tenant_id")
    val lastTxid = long("last_txid")
    val lastSeq = long("last_seq")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(consumer, tenantId)
}
