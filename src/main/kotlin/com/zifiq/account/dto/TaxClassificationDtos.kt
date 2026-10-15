package com.zifiq.account.dto

import com.zifiq.platform.directory.Ref
import jakarta.validation.constraints.NotEmpty
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class VatCategorySummary(
    val id: UUID,
    val code: String,
    val name: String,
    val rate: BigDecimal,
    val inputVatRecoverable: Boolean,
    val description: String,
    /** The matching NRS tax_category.id -- what goes on a submitted NRS invoice (see NRS Reference Data). */
    val nrsTaxCategoryCode: String,
)

data class HsnSectionSummary(
    val id: UUID,
    val code: String,
    val chapterRange: String,
    val name: String,
)

data class IsicSectionSummary(
    val id: UUID,
    val code: String,
    val name: String,
)

data class TenantVatCategorySummary(
    val vatCategory: Ref,
    val rate: BigDecimal,
    val isActive: Boolean,
    val activatedAt: Instant,
)

data class TenantVatCategorySelectionRequest(
    @field:NotEmpty val vatCategoryCodes: Set<String>,
)
