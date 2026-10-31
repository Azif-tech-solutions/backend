package com.zifiq.history.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Raise a data-quality flag by hand (docs/10 §4.8). */
data class ManualFlagRequest(
    @field:NotBlank val entityType: String?,
    @field:NotNull val entityId: UUID?,
    @field:NotBlank val field: String?,
    @field:Size(max = 200) val suggestedValue: String? = null,
)

/** Apply a correction; [value] defaults to the suggestion and must differ from the current value. */
data class ApplyFlagRequest(@field:Size(min = 1, max = 200) val value: String? = null)

data class DismissFlagRequest(
    @field:NotBlank @field:Size(max = 300) val reason: String?,
    val ignoreToken: Boolean = false,
)

/**
 * A data-quality flag (docs/10 §4.8). The cross-module `recordsAffected` / `documentsKeepingOriginal`
 * counts are not computed yet (they need inventory ledger + issued-document reads); the correction
 * workflow itself is complete.
 */
data class DataQualityFlagRow(
    val id: UUID,
    val ref: String,
    val entityType: String,
    val entityCode: String?,
    val field: String,
    val currentValue: String,
    val suggestedValue: String?,
    /** "Spelling" or "Naming". */
    val kind: String,
    /** "System spell check" or the user who raised it. */
    val flaggedBy: String,
    val flaggedOn: Instant,
    val status: String,
    val appliedValue: String?,
)

data class ScanResult(val flagsRaised: Int)
