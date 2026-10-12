package com.zifiq.process.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PasswordChangeRequest(
    @field:NotBlank val oldPassword: String,
    @field:NotBlank val newPassword: String,
)

data class CommissionRuleRequest(
    @field:NotBlank val role: String,
    @field:NotNull @field:Positive val percentageRate: BigDecimal,
    val description: String?,
)

data class CommissionRuleSummary(
    val id: UUID,
    val role: String,
    val percentageRate: BigDecimal,
    val description: String?,
)

data class SystemErrorLogItem(
    val id: UUID,
    val errorCode: String,
    val message: String,
    val module: String,
    val status: String,
    val timestamp: Instant,
)

data class RecycleBinItem(
    val id: UUID,
    val entityType: String,
    val entityId: String,
    val deletedBy: String,
    val deletedAt: Instant,
)

data class SalesCorrespondentRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val email: String,
    @field:NotBlank val phone: String,
    val assignedRegion: String?,
)

data class SalesCorrespondentSummary(
    val id: UUID,
    val name: String,
    val email: String,
    val phone: String,
    val assignedRegion: String?,
)
