package com.zifiq.platform.settings

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.json.Json
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert
import org.springframework.stereotype.Component

enum class SettingType { BOOLEAN, INT, DECIMAL, STRING, STRING_LIST, ENUM }

/**
 * One tenant-configurable setting, declared in code by the module that owns it — docs/03 §10.
 * An unregistered key can never be stored, and stored values are always re-validated against
 * the current definition before use.
 */
data class SettingDefinition(
    val key: String,
    val module: String,
    val label: String,
    val type: SettingType,
    val default: Any,
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    val options: List<String> = emptyList(),
    val maxItems: Int = 100,
)

/** Implemented by each module (a Spring bean) to register its settings. */
interface ModuleSettingProvider {
    fun settings(): List<SettingDefinition>
}

object ModuleSettingsTable : Table("module_settings") {
    val tenantId = reference("tenant_id", TenantsTable)
    val settingKey = varchar("setting_key", 80)
    // Raw JSON text: binding a JsonNode through upsert resolves to the Expression overload.
    val value = jsonb<String>("value", { it }, { it })
    val updatedByUserId = uuid("updated_by_user_id")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(tenantId, settingKey)
}

data class SettingValue(
    val definition: SettingDefinition,
    val value: Any,
    val isDefault: Boolean,
    val updatedByUserId: UUID?,
    val updatedAt: Instant?,
)

/**
 * Typed reader/writer for module settings. Every method runs in the caller's (tenant-scoped)
 * transaction — it never opens its own — so a module reading a threshold sees the same snapshot
 * as the rest of its work.
 */
@Component
class ModuleSettings(
    providers: List<ModuleSettingProvider>,
    private val tenantContext: TenantContext,
) {
    private val definitions: Map<String, SettingDefinition> = providers.flatMap { it.settings() }.let { all ->
        val duplicates = all.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Module setting keys registered twice: $duplicates" }
        all.associateBy { it.key }
    }

    fun definitions(): List<SettingDefinition> = definitions.values.sortedWith(compareBy({ it.module }, { it.key }))

    fun definition(key: String): SettingDefinition = definitions[key] ?: throw ApiErrors.settingKeyUnknown(key)

    fun int(key: String): Int = (value(key) as Number).toInt()
    fun decimal(key: String): BigDecimal = value(key) as BigDecimal
    fun boolean(key: String): Boolean = value(key) as Boolean
    fun string(key: String): String = value(key) as String

    @Suppress("UNCHECKED_CAST")
    fun stringList(key: String): List<String> = value(key) as List<String>

    fun zone(): ZoneId = ZoneId.of(string(TENANT_TIMEZONE))

    /** The stored value, or the registered default when unset or no longer valid. */
    fun value(key: String): Any = values(listOf(key)).single().value

    fun values(keys: Collection<String> = definitions.keys): List<SettingValue> {
        val wanted = keys.map(::definition)
        val stored = ModuleSettingsTable.selectAll()
            .where { (ModuleSettingsTable.tenantId eq tenantContext.requireTenantId()) and (ModuleSettingsTable.settingKey inList wanted.map { it.key }) }
            .associateBy { it[ModuleSettingsTable.settingKey] }
        return wanted.map { definition ->
            val row = stored[definition.key]
            val parsed = row?.let { runCatching { parse(definition, Json.readTree(it[ModuleSettingsTable.value])) }.getOrNull() }
            SettingValue(
                definition = definition,
                value = parsed ?: definition.default,
                isDefault = parsed == null,
                updatedByUserId = if (parsed != null) row[ModuleSettingsTable.updatedByUserId] else null,
                updatedAt = if (parsed != null) row[ModuleSettingsTable.updatedAt] else null,
            )
        }
    }

    /** Validates and stores [raw]. Returns the stored value. */
    fun set(key: String, raw: JsonNode, userId: UUID): SettingValue {
        val definition = definition(key)
        val parsed = parse(definition, raw)
        ModuleSettingsTable.upsert {
            it[tenantId] = tenantContext.requireTenantId()
            it[settingKey] = key
            it[value] = Json.write(parsed)
            it[updatedByUserId] = userId
            it[updatedAt] = Instant.now()
        }
        return values(listOf(key)).single()
    }

    /** Back to the registered default. */
    fun reset(key: String) {
        definition(key)
        ModuleSettingsTable.deleteWhere {
            (tenantId eq tenantContext.requireTenantId()) and (settingKey eq key)
        }
    }

    private fun parse(definition: SettingDefinition, raw: JsonNode): Any {
        fun invalid(reason: String): Nothing = throw ApiErrors.settingValueInvalid(definition.key, reason)
        fun inRange(number: BigDecimal) {
            definition.min?.let { if (number < it) invalid("must be at least $it") }
            definition.max?.let { if (number > it) invalid("must be at most $it") }
        }
        return when (definition.type) {
            SettingType.BOOLEAN -> if (raw.isBoolean) raw.booleanValue() else invalid("must be true or false")
            SettingType.INT -> {
                if (!raw.isIntegralNumber || !raw.canConvertToInt()) invalid("must be a whole number")
                inRange(BigDecimal(raw.intValue()))
                raw.intValue()
            }
            SettingType.DECIMAL -> {
                if (!raw.isNumber) invalid("must be a number")
                raw.decimalValue().also(::inRange)
            }
            SettingType.STRING -> {
                if (!raw.isTextual || raw.textValue().isBlank()) invalid("must be non-empty text")
                if (definition.key == TENANT_TIMEZONE && runCatching { ZoneId.of(raw.textValue()) }.isFailure) invalid("must be an IANA timezone such as Africa/Lagos")
                raw.textValue().trim().take(200)
            }
            SettingType.ENUM -> {
                val text = if (raw.isTextual) raw.textValue() else invalid("must be one of ${definition.options}")
                definition.options.firstOrNull { it.equals(text, ignoreCase = true) } ?: invalid("must be one of ${definition.options}")
            }
            SettingType.STRING_LIST -> {
                if (!raw.isArray) invalid("must be a list of text values")
                val items = raw.map { item -> if (item.isTextual && item.textValue().isNotBlank()) item.textValue().trim().take(120) else invalid("every item must be non-empty text") }
                if (items.size > definition.maxItems) invalid("at most ${definition.maxItems} items")
                if (items.map { it.lowercase() }.toSet().size != items.size) invalid("items must be unique")
                items
            }
        }
    }

    companion object {
        const val TENANT_TIMEZONE = "tenant.timezone"
        const val TENANT_CURRENCY = "tenant.currency"
        val nodes: JsonNodeFactory = JsonNodeFactory.instance
    }
}

/** Settings every module shares. */
@Component
class PlatformSettings : ModuleSettingProvider {
    override fun settings() = listOf(
        SettingDefinition(ModuleSettings.TENANT_TIMEZONE, "platform", "Business timezone (dates, periods and daily jobs use it)", SettingType.STRING, "Africa/Lagos"),
        SettingDefinition(ModuleSettings.TENANT_CURRENCY, "platform", "Base currency", SettingType.ENUM, "NGN", options = listOf("NGN", "XOF", "GHS", "USD")),
    )
}
