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
 * A data-quality flag (docs/10 §4.8). For a STOCK_ITEM flag, the detail view carries the impact of
 * applying the correction: `recordsAffected` (live records that would reflect the new name — the
 * inventory ledger plus open sale / pro-forma lines) and `documentsKeepingOriginal` (issued documents
 * that snapshot the old name and never change — non-draft invoice lines, credit-note lines, waybill
 * lines). Both are null in the bulk list and for non-STOCK_ITEM flags.
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
    val recordsAffected: Int? = null,
    val documentsKeepingOriginal: Int? = null,
)

data class ScanResult(val flagsRaised: Int)
