package com.zifiq.platform.identity.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

data class InviteMemberRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val displayName: String,
    val phone: String?,
    @field:NotNull val roleId: UUID,
    val locationId: UUID?,
)

data class RoleRef(val id: UUID, val name: String)

data class MemberSummary(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val phone: String?,
    val status: String,
    val role: RoleRef?,
    val locationId: UUID?,
    val accessScope: String,
    val invitedAt: Instant,
)

data class MemberStatusRequest(
    @field:Pattern(regexp = "ACTIVE|SUSPENDED") val status: String,
)

data class MemberLocationRequest(
    val locationId: UUID?,
)
