package com.zifiq.platform.notifications.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

private val outboxObjectMapper = ObjectMapper()

object NotificationOutboxTable : UUIDTable("notification_outbox") {
    val tenantId = reference("tenant_id", TenantsTable)
    val channel = varchar("channel", 20)
    val recipient = varchar("recipient", 320)
    val subject = varchar("subject", 300).nullable()
    val body = text("body")
    val metadata = jsonb<Map<String, String>>(
        "metadata",
        { outboxObjectMapper.writeValueAsString(it) },
        { outboxObjectMapper.readValue(it) },
    )
    val status = varchar("status", 20).default("PENDING")
    val attempts = integer("attempts").default(0)
    val createdAt = timestamp("created_at")
    val sentAt = timestamp("sent_at").nullable()
}
