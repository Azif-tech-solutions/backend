package com.zifiq.common.filter

import com.fasterxml.jackson.databind.JsonNode
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.search.NotOp
import com.zifiq.common.search.QualifierOperator
import com.zifiq.common.search.QualifierResolver
import com.zifiq.common.search.QualifierTerm
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.allOf
import com.zifiq.common.search.anyOf
import com.zifiq.common.search.escapeLike
import com.zifiq.common.search.text
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID
import org.jetbrains.exposed.sql.BooleanColumnType
import org.jetbrains.exposed.sql.DecimalColumnType
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType

enum class FilterType { ENUM, UUID, REF, TEXT, NUMBER, MONEY, DATE, DATETIME, BOOLEAN, LOCATION }

enum class FilterOperator(val wire: String) {
    EQ("eq"), NE("ne"), IN("in"), NOT_IN("not_in"),
    CONTAINS("contains"), STARTS_WITH("starts_with"), NOT_CONTAINS("not_contains"),
    GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), BETWEEN("between"),
    IS_NULL("is_null"), IS_NOT_NULL("is_not_null"),
    IS_EMPTY("is_empty"), IS_NOT_EMPTY("is_not_empty"),
    IN_PERIOD("in_period");

    companion object {
        fun parse(value: String): FilterOperator? = entries.firstOrNull { it.wire == value }
    }
}

data class FilterOption(val value: String, val label: String)

/** One filterable field of a list endpoint. [expression] is the SQL it compiles against. */
class FilterField(
    val key: String,
    val label: String,
    val type: FilterType,
    val expression: Expression<*>,
    val options: List<FilterOption> = emptyList(),
    val aliases: Set<String> = emptySet(),
    val sortable: Boolean = false,
    val facetable: Boolean = false,
    /** Cost fields: never filterable, sortable or searchable by callers without cost visibility (docs/03 §5.6). */
    val cost: Boolean = false,
    /** Lookup endpoint the frontend uses to pick values (UUID fields). */
    val lookup: String? = null,
) {
    val operators: List<FilterOperator> = when (type) {
        FilterType.ENUM, FilterType.UUID, FilterType.REF, FilterType.LOCATION ->
            listOf(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN, FilterOperator.NOT_IN, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL)
        FilterType.TEXT ->
            listOf(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CONTAINS, FilterOperator.STARTS_WITH, FilterOperator.NOT_CONTAINS, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL)
        FilterType.NUMBER, FilterType.MONEY ->
            listOf(FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE, FilterOperator.BETWEEN, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL)
        FilterType.DATE, FilterType.DATETIME ->
            listOf(FilterOperator.EQ, FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE, FilterOperator.BETWEEN, FilterOperator.IN_PERIOD, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL)
        FilterType.BOOLEAN -> listOf(FilterOperator.EQ, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL)
    }
}

/**
 * A one-to-many child of the listed entity (invoice lines, payments, waybills). Filters on it
 * compile to `EXISTS (…)` so conditions grouped under the collection hold for the *same* child row.
 * [exists] receives the compiled inner condition (null = "any child exists").
 */
class FilterCollection(
    val key: String,
    val label: String,
    val fields: List<FilterField>,
    val exists: (Op<Boolean>?) -> Op<Boolean>,
)

/** Per-request context: who is asking and when. */
data class FilterContext(
    val costVisible: Boolean,
    val zone: ZoneId,
    val today: LocalDate = LocalDate.now(zone),
    /** Expands a showroom/factory to itself plus its storage areas (docs/03 §11). */
    val expandLocations: (Set<UUID>) -> Set<UUID> = { it },
)

/**
 * The whitelisted filters, sorts and qualifiers of one list endpoint, and their compilation to SQL —
 * docs/implemented/03-cross-module-conventions.md §5. Every value is bound as a parameter.
 */
