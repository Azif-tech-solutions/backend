package com.zifiq.history.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class LinkedRefInput(
    @field:NotBlank val type: String?,
    @field:NotBlank val ref: String?,
)

/** Raise a violation record (§4.4). Description must be substantial; the subject can't be the caller. */
data class ViolationRaiseRequest(
    /** Required for a direct raise; for open-record it defaults to the alert's subject. */
    val subjectUserId: UUID? = null,
    val locationId: UUID? = null,
    @field:NotBlank val category: String?,
    @field:NotBlank @field:Size(min = 20, max = 2000) val description: String?,
    val amountInvolved: BigDecimal? = null,
    @field:NotBlank val severity: String?,
    val occurredOn: LocalDate? = null,
    val linkedRefs: List<LinkedRefInput> = emptyList(),
)

data class ViolationNoteRequest(@field:NotBlank @field:Size(max = 2000) val note: String?)

data class ViolationUpdateRequest(
    val severity: String? = null,
    val amountInvolved: BigDecimal? = null,
    @field:NotBlank @field:Size(max = 2000) val note: String?,
)

data class ViolationConcludeRequest(
    @field:NotBlank val outcome: String?,
    @field:NotBlank @field:Size(min = 20, max = 2000) val conclusion: String?,
)

data class ViolationReopenRequest(@field:NotBlank @field:Size(max = 2000) val reason: String?)

data class LinkedRef(val type: String, val ref: String)

/** A row of the violations list. */
data class ViolationRow(
    val id: UUID,
    val ref: String,
    val dateRaised: Instant,
    val subject: UserRef,
    val location: Ref?,
    val category: String,
    val description: String,
    val amountInvolved: BigDecimal?,
    val severity: String,
    val raisedBy: UserRef,
    /** The status, named "outcome" on the screen. */
    val outcome: String,
)

data class ViolationEventView(
    val kind: String,
    val fromValue: String?,
    val toValue: String?,
    val note: String?,
    val by: UserRef,
    val at: Instant,
)

/** One violation record with its full investigation trail. */
data class ViolationDetail(
    val id: UUID,
    val ref: String,
    val subject: UserRef,
    val location: Ref?,
    val category: String,
    val description: String,
    val amountInvolved: BigDecimal?,
    val severity: String,
    val status: String,
    val source: String,
    val occurredOn: LocalDate?,
    val linkedRefs: List<LinkedRef>,
    val raisedBy: UserRef,
    val raisedAt: Instant,
    val conclusion: String?,
    val concludedBy: UserRef?,
    val concludedAt: Instant?,
    val events: List<ViolationEventView>,
)

data class StaffMember(val user: UserRef, val location: Ref?)

data class StaffViolationStats(
    val recordsOnFile: Int,
    val underInvestigation: Int,
    val substantiated: Int,
    val amountInvolved: BigDecimal,
    val clearedWithoutCase: Int,
)

/** The staff panel (§4.4): one member's record, most recent first. */
data class StaffViolationPanel(
    val staff: StaffMember,
    val stats: StaffViolationStats,
    val records: List<ViolationDetail>,
)

/** An alert a detector raised from another module's event (§4.4). */
data class AuditAlertRow(
    val id: UUID,
    val ruleCode: String,
    val subject: UserRef?,
    val location: Ref?,
    val summary: String,
    val amount: BigDecimal?,
    val status: String,
    val evidence: Map<String, Any?>,
    val createdAt: Instant,
)

data class AlertDismissRequest(@field:NotBlank @field:Size(max = 300) val reason: String?)
