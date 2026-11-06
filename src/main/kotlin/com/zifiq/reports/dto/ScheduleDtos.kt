package com.zifiq.reports.dto

import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/** Create/update a scheduled report (docs/13 §4.7). Recipients are tenant member ids. */
data class ScheduleRequest(
    @field:NotBlank val name: String?,
    @field:NotBlank val reportKey: String?,
    val params: Map<String, Any?> = emptyMap(),
    @field:NotBlank val format: String?,
    @field:NotBlank val frequency: String?,
    val dayOfWeek: Int? = null,
    val dayOfMonth: Int? = null,
    val sendAt: LocalTime? = null,
    val recipients: List<UUID> = emptyList(),
)

data class ScheduleView(
    val id: UUID,
    val name: String,
    val reportKey: String,
    val format: String,
    val frequency: String,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
    val sendAt: LocalTime,
    val recipients: List<UserRef?>,
    val owner: UserRef?,
    val status: String,
    val lastRunAt: Instant?,
    val nextRunAt: Instant,
    val createdAt: Instant,
)
