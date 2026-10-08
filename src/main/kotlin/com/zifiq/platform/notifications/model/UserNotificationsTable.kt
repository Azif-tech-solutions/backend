package com.zifiq.platform.notifications.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/** In-app inbox (docs/03 §9.1). One row per recipient. */
object UserNotificationsTable : UUIDTable("user_notifications") {
    val tenantId = reference("tenant_id", TenantsTable)
    val userId = uuid("user_id")
    val category = varchar("category", 30)
    val title = varchar("title", 160)
    val body = varchar("body", 600)
    val linkType = varchar("link_type", 40).nullable()
    val linkRef = varchar("link_ref", 60).nullable()
    val sourceEventId = uuid("source_event_id").nullable()
    val createdAt = timestamp("created_at")
    val readAt = timestamp("read_at").nullable()
    val dismissedAt = timestamp("dismissed_at").nullable()
}
