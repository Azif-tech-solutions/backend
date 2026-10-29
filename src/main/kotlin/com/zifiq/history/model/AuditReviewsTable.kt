package com.zifiq.history.model

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * One current audit-review state per record (docs/10 §2.4, §4.11). `record_id` points at another
 * module's record, so it is a plain uuid with no cross-module FK; the trail of changes lives in the
 * activity log as `history.review.updated` events. See V16__audit_compliance_reviews.sql.
 */
object AuditReviewsTable : UUIDTable("audit_reviews") {
    val tenantId = reference("tenant_id", TenantsTable)
    val recordType = varchar("record_type", 30)
    val recordId = uuid("record_id")
    val recordRef = varchar("record_ref", 40)
    val status = varchar("status", 12)
    val note = varchar("note", 1000).nullable()
    val reviewerUserId = reference("reviewer_user_id", AppUsersTable)
    val updatedAt = timestamp("updated_at")
    val createdAt = timestamp("created_at")
}
