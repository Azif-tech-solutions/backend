package com.zifiq.history.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.history.dto.ApplyFlagRequest
import com.zifiq.history.dto.DataQualityFlagRow
import com.zifiq.history.dto.DismissFlagRequest
import com.zifiq.history.dto.ManualFlagRequest
import com.zifiq.history.dto.ScanResult
import com.zifiq.history.model.DataQualityFlagsTable
import com.zifiq.history.model.DataQualityIgnoresTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.directory.Names
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Error Handler (docs/10 §4.8): finds likely spelling slips and naming inconsistencies in product
 * data and lets an Administrator correct the master record. The scan is a bounded nightly batch
 * (stock items per tenant are in the hundreds/thousands), so the token analysis runs in memory
 * rather than as hot SQL. This phase scans stock-item NAMEs; units and categories (owned by Factory,
 * not an open module) and BRAND need a factory SPI and are not scanned yet — manual flags still
 * accept them.
 */
@Service
class DataQualityService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
    private val sequences: DocumentSequenceService,
) {
    fun scan(): ScanResult = tx.execute {
        if (!support.settings().boolean(HistorySettings.DATA_QUALITY_SCAN_ENABLED)) return@execute ScanResult(0)
        val products = StockItemsTable.select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name)
            .where { (StockItemsTable.tenantId eq support.tenantId()) and (StockItemsTable.status inList listOf("ACTIVE", "SUSPENDED")) }
            .map { Product(it[StockItemsTable.id].value, it[StockItemsTable.code], it[StockItemsTable.name]) }
        if (products.isEmpty()) return@execute ScanResult(0)
        val ignores = DataQualityIgnoresTable.select(DataQualityIgnoresTable.token).where { DataQualityIgnoresTable.tenantId eq support.tenantId() }
            .map { it[DataQualityIgnoresTable.token] }.toSet()
        val alreadyOpen = DataQualityFlagsTable.select(DataQualityFlagsTable.entityId)
            .where { (DataQualityFlagsTable.tenantId eq support.tenantId()) and (DataQualityFlagsTable.field eq "NAME") and (DataQualityFlagsTable.status eq "OPEN") }
            .map { it[DataQualityFlagsTable.entityId] }.toMutableSet()

        val tokenProducts = HashMap<String, MutableSet<UUID>>()
        products.forEach { p -> tokens(p.name, ignores).forEach { tokenProducts.getOrPut(it) { mutableSetOf() }.add(p.id) } }
        val count = tokenProducts.mapValues { it.value.size }
        var raised = 0

        // SPELLING: a rare token near a common one.
        for (p in products) {
            if (p.id in alreadyOpen) continue
            for (t in tokens(p.name, ignores)) {
                if ((count[t] ?: 0) > 2) continue
                val candidate = count.entries.firstOrNull { (c, n) ->
                    c != t && n >= 3 && n >= 3 * (count[t] ?: 1) && levenshtein(t, c) <= (if (t.length >= 7) 2 else 1)
                }?.key ?: continue
                val suggested = replaceToken(p.name, t, candidate)
                raise(p.id, p.code, p.name, suggested, "SPELLING"); alreadyOpen.add(p.id); raised++
                break
            }
        }
        // NAMING: distinct names that normalise to the same thing.
        products.filter { it.id !in alreadyOpen }.groupBy { normalize(it.name) }.values
            .filter { grp -> grp.map { it.name }.distinct().size > 1 }
            .forEach { grp ->
                val canonical = grp.groupingBy { it.name }.eachCount().maxByOrNull { it.value }!!.key
                grp.filter { it.name != canonical && it.id !in alreadyOpen }.forEach { p ->
                    raise(p.id, p.code, p.name, canonical, "NAMING"); alreadyOpen.add(p.id); raised++
                }
            }

        if (raised > 0) support.record("history.data-quality.flagged", "data_quality", support.tenantId(), null, "$raised new data-quality flag(s)", mapOf("openCount" to raised))
        ScanResult(raised)
    }

    fun createFlag(request: ManualFlagRequest): DataQualityFlagRow = tx.execute {
        val entityType = request.entityType!!.trim().uppercase()
        val field = request.field!!.trim().uppercase()
        if (entityType != "STOCK_ITEM") throw ApiErrors.validationFailed(mapOf("entityType" to "only STOCK_ITEM is supported this phase"))
        if (field !in setOf("NAME", "BRAND")) throw ApiErrors.validationFailed(mapOf("field" to "NAME or BRAND"))
        val item = stockItem(request.entityId!!)
        val current = if (field == "NAME") item[StockItemsTable.name] else (item[StockItemsTable.brand] ?: "")
        val id = insertFlag(request.entityId, item[StockItemsTable.code], field, current, request.suggestedValue?.trim()?.ifEmpty { null }, "NAMING", "USER", support.userId())
        row(id)
    }

    fun list(status: String?, kind: String?, entityType: String?, paging: PageRequest): PageResult<DataQualityFlagRow> = tx.execute {
        var cond = DataQualityFlagsTable.tenantId eq support.tenantId()
        (status?.trim()?.uppercase() ?: "OPEN").let { cond = cond and (DataQualityFlagsTable.status eq it) }
        kind?.trim()?.uppercase()?.let { cond = cond and (DataQualityFlagsTable.kind eq it) }
        entityType?.trim()?.uppercase()?.let { cond = cond and (DataQualityFlagsTable.entityType eq it) }
        val page = Paging.fetch(DataQualityFlagsTable.selectAll().where(cond).orderBy(DataQualityFlagsTable.createdAt to SortOrder.DESC), paging) { it }
        val names = Names.users(page.items.mapNotNull { it[DataQualityFlagsTable.flaggedByUserId] })
        page.withItems(page.items.map { toRow(it, names) })
    }

    fun apply(id: UUID, request: ApplyFlagRequest): DataQualityFlagRow = tx.execute {
        val flag = flagForUpdate(id)
        if (flag[DataQualityFlagsTable.status] != "OPEN") throw ApiErrors.flagNotOpen()
        val value = (request.value ?: flag[DataQualityFlagsTable.suggestedValue])?.trim()
            ?: throw ApiErrors.validationFailed(mapOf("value" to "a correction is required"))
        if (value == flag[DataQualityFlagsTable.currentValue]) throw ApiErrors.validationFailed(mapOf("value" to "must differ from the current value"))
        val entityId = flag[DataQualityFlagsTable.entityId]
        val field = flag[DataQualityFlagsTable.field]
        val item = stockItem(entityId)
        val current = if (field == "NAME") item[StockItemsTable.name] else (item[StockItemsTable.brand] ?: "")
        if (current != flag[DataQualityFlagsTable.currentValue]) throw ApiErrors.flagStale()
        StockItemsTable.update({ (StockItemsTable.id eq entityId) and (StockItemsTable.tenantId eq support.tenantId()) }) {
            if (field == "NAME") it[name] = value else it[brand] = value
        }
        support.record("inventory.stock-item.updated", "stock_item", entityId, item[StockItemsTable.code],
            "${item[StockItemsTable.code]}: $field corrected to \"$value\"", mapOf("field" to field, "from" to current, "to" to value), item[StockItemsTable.locationId])
        DataQualityFlagsTable.update({ DataQualityFlagsTable.id eq id }) {
            it[status] = "CORRECTED"; it[appliedValue] = value; it[resolvedByUserId] = support.userId(); it[resolvedAt] = Instant.now()
        }
        row(id)
    }

    fun dismiss(id: UUID, request: DismissFlagRequest): DataQualityFlagRow = tx.execute {
        val flag = flagForUpdate(id)
        if (flag[DataQualityFlagsTable.status] != "OPEN") throw ApiErrors.flagNotOpen()
        if (request.ignoreToken) {
            val token = if (flag[DataQualityFlagsTable.kind] == "SPELLING") firstFlaggedToken(flag) else normalize(flag[DataQualityFlagsTable.currentValue])
            if (token.isNotBlank()) {
                DataQualityIgnoresTable.insert {
                    it[tenantId] = support.tenantId(); it[DataQualityIgnoresTable.token] = token.take(120)
                    it[createdByUserId] = support.userId(); it[createdAt] = Instant.now()
                }
            }
        }
        DataQualityFlagsTable.update({ DataQualityFlagsTable.id eq id }) {
            it[status] = "DISMISSED"; it[dismissReason] = request.reason!!.trim(); it[resolvedByUserId] = support.userId(); it[resolvedAt] = Instant.now()
        }
        row(id)
    }

    // ---- helpers ----

    private data class Product(val id: UUID, val code: String, val name: String)

    private fun raise(entityId: UUID, code: String, current: String, suggested: String, kind: String) {
        insertFlag(entityId, code, "NAME", current, suggested, kind, "SYSTEM", null)
    }

    private fun insertFlag(entityId: UUID, code: String?, field: String, current: String, suggested: String?, kind: String, source: String, byUser: UUID?): UUID {
        val ref = sequences.next("ER")
        return DataQualityFlagsTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[DataQualityFlagsTable.ref] = ref
            it[entityType] = "STOCK_ITEM"
            it[DataQualityFlagsTable.entityId] = entityId
            it[entityCode] = code
            it[DataQualityFlagsTable.field] = field
            it[currentValue] = current.take(200)
            it[suggestedValue] = suggested?.take(200)
            it[DataQualityFlagsTable.kind] = kind
            it[DataQualityFlagsTable.sourceKind] = source
            it[flaggedByUserId] = byUser
            it[status] = "OPEN"
            it[createdAt] = Instant.now()
        }.value
    }

    private fun stockItem(id: UUID): ResultRow =
        StockItemsTable.selectAll().where { (StockItemsTable.id eq id) and (StockItemsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.stockItemNotFound()

    private fun flagForUpdate(id: UUID): ResultRow =
        DataQualityFlagsTable.selectAll().where { (DataQualityFlagsTable.id eq id) and (DataQualityFlagsTable.tenantId eq support.tenantId()) }.forUpdate().firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("id" to "flag not found"))

    private fun row(id: UUID): DataQualityFlagRow {
        val r = DataQualityFlagsTable.selectAll().where { DataQualityFlagsTable.id eq id }.single()
        return toRow(r, Names.users(listOfNotNull(r[DataQualityFlagsTable.flaggedByUserId])))
    }

    private fun toRow(r: ResultRow, names: Map<UUID, String>) = DataQualityFlagRow(
        id = r[DataQualityFlagsTable.id].value, ref = r[DataQualityFlagsTable.ref], entityType = r[DataQualityFlagsTable.entityType],
        entityCode = r[DataQualityFlagsTable.entityCode], field = r[DataQualityFlagsTable.field], currentValue = r[DataQualityFlagsTable.currentValue],
        suggestedValue = r[DataQualityFlagsTable.suggestedValue], kind = if (r[DataQualityFlagsTable.kind] == "SPELLING") "Spelling" else "Naming",
        flaggedBy = r[DataQualityFlagsTable.flaggedByUserId]?.let { names[it] ?: "User" } ?: "System spell check",
        flaggedOn = r[DataQualityFlagsTable.createdAt], status = r[DataQualityFlagsTable.status], appliedValue = r[DataQualityFlagsTable.appliedValue],
    )

    private fun firstFlaggedToken(flag: ResultRow): String {
        val suggested = flag[DataQualityFlagsTable.suggestedValue] ?: return ""
        val current = tokens(flag[DataQualityFlagsTable.currentValue], emptySet())
        val sugg = tokens(suggested, emptySet())
        return current.firstOrNull { it !in sugg } ?: ""
    }

    private fun tokens(value: String, ignores: Set<String>): List<String> =
        value.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && !it.any { c -> c.isDigit() } && it !in ignores }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun replaceToken(name: String, from: String, to: String): String =
        name.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { if (it.lowercase() == from) matchCase(it, to) else it }

    private fun matchCase(original: String, replacement: String): String =
        if (original.isNotEmpty() && original[0].isUpperCase()) replacement.replaceFirstChar { it.uppercase() } else replacement

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
