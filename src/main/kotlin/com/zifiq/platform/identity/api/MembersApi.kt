package com.zifiq.platform.identity.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.identity.dto.InviteMemberRequest
import com.zifiq.platform.identity.dto.MemberLocationRequest
import com.zifiq.platform.identity.dto.MemberStatusRequest
import com.zifiq.platform.identity.dto.MemberSummary
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Tenant Members", description = "Invite, list, suspend, and location-scope tenant users")
@SecurityRequirement(name = "bearerAuth")
interface MembersApi {
    @Operation(summary = "Invite a new or existing user into the active tenant with a role and optional location")
    fun inviteMember(request: InviteMemberRequest): ApiEnvelope<MemberSummary>

    @Operation(summary = "List tenant members with role, status, and location scope")
    fun listMembers(): ApiEnvelope<List<MemberSummary>>

    @Operation(summary = "Activate or suspend a tenant member")
    fun updateStatus(userId: UUID, request: MemberStatusRequest): ApiEnvelope<MemberSummary>

    @Operation(summary = "Assign or clear a tenant member's location scope (null = all locations)")
    fun updateLocation(userId: UUID, request: MemberLocationRequest): ApiEnvelope<MemberSummary>
}
