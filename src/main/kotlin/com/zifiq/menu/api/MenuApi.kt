package com.zifiq.menu.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.identity.dto.InviteMemberRequest
import com.zifiq.platform.identity.dto.MemberSummary
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

// The Debtors List moved to GET /api/v1/invoicing/debtors (docs/08 §4.10); dealer registration to POST /api/v1/customers.
@Tag(name = "Menu Button & User Management", description = "User registration (Debtors List: GET /api/v1/invoicing/debtors; dealer registration: POST /api/v1/customers)")
@SecurityRequirement(name = "bearerAuth")
interface MenuApi {
    @Operation(summary = "Create user and assign tenant role (thin pass-through to the platform member-invite flow)")
    fun registerUserAndAssignRole(request: InviteMemberRequest): ApiEnvelope<MemberSummary>
}
