package com.zifiq.inventory.codes

import com.zifiq.common.errors.ApiErrors
import com.zifiq.inventory.model.StockItemCodesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * All mutations of `stock_item_codes` (docs/11 §4.1), in OPEN inventory so both registration and the
 * barcode module's Codes API use one implementation — uniqueness, primary rules, the mirror to
 * `stock_items.barcode`/`barcode_symbology`, and the `barcode.code.*` events live here once. Every
 * method runs in the caller's transaction; the barcode module adds permission and location checks.
 */
@Service
class StockItemCodeService(
    private val actors: CurrentActorProvider,
    private val generator: BarcodeGenerator,
    private val domainEvents: DomainEvents,
) {
    data class CodeView(
        val id: UUID,
        val code: String,
        val symbology: String,
        val source: String,
        val isPrimary: Boolean,
        val packQuantity: BigDecimal,
        val packLabel: String?,
        val status: String,
        val retiredReason: String?,
        val assignedByUserId: UUID,
        val assignedAt: Instant,
    )

    fun list(stockItemId: UUID, status: String? = null): List<CodeView> {
        val item = requireItem(stockItemId)
        return StockItemCodesTable.selectAll()
            .where {
                var c = (StockItemCodesTable.tenantId eq item[StockItemsTable.tenantId].value) and (StockItemCodesTable.stockItemId eq stockItemId)
                if (status != null) c = c and (StockItemCodesTable.status eq status)
                c
            }
            .orderBy(StockItemCodesTable.isPrimary to SortOrder.DESC, StockItemCodesTable.assignedAt to SortOrder.ASC)
            .map(::toView)
    }

    /** Generate and assign a primary code. Fails if the item already has an active primary (use replacePrimary). */
    fun generatePrimary(stockItemId: UUID, symbology: String? = null): CodeView {
        val item = requireItem(stockItemId)
        if (activePrimary(stockItemId) != null) throw ApiErrors.primaryCodeExists()
        val sym = symbology ?: generator.defaultSymbology(item[StockItemsTable.itemType])
        val generated = generator.generate(item[StockItemsTable.code], sym)
        return insert(item, generated.code, generated.symbology, "GENERATED", isPrimary = true, packQuantity = BigDecimal.ONE, packLabel = null)
    }

    /** Assign an existing code (manufacturer or manual). A carton code (packQuantity > 1) can never be the primary. */
    fun assign(
        stockItemId: UUID,
        code: String,
        symbology: String,
        source: String,
        isPrimary: Boolean,
        packQuantity: BigDecimal = BigDecimal.ONE,
        packLabel: String? = null,
    ): CodeView {
        val item = requireItem(stockItemId)
        validateFormat(code, symbology)
        if (packQuantity <= BigDecimal.ZERO) throw ApiErrors.invalidCodeFormat("pack quantity must be positive")
        if (isPrimary && packQuantity.compareTo(BigDecimal.ONE) != 0) throw ApiErrors.primaryMustBeUnit()
        if (isPrimary) demoteActivePrimary(stockItemId)
        return insert(item, code, symbology, source, isPrimary, packQuantity, packLabel)
    }

    /** Make [codeId] the primary; the old primary stays ACTIVE as an alias so its printed labels still scan. */
    fun replacePrimary(stockItemId: UUID, codeId: UUID): CodeView {
        val item = requireItem(stockItemId)
        val target = requireCode(stockItemId, codeId)
        if (target[StockItemCodesTable.status] != "ACTIVE") throw ApiErrors.codeNotFound()
        if (target[StockItemCodesTable.packQuantity].compareTo(BigDecimal.ONE) != 0) throw ApiErrors.primaryMustBeUnit()
        demoteActivePrimary(stockItemId)
        StockItemCodesTable.update({ StockItemCodesTable.id eq codeId }) { it[isPrimary] = true }
        mirrorPrimary(item, target[StockItemCodesTable.code], target[StockItemCodesTable.symbology])
        domainEvents.record(
            type = "barcode.code.replaced", aggregateType = "stock-item", aggregateId = stockItemId, aggregateRef = item[StockItemsTable.code],
            summary = "Primary code for ${item[StockItemsTable.code]} changed to ${target[StockItemCodesTable.code]}",
            payload = mapOf("code" to target[StockItemCodesTable.code]), locationId = item[StockItemsTable.locationId],
        )
        return toView(requireCode(stockItemId, codeId))
    }

    /**
     * Retire a code. Retiring the primary needs a replacement promoted in the same call (or the item
     * to be DELETED/PURGED). A retired code is never reused and resolves to BLOCKED, never MATCHED.
     */
    fun retire(stockItemId: UUID, codeId: UUID, reason: String, newPrimaryCodeId: UUID? = null) {
        val item = requireItem(stockItemId)
        val code = requireCode(stockItemId, codeId)
        if (code[StockItemCodesTable.status] != "ACTIVE") throw ApiErrors.codeNotFound()
        val recycled = item[StockItemsTable.status] in setOf("DELETED", "PURGED")
        StockItemCodesTable.update({ StockItemCodesTable.id eq codeId }) {
            it[status] = "RETIRED"; it[retiredReason] = reason.take(200); it[isPrimary] = false; it[retiredAt] = Instant.now()
        }
        if (code[StockItemCodesTable.isPrimary]) {
            when {
                newPrimaryCodeId != null -> {
                    val next = requireCode(stockItemId, newPrimaryCodeId)
                    if (next[StockItemCodesTable.status] != "ACTIVE" || next[StockItemCodesTable.packQuantity].compareTo(BigDecimal.ONE) != 0) throw ApiErrors.primaryMustBeUnit()
                    StockItemCodesTable.update({ StockItemCodesTable.id eq newPrimaryCodeId }) { it[isPrimary] = true }
                    mirrorPrimary(item, next[StockItemCodesTable.code], next[StockItemCodesTable.symbology])
                }
                recycled -> mirrorPrimary(item, null, null)
                else -> throw ApiErrors.cannotRetirePrimary()
            }
        }
        domainEvents.record(
            type = "barcode.code.retired", aggregateType = "stock-item", aggregateId = stockItemId, aggregateRef = item[StockItemsTable.code],
            summary = "Code ${code[StockItemCodesTable.code]} retired: $reason",
            payload = mapOf("code" to code[StockItemCodesTable.code]), locationId = item[StockItemsTable.locationId],
        )
    }

    /**
     * Registration hook (docs/11 §4.1). A supplied [barcode] is validated and assigned as the primary;
     * otherwise, when [generate] is true, one is generated. No code at all leaves the item for the
     * unlabelled report. Call this inside the registering transaction, right after the item is inserted.
     */
    fun assignAtRegistration(stockItemId: UUID, barcode: String?, symbology: String?, generate: Boolean) {
        val item = requireItem(stockItemId)
        val given = barcode?.trim()?.takeIf(String::isNotEmpty)
        when {
            given != null -> {
                val sym = symbology ?: inferSymbology(given)
                assign(stockItemId, given, sym, "MANUAL", isPrimary = true)
            }
            generate -> generatePrimary(stockItemId, symbology)
            else -> {} // no code; appears in the unlabelled-items report
        }
    }

    // ---- internals ----

    private fun insert(item: ResultRow, code: String, symbology: String, source: String, isPrimary: Boolean, packQuantity: BigDecimal, packLabel: String?): CodeView {
        val tenantId = item[StockItemsTable.tenantId].value
        val normalized = normalize(code)
        val clash = StockItemCodesTable.selectAll()
            .where { (StockItemCodesTable.tenantId eq tenantId) and (StockItemCodesTable.codeNormalized eq normalized) }
            .firstOrNull()
        if (clash != null) throw ApiErrors.duplicateBarcode(code)
        val id = StockItemCodesTable.insertAndGetId {
            it[StockItemCodesTable.tenantId] = tenantId
            it[StockItemCodesTable.stockItemId] = item[StockItemsTable.id].value
            it[StockItemCodesTable.code] = code
            it[StockItemCodesTable.symbology] = symbology
            it[sourceKind] = source
            it[StockItemCodesTable.isPrimary] = isPrimary
            it[StockItemCodesTable.packQuantity] = packQuantity
            it[StockItemCodesTable.packLabel] = packLabel
            it[status] = "ACTIVE"
            it[assignedByUserId] = actors.current().userId
            it[assignedAt] = Instant.now()
        }.value
        if (isPrimary) mirrorPrimary(item, code, symbology)
        domainEvents.record(
            type = "barcode.code.assigned", aggregateType = "stock-item", aggregateId = item[StockItemsTable.id].value, aggregateRef = item[StockItemsTable.code],
            summary = "$symbology code $code assigned to ${item[StockItemsTable.code]}",
            payload = mapOf("code" to code, "symbology" to symbology, "source" to source), locationId = item[StockItemsTable.locationId],
        )
        return toView(requireCode(item[StockItemsTable.id].value, id))
    }

    private fun mirrorPrimary(item: ResultRow, code: String?, symbology: String?) {
        StockItemsTable.update({ StockItemsTable.id eq item[StockItemsTable.id].value }) {
            it[barcode] = code
            it[barcodeSymbology] = symbology
        }
    }

    private fun demoteActivePrimary(stockItemId: UUID) {
        activePrimary(stockItemId)?.let { p ->
            StockItemCodesTable.update({ StockItemCodesTable.id eq p[StockItemCodesTable.id].value }) { it[isPrimary] = false }
        }
    }

    private fun activePrimary(stockItemId: UUID): ResultRow? =
        StockItemCodesTable.selectAll()
            .where { (StockItemCodesTable.stockItemId eq stockItemId) and (StockItemCodesTable.isPrimary eq true) and (StockItemCodesTable.status eq "ACTIVE") }
            .firstOrNull()

    private fun requireItem(stockItemId: UUID): ResultRow =
        StockItemsTable.selectAll().where { (StockItemsTable.id eq stockItemId) and (StockItemsTable.tenantId eq actors.current().tenantId) }.firstOrNull()
            ?: throw ApiErrors.stockItemNotFound()

    private fun requireCode(stockItemId: UUID, codeId: UUID): ResultRow =
        StockItemCodesTable.selectAll().where { (StockItemCodesTable.id eq codeId) and (StockItemCodesTable.stockItemId eq stockItemId) }.firstOrNull()
            ?: throw ApiErrors.codeNotFound()

    private fun validateFormat(code: String, symbology: String) {
        when (symbology) {
            "EAN_13", "EAN_8", "UPC_A" -> if (!CheckDigits.isValidGtin(code)) throw ApiErrors.invalidCheckDigit()
            "QR" -> if (code.isBlank() || code.length > 200) throw ApiErrors.invalidCodeFormat("QR payload must be 1–200 characters")
            "CODE_128" -> if (code.isBlank() || code.length > 48 || code.any { it.code !in 32..126 }) throw ApiErrors.invalidCodeFormat("Code 128 must be 1–48 printable ASCII characters")
            else -> throw ApiErrors.invalidCodeFormat("unknown symbology $symbology")
        }
    }

    private fun inferSymbology(code: String): String = when {
        CheckDigits.isValidGtin(code) && code.length == 13 -> "EAN_13"
        CheckDigits.isValidGtin(code) && code.length == 8 -> "EAN_8"
        CheckDigits.isValidGtin(code) && code.length == 12 -> "UPC_A"
        else -> "CODE_128"
    }

    private fun normalize(code: String): String = code.uppercase().replace(Regex("\\s"), "")

    private fun toView(r: ResultRow) = CodeView(
        id = r[StockItemCodesTable.id].value, code = r[StockItemCodesTable.code], symbology = r[StockItemCodesTable.symbology],
        source = r[StockItemCodesTable.sourceKind], isPrimary = r[StockItemCodesTable.isPrimary], packQuantity = r[StockItemCodesTable.packQuantity],
        packLabel = r[StockItemCodesTable.packLabel], status = r[StockItemCodesTable.status], retiredReason = r[StockItemCodesTable.retiredReason],
        assignedByUserId = r[StockItemCodesTable.assignedByUserId].value, assignedAt = r[StockItemCodesTable.assignedAt],
    )
}
