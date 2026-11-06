package com.zifiq.reports.model

import com.zifiq.common.json.Json
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object ReportSchedulesTable : UUIDTable("report_schedules") {
    val tenantId = reference("tenant_id", TenantsTable)
    val name = varchar("name", 120)
    val reportKey = varchar("report_key", 80)
    val params = jsonb<Map<String, Any?>>("params", Json::write, Json::readMap)
    val format = varchar("format", 4)
    val frequency = varchar("frequency", 10)
    val dayOfWeek = integer("day_of_week").nullable()
    val dayOfMonth = integer("day_of_month").nullable()
    val sendAt = time("send_at")
    val recipients = jsonb<List<String>>("recipients", Json::write, { s -> Json.readTree(s).map { it.asText() } })
    val runAsUserId = reference("run_as_user_id", AppUsersTable)
    val status = varchar("status", 10).default("ACTIVE")
    val lastRunAt = timestamp("last_run_at").nullable()
    val nextRunAt = timestamp("next_run_at")
    val createdByUserId = reference("created_by_user_id", AppUsersTable)
    val createdAt = timestamp("created_at")
}
