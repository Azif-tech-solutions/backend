package com.zifiq.history.dto

import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Upsert the audit-review state of one record (docs/10 §4.11). One state per (recordType, recordId). */
data class AuditReviewUpsertRequest(
    @field:NotBlank val recordType: String?,
    @field:NotNull val recordId: UUID?,
    @field:NotBlank @field:Size(max = 40) val recordRef: String?,
    /** UNDER_REVIEW, REVIEWED or FLAGGED. FLAGGED requires a note. */
    @field:NotBlank val status: String?,
    @field:Size(max = 1000) val note: String?,
)

/**
 * The current review state of a record. `recordId` is the reviewed record across many modules, so it
 * is kept as a plain id (recordType + recordRef describe it); it is a documented exception to the
 * no-bare-ids rule, like IssuedDocumentRow.sourceId.
 */
data class AuditReviewRow(
    val id: UUID,
    val recordType: String,
    val recordId: UUID,
    val recordRef: String,
    val status: String,
    val note: String?,
    val reviewer: UserRef,
    val updatedAt: Instant,
    val createdAt: Instant,
)

/** A row of the batch lookup (`GET /audit/reviews/lookup`): the review badge for one record the caller already holds. */
data class AuditReviewLookupRow(
    val recordId: UUID,
    val status: String,
    val note: String?,
    val reviewer: UserRef,
    val updatedAt: Instant,
)
