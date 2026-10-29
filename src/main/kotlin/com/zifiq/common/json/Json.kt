package com.zifiq.common.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/** One JSON mapper for jsonb columns (event payloads, settings values, view definitions, custom attributes). */
object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun write(value: Any?): String = mapper.writeValueAsString(value)
    fun readMap(json: String): Map<String, Any?> = mapper.readValue(json)
    fun readList(json: String): List<Map<String, Any?>> = mapper.readValue(json)
    fun readTree(json: String): JsonNode = mapper.readTree(json)
}
