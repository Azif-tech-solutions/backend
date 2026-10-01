package com.zifiq.platform.accesscontrol.api

import com.zifiq.platform.accesscontrol.RoleAssignmentSummary
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Tenant Roles", description = "Create, list, and assign tenant-scoped roles")
@SecurityRequirement(name = "bearerAuth")
interface RoleAssignmentApi {
    @Operation(summary = "Assign a tenant role to a user")
    fun assignRole(userId: UUID, roleId: UUID): ApiEnvelope<RoleAssignmentSummary>
}
