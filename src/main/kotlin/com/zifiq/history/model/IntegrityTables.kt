package com.zifiq.history.model

import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/** Integrity check runs (docs/10 §2.7, §4.10). See V16_2. */
object IntegrityCheckRunsTable : UUIDTable("integrity_check_runs") {
    val tenantId = reference("tenant_id", TenantsTable)
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
    val trigger = varchar("trigger", 10)
    val triggeredByUserId = uuid("triggered_by_user_id").nullable()
    val checksRun = integer("checks_run")
    val findingsCount = integer("findings_count")
    val status = varchar("status", 10)
}

object IntegrityCheckFindingsTable : UUIDTable("integrity_check_findings") {
    val tenantId = reference("tenant_id", TenantsTable)
    val runId = uuid("run_id")
    val checkCode = varchar("check_code", 40)
    val severity = varchar("severity", 10)
    val recordType = varchar("record_type", 40)
    val recordRef = varchar("record_ref", 60)
    val expected = varchar("expected", 120).nullable()
    val actual = varchar("actual", 120).nullable()
    val message = varchar("message", 300)
}
