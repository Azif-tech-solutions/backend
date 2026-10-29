package com.zifiq.history.model

import com.zifiq.common.json.Json
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

/** Violation records (docs/10 §2.2, §4.4). See V16_1__audit_compliance_violations.sql. */
object ViolationRecordsTable : UUIDTable("violation_records") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 20)
    val subjectUserId = reference("subject_user_id", AppUsersTable)
    val locationId = uuid("location_id").nullable()
    val category = varchar("category", 30)
    val description = varchar("description", 2000)
    val amountInvolved = decimal("amount_involved", 14, 2).nullable()
    val severity = varchar("severity", 10)
    val status = varchar("status", 20)
    val occurredOn = date("occurred_on").nullable()
    val raisedByUserId = reference("raised_by_user_id", AppUsersTable)
    val raisedAt = timestamp("raised_at")
    val sourceKind = varchar("source", 10)
    val auditAlertId = uuid("audit_alert_id").nullable()
    val linkedRefs = jsonb<List<Map<String, Any?>>>("linked_refs", Json::write, Json::readList)
    val conclusion = varchar("conclusion", 2000).nullable()
    val concludedByUserId = uuid("concluded_by_user_id").nullable()
    val concludedAt = timestamp("concluded_at").nullable()
}

object ViolationRecordEventsTable : UUIDTable("violation_record_events") {
    val tenantId = reference("tenant_id", TenantsTable)
    val violationRecordId = reference("violation_record_id", ViolationRecordsTable)
    val kind = varchar("kind", 20)
    val fromValue = varchar("from_value", 60).nullable()
    val toValue = varchar("to_value", 60).nullable()
    val note = varchar("note", 2000).nullable()
    val byUserId = reference("by_user_id", AppUsersTable)
    val at = timestamp("at")
}

object AuditAlertsTable : UUIDTable("audit_alerts") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ruleCode = varchar("rule_code", 40)
    val subjectUserId = uuid("subject_user_id").nullable()
    val locationId = uuid("location_id").nullable()
    val summary = varchar("summary", 300)
    val amount = decimal("amount", 14, 2).nullable()
    val evidence = jsonb<Map<String, Any?>>("evidence", Json::write, Json::readMap)
    val status = varchar("status", 20)
    val decidedByUserId = uuid("decided_by_user_id").nullable()
    val decidedAt = timestamp("decided_at").nullable()
    val dismissReason = varchar("dismiss_reason", 300).nullable()
    val openedRecordId = uuid("opened_record_id").nullable()
    val dedupeKey = varchar("dedupe_key", 120)
    val createdAt = timestamp("created_at")
}
