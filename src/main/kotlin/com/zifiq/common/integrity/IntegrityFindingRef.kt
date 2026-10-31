package com.zifiq.common.integrity

/**
 * One integrity-check breach reported by a module through its SPI, for Audit's integrity run
 * (docs/10 §4.10). A module returns these from reading its own tables; the Audit module collects
 * them across modules into a run. An empty list means the module's checks all passed.
 */
data class IntegrityFindingRef(
    val checkCode: String,
    /** WARNING or CRITICAL. */
    val severity: String,
    val recordType: String,
    val recordRef: String,
    val expected: String?,
    val actual: String?,
    val message: String,
)

/** A module's published integrity checks (docs/10 §4.10). Runs in the caller's tenant transaction. */
interface IntegrityChecks {
    fun runIntegrityChecks(): List<IntegrityFindingRef>
}
