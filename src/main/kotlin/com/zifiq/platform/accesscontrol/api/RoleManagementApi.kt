package com.zifiq.platform.accesscontrol.api

import com.zifiq.platform.accesscontrol.TenantRoleSummary
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateRoleBody(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Size(max = 300)
    val description: String? = null,
    val permissions: Set<String> = emptySet(),
)

@Tag(name = "Tenant Roles", description = "Create, list, and assign tenant-scoped roles")
@SecurityRequirement(name = "bearerAuth")
interface RoleManagementApi {
    @Operation(summary = "List roles configured for the active tenant")
    fun listRoles(): ApiEnvelope<List<TenantRoleSummary>>

    @Operation(summary = "Create a tenant role with selected permissions")
    fun createRole(body: CreateRoleBody): ApiEnvelope<TenantRoleSummary>
}
