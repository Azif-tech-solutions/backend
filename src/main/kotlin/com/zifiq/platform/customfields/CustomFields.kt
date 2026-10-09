package com.zifiq.platform.customfields

import com.fasterxml.jackson.databind.JsonNode
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.json.Json
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.LocalDate
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

enum class CustomFieldEntity { STOCK_ITEM, CUSTOMER, SUPPLIER }

object CustomFieldDefinitionsTable : UUIDTable("custom_field_definitions") {
    val tenantId = reference("tenant_id", TenantsTable)
    val entityType = varchar("entity_type", 30)
    val fieldKey = varchar("field_key", 60)
    val label = varchar("label", 80)
    val dataType = varchar("data_type", 20)
    val options = jsonb<JsonNode>("options", Json::write, Json::readTree).nullable()
    val required = bool("required")
    val status = varchar("status", 20)
    val sortOrder = integer("sort_order")
    val createdByUserId = uuid("created_by_user_id")
    val createdAt = timestamp("created_at")
}

/**
 * Implemented by each module that stores `custom_attributes` for an entity type, so Settings can
 * show how a field is used and guard edits (docs/06 §4.5) without depending on that module. Every
 * method runs in the caller's tenant-scoped transaction. An entity type with no implementation
 * stores no values yet, so every count is zero.
 */
interface CustomAttributeUsage {
    val entity: CustomFieldEntity

    /** Records holding a value, per field key. */
    fun counts(): Map<String, Long>

    /** Records whose value for [fieldKey] equals [value] (case-insensitive): a SELECT option in use. */
    fun countWithValue(fieldKey: String, value: String): Long

    /** Live (not removed) records with no value for [fieldKey]: what would break if it became required. */
    fun countMissing(fieldKey: String): Long
}

data class CustomFieldDefinition(
    val key: String,
    val label: String,
    val dataType: String,
    val options: List<String>,
    val required: Boolean,
    val active: Boolean,
)

/**
 * Validates an entity's `custom_attributes` against the tenant's definitions (docs/03 §12.2). The
 * definitions are managed in Settings; every module storing custom attributes calls [validate]
 * before writing them, in the caller's transaction.
 */
@Component
class CustomFieldValidator(private val tenantContext: TenantContext) {
    fun definitions(entity: CustomFieldEntity): List<CustomFieldDefinition> =
        CustomFieldDefinitionsTable.selectAll()
            .where { (CustomFieldDefinitionsTable.tenantId eq tenantContext.requireTenantId()) and (CustomFieldDefinitionsTable.entityType eq entity.name) }
            .orderBy(CustomFieldDefinitionsTable.sortOrder)
            .map { row ->
                CustomFieldDefinition(
                    key = row[CustomFieldDefinitionsTable.fieldKey],
                    label = row[CustomFieldDefinitionsTable.label],
                    dataType = row[CustomFieldDefinitionsTable.dataType],
                    options = row[CustomFieldDefinitionsTable.options]?.map { it.asText() }.orEmpty(),
                    required = row[CustomFieldDefinitionsTable.required],
                    active = row[CustomFieldDefinitionsTable.status] == "ACTIVE",
                )
            }

    /**
     * Returns the normalised attributes to store. Unknown keys are rejected. Values of an inactive
     * field may be kept as they were but not changed. Required active fields must have a value.
     * Blank/null values remove the attribute.
     *
     * @param previous the entity's currently stored attributes (empty on create)
     */
    fun validate(entity: CustomFieldEntity, attributes: Map<String, Any?>?, previous: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val definitions = definitions(entity).associateBy { it.key }
        val result = previous.filterKeys { it in definitions }.toMutableMap()

        attributes.orEmpty().forEach { (key, raw) ->
            val definition = definitions[key] ?: throw ApiErrors.invalidCustomField(key, "Unknown custom field")
            val value = normalise(definition, raw)
            if (!definition.active && value != previous[key]) throw ApiErrors.invalidCustomField(key, "This field is inactive and can no longer be changed")
            if (value == null) result.remove(key) else result[key] = value
        }

        definitions.values.filter { it.active && it.required && result[it.key] == null }.forEach {
            throw ApiErrors.invalidCustomField(it.key, "'${it.label}' is required")
        }
        return result
    }

    private fun normalise(definition: CustomFieldDefinition, raw: Any?): Any? {
        if (raw == null || (raw is String && raw.isBlank())) return null
        fun invalid(reason: String): Nothing = throw ApiErrors.invalidCustomField(definition.key, reason)
        return when (definition.dataType) {
            "TEXT" -> raw.toString().trim().also { if (it.length > 500) invalid("must be at most 500 characters") }
            "NUMBER" -> when (raw) {
                is Number -> BigDecimal(raw.toString())
                is String -> raw.trim().toBigDecimalOrNull() ?: invalid("must be a number")
                else -> invalid("must be a number")
            }
            "DATE" -> runCatching { LocalDate.parse(raw.toString()).toString() }.getOrElse { invalid("must be a date (yyyy-MM-dd)") }
            "BOOLEAN" -> when (raw) {
                is Boolean -> raw
                "true" -> true
                "false" -> false
                else -> invalid("must be true or false")
            }
            "SELECT" -> definition.options.firstOrNull { it.equals(raw.toString().trim(), ignoreCase = true) }
                ?: invalid("must be one of ${definition.options}")
            else -> invalid("has an unsupported type")
        }
    }
}
