package com.zifiq.barcode.dto

import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** One product code (docs/11 §4.1). `assignedBy` is a reference, never a bare id. */
data class StockItemCodeRow(
    val id: UUID,
    val code: String,
    val symbology: String,
    val source: String,
    val isPrimary: Boolean,
    val packQuantity: BigDecimal,
    val packLabel: String?,
    val status: String,
    val assignedBy: UserRef?,
    val assignedAt: Instant,
)

data class GenerateCodeRequest(
    @field:Pattern(regexp = "EAN_13|CODE_128|QR") val symbology: String? = null,
)

data class AssignCodeRequest(
    @field:NotBlank @field:Size(max = 200) val code: String,
    @field:Pattern(regexp = "EAN_13|EAN_8|UPC_A|CODE_128|QR") val symbology: String,
    @field:Pattern(regexp = "MANUFACTURER|MANUAL") val source: String,
    val isPrimary: Boolean = false,
    val packQuantity: BigDecimal? = null,
    @field:Size(max = 40) val packLabel: String? = null,
)

data class ReplacePrimaryRequest(
    @field:NotBlank @field:Size(max = 200) val reason: String,
)

data class RetireCodeRequest(
    @field:NotBlank @field:Size(max = 200) val reason: String,
    val newPrimaryCodeId: UUID? = null,
)

/** The result of a replace-primary: the new primary plus a hint that labels should be reprinted. */
data class ReplacePrimaryResult(
    val code: StockItemCodeRow,
    val reprintSuggested: Boolean,
)
