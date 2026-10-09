package com.zifiq.settings.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.json.Json
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.platform.customfields.CustomAttributeUsage
import com.zifiq.platform.customfields.CustomFieldDefinitionsTable
import com.zifiq.platform.customfields.CustomFieldEntity
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import com.zifiq.settings.dto.CustomFieldCreateRequest
import com.zifiq.settings.dto.CustomFieldUpdateRequest
import com.zifiq.settings.dto.CustomFieldView
import java.text.Normalizer
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.castTo
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * The custom-field builder (docs/06 §4.5) over platform's `custom_field_definitions`. A field's key
 * and type never change once created, and values already stored are never silently dropped (rule
 * ST4): removing a SELECT option in use, or making a field required while records lack a value, is
 * refused. Usage comes from each owning module's [CustomAttributeUsage].
 */
@Service
class CustomFieldService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val domainEvents: DomainEvents,
    usages: List<CustomAttributeUsage>,
) {
    private val usageByEntity = usages.associateBy { it.entity }

    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(CustomFieldDefinitionsTable.fieldKey, SearchKind.CODE, "Key"),
            SearchField(CustomFieldDefinitionsTable.label, SearchKind.TRIGRAM, "Label"),
            SearchField(CustomFieldDefinitionsTable.options.castTo<String>(TextColumnType()), SearchKind.TRIGRAM, "Options"),
        ),
    )

    /** Unpaged: a tenant defines tens of fields. [inUse] filters on usageCount > 0. */
    fun list(
        q: String?,
        entityTypes: Set<String>,
        statuses: Set<String>,
        dataTypes: Set<String>,
        required: Boolean?,
        inUse: Boolean?,
        sort: String?,
    ): List<CustomFieldView> = tx.execute {
        val parsed = searchSpec.parse(q)
        val condition = listOfNotNull<Op<Boolean>>(
            CustomFieldDefinitionsTable.tenantId eq tenantId(),
            entityTypes.takeIf { it.isNotEmpty() }?.let { types -> CustomFieldDefinitionsTable.entityType inList types.map { it.uppercase() } },
            statuses.takeIf { it.isNotEmpty() }?.let { values -> CustomFieldDefinitionsTable.status inList values.map { it.uppercase() } },
            dataTypes.takeIf { it.isNotEmpty() }?.let { values -> CustomFieldDefinitionsTable.dataType inList values.map { it.uppercase() } },
            required?.let { CustomFieldDefinitionsTable.required eq it },
            searchSpec.condition(parsed),
        ).allOf()!!
        val counts = usageByEntity.mapValues { (_, usage) -> usage.counts() }
        val rows = CustomFieldDefinitionsTable.selectAll().where(condition)
            .orderBy(
                *(searchSpec.rank(parsed) +
                    (CustomFieldDefinitionsTable.entityType to SortOrder.ASC) +
                    (CustomFieldDefinitionsTable.sortOrder to SortOrder.ASC) +
                    (CustomFieldDefinitionsTable.label to SortOrder.ASC)).toTypedArray(),
            )
            .map { row -> toView(row, counts[CustomFieldEntity.valueOf(row[CustomFieldDefinitionsTable.entityType])]?.get(row[CustomFieldDefinitionsTable.fieldKey]) ?: 0) }
            .filter { inUse == null || (it.usageCount > 0) == inUse }
        // sort=field[,asc|desc]; usageCount defaults to most-used first.
        val field = sort?.substringBefore(',')?.trim().orEmpty()
        val direction = sort?.substringAfter(',', "")?.trim()?.lowercase().orEmpty()
        val sorted = when (field) {
            "", "sortOrder" -> rows
            "label" -> rows.sortedBy { it.label.lowercase() }
            "usageCount" -> rows.sortedBy { it.usageCount }
            else -> throw ApiErrors.invalidSortField(field, listOf("sortOrder", "label", "usageCount"))
        }
        val descending = if (direction.isEmpty()) field == "usageCount" else direction == "desc"
        if (descending) sorted.reversed() else sorted
    }

    fun get(id: UUID): CustomFieldView = tx.execute { view(row(id)) }

    fun create(request: CustomFieldCreateRequest): CustomFieldView = tx.execute {
        val entity = CustomFieldEntity.valueOf(request.entityType!!)
        val dataType = request.dataType!!
        val label = request.label.trim()
        ensureLabelFree(entity, label, null)
        val options = if (dataType == "SELECT") validOptions(request.options) else {
            if (!request.options.isNullOrEmpty()) throw ApiErrors.validationFailed(mapOf("options" to "Only SELECT fields have options"))
            null
        }
        val key = uniqueKey(entity, slug(label))
        // A new required field is missing on every existing record.
        if (request.required) requireNoBlanks(entity, key)

        val id = CustomFieldDefinitionsTable.insertAndGetId {
            it[tenantId] = EntityID(tenantId(), TenantsTable)
            it[entityType] = entity.name
            it[fieldKey] = key
            it[CustomFieldDefinitionsTable.label] = label
            it[CustomFieldDefinitionsTable.dataType] = dataType
            it[CustomFieldDefinitionsTable.options] = options?.let { values -> Json.readTree(Json.write(values)) }
            it[required] = request.required
            it[status] = "ACTIVE"
            it[sortOrder] = request.sortOrder ?: nextSortOrder(entity)
            it[createdByUserId] = currentActorProvider.current().userId
            it[createdAt] = Instant.now()
        }.value
        record(id, key, "settings.custom-field.created", "Custom field '$label' added to ${entity.label()}", mapOf("entityType" to entity.name, "dataType" to dataType))
        view(row(id))
    }

    fun update(id: UUID, request: CustomFieldUpdateRequest): CustomFieldView = tx.execute {
        val row = row(id)
        val entity = CustomFieldEntity.valueOf(row[CustomFieldDefinitionsTable.entityType])
        val key = row[CustomFieldDefinitionsTable.fieldKey]
        val label = request.label.trim()
        ensureLabelFree(entity, label, id)

        val currentOptions = optionsOf(row)
        val options = when {
            row[CustomFieldDefinitionsTable.dataType] != "SELECT" -> {
                if (!request.options.isNullOrEmpty()) throw ApiErrors.validationFailed(mapOf("options" to "Only SELECT fields have options"))
                null
            }
            request.options == null -> currentOptions
            else -> validOptions(request.options).also { next ->
                val removed = currentOptions.filter { old -> next.none { it.equals(old, ignoreCase = true) } }
                removed.forEach { option ->
                    val used = usageByEntity[entity]?.countWithValue(key, option) ?: 0
                    if (used > 0) throw ApiErrors.optionInUse(option, used)
                }
            }
        }
        if (request.required && !row[CustomFieldDefinitionsTable.required]) requireNoBlanks(entity, key)

        CustomFieldDefinitionsTable.update({ (CustomFieldDefinitionsTable.id eq id) and (CustomFieldDefinitionsTable.tenantId eq tenantId()) }) {
            it[CustomFieldDefinitionsTable.label] = label
            it[CustomFieldDefinitionsTable.options] = options?.let { values -> Json.readTree(Json.write(values)) }
            it[required] = request.required
            request.sortOrder?.let { order -> it[sortOrder] = order }
        }
        record(id, key, "settings.custom-field.updated", "Custom field '$label' updated", mapOf("entityType" to entity.name))
        view(row(id))
    }

    /** INACTIVE hides the field from forms; stored values stay and are still shown read-only. */
    fun changeStatus(id: UUID, status: String): CustomFieldView = tx.execute {
        val row = row(id)
        if (row[CustomFieldDefinitionsTable.status] != status) {
            CustomFieldDefinitionsTable.update({ (CustomFieldDefinitionsTable.id eq id) and (CustomFieldDefinitionsTable.tenantId eq tenantId()) }) {
                it[CustomFieldDefinitionsTable.status] = status
            }
            record(
                id, row[CustomFieldDefinitionsTable.fieldKey], "settings.custom-field.status-changed",
                "Custom field '${row[CustomFieldDefinitionsTable.label]}' ${if (status == "ACTIVE") "reactivated" else "deactivated"}",
                mapOf("status" to status),
            )
        }
        view(row(id))
    }

    // ---- Helpers (caller's transaction) ----

    private fun row(id: UUID): ResultRow =
        CustomFieldDefinitionsTable.selectAll().where { (CustomFieldDefinitionsTable.id eq id) and (CustomFieldDefinitionsTable.tenantId eq tenantId()) }
            .firstOrNull() ?: throw ApiErrors.customFieldNotFound()

    private fun view(row: ResultRow): CustomFieldView {
        val entity = CustomFieldEntity.valueOf(row[CustomFieldDefinitionsTable.entityType])
        return toView(row, usageByEntity[entity]?.counts()?.get(row[CustomFieldDefinitionsTable.fieldKey]) ?: 0)
    }

    private fun toView(row: ResultRow, usageCount: Long) = CustomFieldView(
        id = row[CustomFieldDefinitionsTable.id].value,
        entityType = row[CustomFieldDefinitionsTable.entityType],
        fieldKey = row[CustomFieldDefinitionsTable.fieldKey],
        label = row[CustomFieldDefinitionsTable.label],
        dataType = row[CustomFieldDefinitionsTable.dataType],
        options = optionsOf(row),
        required = row[CustomFieldDefinitionsTable.required],
        status = row[CustomFieldDefinitionsTable.status],
        sortOrder = row[CustomFieldDefinitionsTable.sortOrder],
        usageCount = usageCount,
        createdAt = row[CustomFieldDefinitionsTable.createdAt],
    )

    private fun optionsOf(row: ResultRow): List<String> = row[CustomFieldDefinitionsTable.options]?.map { it.asText() }.orEmpty()

    /** Case-insensitive within the entity type, inactive fields included (their values still exist). */
    private fun ensureLabelFree(entity: CustomFieldEntity, label: String, exceptId: UUID?) {
        CustomFieldDefinitionsTable.selectAll()
            .where { (CustomFieldDefinitionsTable.tenantId eq tenantId()) and (CustomFieldDefinitionsTable.entityType eq entity.name) }
            .firstOrNull { it[CustomFieldDefinitionsTable.label].equals(label, ignoreCase = true) && it[CustomFieldDefinitionsTable.id].value != exceptId }
            ?.let { throw ApiErrors.duplicateCustomField(label, it[CustomFieldDefinitionsTable.id].value) }
    }

    private fun validOptions(raw: List<String>?): List<String> {
        val options = raw.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        fun invalid(reason: String): Nothing = throw ApiErrors.validationFailed(mapOf("options" to reason))
        if (options.isEmpty()) invalid("A SELECT field needs at least one option")
        if (options.size > MAX_OPTIONS) invalid("At most $MAX_OPTIONS options")
        if (options.any { it.length > MAX_OPTION_LENGTH }) invalid("Each option must be at most $MAX_OPTION_LENGTH characters")
        if (options.map { it.lowercase() }.toSet().size != options.size) invalid("Options must be distinct")
        return options
    }

    private fun requireNoBlanks(entity: CustomFieldEntity, key: String) {
        val blanks = usageByEntity[entity]?.countMissing(key) ?: 0
        if (blanks > 0) throw ApiErrors.fieldHasBlanks(blanks)
    }

    /** The slug, or slug_2, slug_3 … when taken (keys of inactive fields stay reserved). */
    private fun uniqueKey(entity: CustomFieldEntity, base: String): String {
        val taken = CustomFieldDefinitionsTable.select(CustomFieldDefinitionsTable.fieldKey)
            .where { (CustomFieldDefinitionsTable.tenantId eq tenantId()) and (CustomFieldDefinitionsTable.entityType eq entity.name) }
            .map { it[CustomFieldDefinitionsTable.fieldKey] }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base.take(MAX_KEY_LENGTH - 4)}_$it" }.first { it !in taken }
    }

    private fun nextSortOrder(entity: CustomFieldEntity): Int {
        val max = CustomFieldDefinitionsTable.sortOrder.max()
        return (CustomFieldDefinitionsTable.select(max)
            .where { (CustomFieldDefinitionsTable.tenantId eq tenantId()) and (CustomFieldDefinitionsTable.entityType eq entity.name) }
            .single()[max] ?: 0) + 10
    }

    private fun record(id: UUID, key: String, type: String, summary: String, payload: Map<String, Any?>) {
        domainEvents.record(type = type, aggregateType = "custom_field", aggregateId = id, aggregateRef = key, summary = summary, payload = payload)
    }

    private fun tenantId(): UUID = tenantContext.requireTenantId()

    private fun CustomFieldEntity.label() = when (this) {
        CustomFieldEntity.STOCK_ITEM -> "stock items"
        CustomFieldEntity.CUSTOMER -> "customers"
        CustomFieldEntity.SUPPLIER -> "suppliers"
    }

    companion object {
        const val MAX_OPTIONS = 50
        const val MAX_OPTION_LENGTH = 60
        const val MAX_KEY_LENGTH = 60

        /** "Pack Size (cl)" → "pack_size_cl"; accents dropped; a leading digit gets an "f_" prefix. */
        fun slug(label: String): String {
            val ascii = Normalizer.normalize(label, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val slug = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "field" }
            return (if (slug.first().isDigit()) "f_$slug" else slug).take(MAX_KEY_LENGTH - 4).trimEnd('_')
        }
    }
}