class FilterSpec(
    val fields: List<FilterField>,
    val collections: List<FilterCollection> = emptyList(),
    /** The field `from`/`to`/`period` apply to. */
    val primaryDateKey: String? = null,
    val defaultSort: List<Pair<String, SortOrder>> = emptyList(),
    /** Stable tiebreaker appended to every sort so paging never repeats or skips rows. */
    val tiebreaker: Expression<*>,
) : QualifierResolver {
    private val byKey = fields.associateBy { it.key }
    private val collectionsByKey = collections.associateBy { it.key }

    // ---- Simple query-parameter filters (§5.2) ----

    /**
     * Compiles query parameters. [params] must contain only filter parameters plus `from`/`to`/
     * `period` — the controller removes paging/search parameters (`q`, `page`, `size`, `sort`, …)
     * first. Unknown names are rejected rather than silently ignored, so a typo never returns an
     * unfiltered list that looks filtered.
     */
    fun fromParams(params: Map<String, List<String>>, context: FilterContext): Op<Boolean>? {
        val conditions = mutableListOf<Op<Boolean>>()
        var from: LocalDate? = null
        var to: LocalDate? = null

        params.forEach { (name, rawValues) ->
            val values = rawValues.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            when (name) {
                "from" -> from = parseDate("from", values.singleOrNull())
                "to" -> to = parseDate("to", values.singleOrNull())
                "period" -> {
                    val period = values.singleOrNull()?.let(DatePeriod::parse)
                        ?: throw ApiErrors.invalidFilter("period", "Unknown period", DatePeriod.entries.map { it.name })
                    val range = period.resolve(context.today)
                    if (from == null) from = range.from
                    if (to == null) to = range.to
                }
                else -> conditions += paramCondition(name, values, context)
            }
        }
        // An explicit from/to wins over a preset; both apply to the endpoint's primary date.
        params["from"]?.firstOrNull()?.let { from = parseDate("from", it) }
        params["to"]?.firstOrNull()?.let { to = parseDate("to", it) }
        if (from != null || to != null) {
            val dateKey = primaryDateKey ?: throw ApiErrors.invalidFilter("from", "This list has no date dimension")
            conditions += dateRange(field(dateKey, context), from, to, context)
        }
        return conditions.allOf()
    }

    private fun paramCondition(name: String, values: List<String>, context: FilterContext): Op<Boolean> {
        // key!=v  arrives from the servlet as a parameter named "key!" with value v.
        if (name.endsWith("!")) return compare(field(name.dropLast(1), context), FilterOperator.NOT_IN, values, context)
        if (name.endsWith("Min")) return compare(field(name.removeSuffix("Min"), context), FilterOperator.GTE, values, context)
        if (name.endsWith("Max")) return compare(field(name.removeSuffix("Max"), context), FilterOperator.LTE, values, context)

        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val suffix = name.substring(dot + 1)
            val key = name.substring(0, dot)
            when (suffix) {
                "isNull" -> return compare(field(key, context), if (values.singleOrNull().toBoolean()) FilterOperator.IS_NULL else FilterOperator.IS_NOT_NULL, emptyList(), context)
                "prefix" -> return compare(field(key, context), FilterOperator.STARTS_WITH, values, context)
                "from" -> return dateRange(field(key, context), parseDate(name, values.singleOrNull()), null, context)
                "to" -> return dateRange(field(key, context), null, parseDate(name, values.singleOrNull()), context)
            }
            FilterOperator.parse(suffix)?.let { return compare(field(key, context), it, values, context) }
        }
        val field = field(name, context)
        val operator = when {
            field.type == FilterType.TEXT -> FilterOperator.CONTAINS
            values.size > 1 -> FilterOperator.IN
            else -> FilterOperator.EQ
        }
        return compare(field, operator, values, context)
    }

    // ---- Advanced JSON filters (§5.3) ----

    fun fromJson(node: JsonNode?, context: FilterContext): Op<Boolean>? {
        if (node == null || node.isNull || node.isEmpty) return null
        val budget = intArrayOf(MAX_LEAVES)
        return compileNode(node, context, depth = 1, budget = budget, scope = null)
    }

    private fun compileNode(node: JsonNode, context: FilterContext, depth: Int, budget: IntArray, scope: FilterCollection?): Op<Boolean>? {
        if (depth > MAX_DEPTH) throw ApiErrors.filterTooComplex("maximum nesting depth is $MAX_DEPTH")
        if (!node.isObject) throw ApiErrors.invalidFilter("filter", "Each filter node must be a JSON object")

        node["all"]?.let { return group(it, context, depth, budget, scope).allOf() }
        node["any"]?.let { return group(it, context, depth, budget, scope).anyOf() }
        node["not"]?.let { child -> return compileNode(child, context, depth + 1, budget, scope)?.let(::NotOp) }

        if (scope == null) {
            val collectionKey = node.fieldNames().asSequence().singleOrNull()?.takeIf { it in collectionsByKey }
            if (collectionKey != null) {
                val collection = collectionsByKey.getValue(collectionKey)
                return collection.exists(compileNode(node[collectionKey], context, depth + 1, budget, collection))
            }
        }
        return leaf(node, context, budget, scope)
    }

    private fun group(array: JsonNode, context: FilterContext, depth: Int, budget: IntArray, scope: FilterCollection?): List<Op<Boolean>> {
        if (!array.isArray) throw ApiErrors.invalidFilter("filter", "'all'/'any' must be an array")
        return array.mapNotNull { compileNode(it, context, depth + 1, budget, scope) }
    }

    private fun leaf(node: JsonNode, context: FilterContext, budget: IntArray, scope: FilterCollection?): Op<Boolean> {
        if (--budget[0] < 0) throw ApiErrors.filterTooComplex("at most $MAX_LEAVES conditions")
        val key = node["field"]?.asText() ?: throw ApiErrors.invalidFilter("filter", "A condition needs 'field' and 'op'")
        val opName = node["op"]?.asText() ?: throw ApiErrors.invalidFilter(key, "Missing 'op'")
        val operator = FilterOperator.parse(opName)
            ?: throw ApiErrors.invalidFilter(key, "Unknown operator '$opName'", FilterOperator.entries.map { it.wire })
        val value = node["value"]
        val values = when {
            value == null || value.isNull -> emptyList()
            value.isArray -> value.map { it.asText() }
            else -> listOf(value.asText())
        }

        // "lines.quantity" outside a collection group = "some line has quantity …".
        if (scope == null && key.contains('.')) {
            val collection = collectionsByKey[key.substringBefore('.')]
            if (collection != null) {
                val inner = collectionField(collection, key.substringAfter('.'), context)
                return collection.exists(compare(inner, operator, values, context))
            }
        }
        if (scope == null && key in collectionsByKey) {
            val collection = collectionsByKey.getValue(key)
            return when (operator) {
                FilterOperator.IS_NOT_EMPTY -> collection.exists(null)
                FilterOperator.IS_EMPTY -> NotOp(collection.exists(null))
                else -> throw ApiErrors.invalidFilter(key, "Collections support only is_empty / is_not_empty", listOf("is_empty", "is_not_empty"))
            }
        }
        val field = if (scope != null) collectionField(scope, key, context) else field(key, context)
        return compare(field, operator, values, context)
    }

    private fun collectionField(collection: FilterCollection, key: String, context: FilterContext): FilterField {
        val field = collection.fields.firstOrNull { it.key == key }
            ?: throw ApiErrors.invalidFilter("${collection.key}.$key", "Unknown field", collection.fields.map { "${collection.key}.${it.key}" })
        if (field.cost && !context.costVisible) throw ApiErrors.invalidFilter("${collection.key}.$key", "Unknown field")
        return field
    }

    // ---- Qualifiers from q (§4.3) ----

    override fun qualifierNames(): Map<String, String> =
        fields.flatMap { field -> (field.aliases + field.key).map { it.lowercase() to field.key } }.toMap()

    override fun costQualifiers(): Set<String> = fields.filter { it.cost }.map { it.key }.toSet()

    override fun qualifierCondition(term: QualifierTerm): Op<Boolean> {
        val field = byKey.getValue(term.field)
        // Qualifiers can't see the request context; cost fields were already removed by the parser
        // for callers without cost visibility, so a permissive context is safe here.
        val context = FilterContext(costVisible = true, zone = ZoneId.of("UTC"))
        val operator = when (term.operator) {
            QualifierOperator.EQ -> when {
                field.type == FilterType.TEXT -> FilterOperator.CONTAINS
                term.values.size > 1 -> FilterOperator.IN
                else -> FilterOperator.EQ
            }
            QualifierOperator.GT -> FilterOperator.GT
            QualifierOperator.GTE -> FilterOperator.GTE
            QualifierOperator.LT -> FilterOperator.LT
            QualifierOperator.LTE -> FilterOperator.LTE
            QualifierOperator.RANGE -> FilterOperator.BETWEEN
        }
        // Enum qualifiers accept labels as well as codes ("status:overdue").
        val values = if (field.type == FilterType.ENUM) term.values.map { resolveEnum(field, it) } else term.values
        return compare(field, operator, values, context)
    }

    // ---- Sorting (§5.7) ----

    /** `sort=field,asc` (repeatable). Empty → [defaultSort]. The tiebreaker is always appended. */
    fun sort(sortParams: List<String>, context: FilterContext): List<Pair<Expression<*>, SortOrder>> {
        val requested = sortParams.filter { it.isNotBlank() }.map { param ->
            val key = param.substringBefore(',').trim()
            val direction = param.substringAfter(',', "asc").trim().lowercase()
            val field = byKey[key]?.takeIf { it.sortable && (!it.cost || context.costVisible) }
                ?: throw ApiErrors.invalidSortField(key, sortableKeys(context))
            val order = when (direction) {
                "asc" -> SortOrder.ASC_NULLS_LAST
                "desc" -> SortOrder.DESC_NULLS_LAST
                else -> throw ApiErrors.invalidSortField(param, listOf("asc", "desc"))
            }
            field.expression to order
        }
        val base = requested.ifEmpty { defaultSort.map { (key, order) -> byKey.getValue(key).expression to order } }
        return base + (tiebreaker to SortOrder.ASC)
    }

    fun sortableKeys(context: FilterContext) = fields.filter { it.sortable && (!it.cost || context.costVisible) }.map { it.key }

    // ---- Metadata for GET <list>/filters (§5.4) ----

    fun metadata(context: FilterContext): FilterMetadata = FilterMetadata(
        fields = fields.filter { !it.cost || context.costVisible }.map { it.describe() } +
            collections.flatMap { collection ->
                collection.fields.filter { !it.cost || context.costVisible }
                    .map { it.describe(prefix = "${collection.key}.") }
            },
        collections = collections.map { FilterCollectionMetadata(it.key, it.label) },
        primaryDate = primaryDateKey,
        defaultSort = defaultSort.map { (key, order) -> SortMetadata(key, if (order.name.startsWith("DESC")) "desc" else "asc") },
        periodPresets = DatePeriod.entries.map { it.name },
    )

    private fun FilterField.describe(prefix: String = "") = FilterFieldMetadata(
        key = prefix + key,
        label = label,
        type = type.name,
        operators = operators.map { it.wire },
        options = options,
        lookup = lookup,
        facetable = facetable,
        sortable = sortable,
        qualifierAliases = aliases.sorted(),
    )

    // ---- Compilation ----

    fun field(key: String, context: FilterContext): FilterField {
        val field = byKey[key]
        if (field == null || (field.cost && !context.costVisible)) {
            throw ApiErrors.invalidFilter(key, "Unknown filter", fields.filter { !it.cost || context.costVisible }.map { it.key })
        }
        return field
    }

    private fun compare(field: FilterField, operator: FilterOperator, rawValues: List<String>, context: FilterContext): Op<Boolean> {
        if (operator !in field.operators) {
            throw ApiErrors.invalidFilter(field.key, "Operator '${operator.wire}' is not valid for a ${field.type} field", field.operators.map { it.wire })
        }
        if (rawValues.size > MAX_IN_VALUES) throw ApiErrors.filterTooComplex("at most $MAX_IN_VALUES values per filter")
        val expr = field.expression

        when (operator) {
            FilterOperator.IS_NULL -> return RawOp { +"("; +expr; +") IS NULL" }
            FilterOperator.IS_NOT_NULL -> return RawOp { +"("; +expr; +") IS NOT NULL" }
            FilterOperator.IN_PERIOD -> {
                val period = rawValues.singleOrNull()?.let(DatePeriod::parse)
                    ?: throw ApiErrors.invalidFilter(field.key, "Expected one period", DatePeriod.entries.map { it.name })
                val range = period.resolve(context.today)
                return dateRange(field, range.from, range.to, context)
            }
            else -> Unit
        }
        if (rawValues.isEmpty()) throw ApiErrors.invalidFilter(field.key, "A value is required")

        if (field.type == FilterType.TEXT) return textCompare(field, operator, rawValues.first())
        if (field.type == FilterType.DATETIME) return dateTimeCompare(field, operator, rawValues, context)

        val values = rawValues.map { convert(field, it, context) }.let { converted ->
            if (field.type == FilterType.LOCATION) context.expandLocations(converted.map { it as UUID }.toSet()).toList() else converted
        }
        val columnType = columnType(field.type)
        val cast = if (field.type == FilterType.ENUM || field.type == FilterType.REF) "::text" else ""

        fun QueryBuilder.lhs() { +"("; +expr; +")$cast" }
        fun QueryBuilder.list() = values.forEachIndexed { index, value -> if (index > 0) +", "; registerArgument(columnType, value) }

        return when (operator) {
            FilterOperator.EQ -> if (values.size == 1) RawOp { lhs(); +" = "; registerArgument(columnType, values.single()) } else RawOp { lhs(); +" IN ("; list(); +")" }
            FilterOperator.IN -> RawOp { lhs(); +" IN ("; list(); +")" }
            FilterOperator.NE -> RawOp { lhs(); +" IS DISTINCT FROM "; registerArgument(columnType, values.single()) }
            FilterOperator.NOT_IN -> NotOp(RawOp { lhs(); +" IN ("; list(); +")" })
            FilterOperator.GT -> RawOp { lhs(); +" > "; registerArgument(columnType, values.single()) }
            FilterOperator.GTE -> RawOp { lhs(); +" >= "; registerArgument(columnType, values.single()) }
            FilterOperator.LT -> RawOp { lhs(); +" < "; registerArgument(columnType, values.single()) }
            FilterOperator.LTE -> RawOp { lhs(); +" <= "; registerArgument(columnType, values.single()) }
            FilterOperator.BETWEEN -> {
                if (values.size != 2) throw ApiErrors.invalidFilter(field.key, "between needs exactly two values")
                RawOp { lhs(); +" BETWEEN "; registerArgument(columnType, values[0]); +" AND "; registerArgument(columnType, values[1]) }
            }
            else -> throw ApiErrors.invalidFilter(field.key, "Operator '${operator.wire}' is not valid here")
        }
    }

    private fun textCompare(field: FilterField, operator: FilterOperator, value: String): Op<Boolean> {
        if (value.length > MAX_TEXT_LENGTH) throw ApiErrors.invalidFilter(field.key, "Value is longer than $MAX_TEXT_LENGTH characters")
        val expr = field.expression
        val contains = RawOp { +"f_unaccent(lower("; +expr; +")) LIKE '%' || f_unaccent(lower("; text(escapeLike(value)); +")) || '%'" }
        return when (operator) {
            FilterOperator.EQ -> RawOp { +"lower("; +expr; +") = lower("; text(value); +")" }
            FilterOperator.NE -> RawOp { +"lower("; +expr; +") IS DISTINCT FROM lower("; text(value); +")" }
            FilterOperator.CONTAINS -> contains
            FilterOperator.NOT_CONTAINS -> NotOp(contains)
            FilterOperator.STARTS_WITH -> RawOp { +"f_unaccent(lower("; +expr; +")) LIKE f_unaccent(lower("; text(escapeLike(value)); +")) || '%'" }
            else -> throw ApiErrors.invalidFilter(field.key, "Operator '${operator.wire}' is not valid for text")
        }
    }

    /** DATETIME fields accept dates (whole days in the tenant timezone) or ISO instants. */
    private fun dateTimeCompare(field: FilterField, operator: FilterOperator, values: List<String>, context: FilterContext): Op<Boolean> {
        fun dayStart(date: LocalDate) = date.atStartOfDay(context.zone).toInstant()
        val dates = values.map { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (dates.all { it != null }) {
            val d = dates.filterNotNull()
            return when (operator) {
                FilterOperator.EQ -> dateRange(field, d.single(), d.single(), context)
                FilterOperator.GT -> instantCompare(field, ">=", dayStart(d.single().plusDays(1)))
                FilterOperator.GTE -> instantCompare(field, ">=", dayStart(d.single()))
                FilterOperator.LT -> instantCompare(field, "<", dayStart(d.single()))
                FilterOperator.LTE -> instantCompare(field, "<", dayStart(d.single().plusDays(1)))
                FilterOperator.BETWEEN -> dateRange(field, d.getOrNull(0), d.getOrNull(1), context)
                else -> throw ApiErrors.invalidFilter(field.key, "Operator '${operator.wire}' is not valid for dates")
            }
        }
        val instants = values.map { value ->
            runCatching { Instant.parse(value) }.getOrElse { throw ApiErrors.invalidFilter(field.key, "Expected a date (yyyy-MM-dd) or ISO timestamp") }
        }
        return when (operator) {
            FilterOperator.EQ -> instantCompare(field, "=", instants.single())
            FilterOperator.GT -> instantCompare(field, ">", instants.single())
            FilterOperator.GTE -> instantCompare(field, ">=", instants.single())
            FilterOperator.LT -> instantCompare(field, "<", instants.single())
            FilterOperator.LTE -> instantCompare(field, "<=", instants.single())
            FilterOperator.BETWEEN -> listOf(instantCompare(field, ">=", instants[0]), instantCompare(field, "<=", instants[1])).allOf()!!
            else -> throw ApiErrors.invalidFilter(field.key, "Operator '${operator.wire}' is not valid for timestamps")
        }
    }

    private fun instantCompare(field: FilterField, sqlOperator: String, value: Instant) =
        RawOp { +"("; +field.expression; +") $sqlOperator "; registerArgument(JavaInstantColumnType(), value) }

    /** Inclusive day range. DATETIME columns use [from 00:00, to+1 00:00) in the tenant timezone. */
    private fun dateRange(field: FilterField, from: LocalDate?, to: LocalDate?, context: FilterContext): Op<Boolean> {
        if (from != null && to != null && from.isAfter(to)) throw ApiErrors.invalidFilter(field.key, "'from' is after 'to'")
        val parts = mutableListOf<Op<Boolean>>()
        when (field.type) {
            FilterType.DATETIME -> {
                from?.let { parts += instantCompare(field, ">=", it.atStartOfDay(context.zone).toInstant()) }
                to?.let { parts += instantCompare(field, "<", it.plusDays(1).atStartOfDay(context.zone).toInstant()) }
            }
            FilterType.DATE -> {
                from?.let { parts += RawOp { +"("; +field.expression; +") >= "; registerArgument(JavaLocalDateColumnType(), it) } }
                to?.let { parts += RawOp { +"("; +field.expression; +") <= "; registerArgument(JavaLocalDateColumnType(), it) } }
            }
            else -> throw ApiErrors.invalidFilter(field.key, "Not a date field")
        }
        return parts.allOf() ?: RawOp { +"TRUE" }
    }

    private fun convert(field: FilterField, raw: String, context: FilterContext): Any = try {
        when (field.type) {
            FilterType.ENUM -> resolveEnum(field, raw)
            FilterType.UUID, FilterType.LOCATION -> UUID.fromString(raw)
            FilterType.REF -> raw.take(MAX_TEXT_LENGTH)
            FilterType.NUMBER, FilterType.MONEY -> BigDecimal(raw.replace(",", "").removePrefix("₦"))
            FilterType.DATE -> LocalDate.parse(raw)
            FilterType.BOOLEAN -> when (raw.lowercase()) {
                "true", "yes", "1" -> true
                "false", "no", "0" -> false
                else -> throw IllegalArgumentException()
            }
            FilterType.TEXT, FilterType.DATETIME -> raw
        }
    } catch (error: IllegalArgumentException) {
        throw ApiErrors.invalidFilter(field.key, "'$raw' is not a valid ${field.type.name.lowercase()}")
    } catch (error: DateTimeParseException) {
        throw ApiErrors.invalidFilter(field.key, "'$raw' is not a valid date (yyyy-MM-dd)")
    }

    /**
     * Matches an enum by code or label, case-insensitively, or by one word of either when that word
     * picks out a single option (`type:dealer` → AUTHORISED_DEALER; `customer` is ambiguous and
     * rejected). With no declared options any value is accepted.
     */
    private fun resolveEnum(field: FilterField, raw: String): String {
        if (field.options.isEmpty()) return raw
        field.options.firstOrNull { it.value.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true) }?.let { return it.value }
        val word = raw.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val byWord = field.options.filter { option ->
            (option.value.split('_') + option.label.split(Regex("[^\\p{L}\\p{N}]+")))
                .any { it.isNotEmpty() && it.lowercase().replace("-", "") == word }
        }
        return byWord.singleOrNull()?.value
            ?: throw ApiErrors.invalidFilter(field.key, "Unknown value '$raw'", field.options.map { it.value })
    }

    private fun columnType(type: FilterType): IColumnType<*> = when (type) {
        FilterType.UUID, FilterType.LOCATION -> UUIDColumnType()
        FilterType.NUMBER, FilterType.MONEY -> DecimalColumnType(20, 4)
        FilterType.DATE -> JavaLocalDateColumnType()
        FilterType.BOOLEAN -> BooleanColumnType()
        FilterType.DATETIME -> JavaInstantColumnType()
        FilterType.ENUM, FilterType.REF, FilterType.TEXT -> org.jetbrains.exposed.sql.TextColumnType()
    }

    private fun parseDate(name: String, value: String?): LocalDate? = value?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw ApiErrors.invalidFilter(name, "'$value' is not a valid date (yyyy-MM-dd)") }
    }

    companion object {
        const val MAX_DEPTH = 4
        const val MAX_LEAVES = 30
        const val MAX_IN_VALUES = 200
        const val MAX_TEXT_LENGTH = 200

        /** Query parameters that are never filters. */
        val RESERVED_PARAMS = setOf("q", "page", "size", "sort", "after", "facets", "highlight", "summary")
    }
}

data class FilterFieldMetadata(
    val key: String,
    val label: String,
    val type: String,
    val operators: List<String>,
    val options: List<FilterOption>,
    val lookup: String?,
    val facetable: Boolean,
    val sortable: Boolean,
    val qualifierAliases: List<String>,
)

data class FilterCollectionMetadata(val key: String, val label: String)

data class SortMetadata(val field: String, val dir: String)

data class FilterMetadata(
    val fields: List<FilterFieldMetadata>,
    val collections: List<FilterCollectionMetadata>,
    val primaryDate: String?,
    val defaultSort: List<SortMetadata>,
    val periodPresets: List<String>,
)
