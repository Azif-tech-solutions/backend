package com.zifiq.barcode.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private const val SIZES = "40x25|58x40|100x50|A4_24"

/** One row of the label picker (docs/11 §4.5). `stockItem` is a Ref, never a bare id. */
data class LabelItemRow(
    val stockItem: Ref,
    val code: String,
    val name: String,
    val kind: String,
    val unit: String,
    val primaryCode: String?,
    val symbology: String?,
    val packCodes: Int,
)

data class LabelPreviewRequest(
    @field:NotNull val stockItemId: UUID?,
    @field:Pattern(regexp = SIZES) val size: String,
    val showPrice: Boolean = false,
    @field:Pattern(regexp = "RETAIL|DEALER") val priceBasis: String? = null,
    val locationId: UUID? = null,
)

data class LabelRunItemInput(
    @field:NotNull val stockItemId: UUID?,
    @field:Min(1) @field:Max(1000) val copies: Int,
    val codeId: UUID? = null,
)

data class LabelRunRequest(
    @field:Pattern(regexp = SIZES) val size: String,
    @field:Pattern(regexp = "PDF|ZPL") val outputFormat: String = "PDF",
    val showPrice: Boolean = false,
    @field:Pattern(regexp = "RETAIL|DEALER") val priceBasis: String? = null,
    val locationId: UUID? = null,
    val generateMissing: Boolean = false,
    @field:NotEmpty @field:Valid val items: List<LabelRunItemInput>,
)

data class LabelRunResult(
    val ref: String,
    val totalLabels: Int,
    val codesGenerated: Int,
    val downloadUrl: String,
)

data class LabelRunRow(
    val ref: String,
    val createdAt: Instant,
    val createdBy: UserRef?,
    val size: String,
    val outputFormat: String,
    val items: Int,
    val totalLabels: Int,
    val codesGenerated: Int,
    val downloadUrl: String,
)
