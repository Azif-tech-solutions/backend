package com.zifiq.factory.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Shared Jackson mapper + typealias for the jsonb "snapshot" columns on production_costings,
 * production_runs, and special_production_runs (materials_snapshot, overhead_snapshot,
 * materials_used) — each snapshot is a list of small key/value line items, e.g.
 * [{"stockItemId": "...", "name": "...", "quantity": 4.0, "unitCost": 12.50}].
 */
val snapshotObjectMapper: ObjectMapper = jacksonObjectMapper()

typealias JsonSnapshot = List<Map<String, Any?>>

fun JsonSnapshot.toJson(): String = snapshotObjectMapper.writeValueAsString(this)
fun String.toSnapshot(): JsonSnapshot = snapshotObjectMapper.readValue(this)
