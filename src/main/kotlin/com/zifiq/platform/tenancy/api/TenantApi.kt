package com.zifiq.platform.tenancy.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.tenancy.TenantSummary
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateTenantBody(
    @field:NotBlank
    @field:Size(max = 160)
    val name: String,
)

@Tag(name = "Tenants", description = "List and create child tenants of the active tenant")
@SecurityRequirement(name = "bearerAuth")
interface TenantApi {
    @Operation(summary = "List child tenants of the active tenant")
    fun listChildren(): ApiEnvelope<List<TenantSummary>>

    @Operation(summary = "Create a child tenant and provision its administrator")
    fun createChild(body: CreateTenantBody): ApiEnvelope<TenantSummary>
}
