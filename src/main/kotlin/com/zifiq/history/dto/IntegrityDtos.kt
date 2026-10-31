package com.zifiq.history.dto

import com.zifiq.platform.directory.UserRef
import java.time.Instant
import java.util.UUID

data class IntegrityRunRow(
    val id: UUID,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val trigger: String,
    val triggeredBy: UserRef?,
    val checksRun: Int,
    val findingsCount: Int,
    val status: String,
)

data class IntegrityFindingRow(
    val checkCode: String,
    val severity: String,
    val recordType: String,
    val recordRef: String,
    val expected: String?,
    val actual: String?,
    val message: String,
)

data class IntegrityRunDetail(val run: IntegrityRunRow, val findings: List<IntegrityFindingRow>)
