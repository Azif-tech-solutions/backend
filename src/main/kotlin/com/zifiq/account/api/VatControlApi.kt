package com.zifiq.account.api

import com.zifiq.account.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "VAT Control", description = "System-seeded VAT categories (NRS/Nigeria Tax Act 2025), HSN & ISIC classification sections, and per-tenant VAT category activation")
@SecurityRequirement(name = "bearerAuth")
interface VatControlApi {
    @Operation(summary = "List the system-seeded VAT categories (Standard Rate, Zero-Rated, Exempt)")
    fun listVatCategories(): ApiEnvelope<List<VatCategorySummary>>

    @Operation(summary = "List HSN (Harmonized System) classification sections")
    fun listHsnSections(): ApiEnvelope<List<HsnSectionSummary>>

    @Operation(summary = "List ISIC Rev.4 classification sections")
    fun listIsicSections(): ApiEnvelope<List<IsicSectionSummary>>

    @Operation(summary = "Get this tenant's VAT category activation status")
    fun getTenantVatCategories(): ApiEnvelope<List<TenantVatCategorySummary>>

    @Operation(summary = "Set which VAT categories this tenant uses (replaces the active set)")
    fun selectTenantVatCategories(request: TenantVatCategorySelectionRequest): ApiEnvelope<List<TenantVatCategorySummary>>
}
