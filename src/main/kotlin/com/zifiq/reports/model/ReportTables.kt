package com.zifiq.reports.model

import com.zifiq.common.json.Json
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

/** A generated report export (docs/13 §2.2): metadata only; the bytes live in [ReportExportFilesTable]. */
object ReportExportsTable : UUIDTable("report_exports") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val reportKey = varchar("report_key", 80)
    val params = jsonb<Map<String, Any?>>("params", Json::write, Json::readMap)
    val format = varchar("format", 4)
    val status = varchar("status", 10)
    val rowCount = integer("row_count").nullable()
    val fileName = varchar("file_name", 200).nullable()
    val contentType = varchar("content_type", 100).nullable()
    val sizeBytes = long("size_bytes").nullable()
    val errorMessage = varchar("error_message", 500).nullable()
    val requestedByUserId = reference("requested_by_user_id", AppUsersTable)
    val scheduleId = uuid("schedule_id").nullable()
    val requestedAt = timestamp("requested_at")
    val startedAt = timestamp("started_at").nullable()
    val finishedAt = timestamp("finished_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
}

object ReportExportFilesTable : Table("report_export_files") {
    val exportId = uuid("export_id")
    val tenantId = reference("tenant_id", TenantsTable)
    val content = binary("content")
    override val primaryKey = PrimaryKey(exportId)
}
