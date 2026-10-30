package com.zifiq.history.dto

import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import java.time.Instant

/**
 * One business event in the activity log (docs/10 §4.7), read from domain_events. No bare ids:
 * `location` and `actor` are references; the aggregate is identified by `aggregateType` + `ref`
 * (the record's own reference), and the entity timeline is fetched by aggregate id as a query param.
 */
data class ActivityRow(
    val occurredAt: Instant,
    val eventType: String,
    val summary: String,
    val ref: String?,
    val aggregateType: String,
    val location: Ref?,
    /** The acting user, or null for a system action (then `actorLabel` names the job). */
    val actor: UserRef?,
    val actorLabel: String?,
    val payload: Map<String, Any?>,
)
