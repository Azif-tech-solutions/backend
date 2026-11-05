package com.zifiq.barcode.service

import com.zifiq.barcode.dto.BatchScanRequest
import com.zifiq.barcode.dto.ScanPrice
import com.zifiq.barcode.dto.ScanRequest
import com.zifiq.barcode.dto.ScanResolution
import com.zifiq.barcode.dto.ScanStock
import com.zifiq.barcode.dto.ScannedItem
import com.zifiq.barcode.model.ScanEventsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.inventory.codes.CheckDigits
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.inventory.model.StockItemCodesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.StockSql
import com.zifiq.inventory.service.StockAvailability
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/**
 * Resolves a scanned code to a stock item for the screen that scanned it (docs/11 §4.2). The lookup
 * is an exact, index-backed equality on `code_normalized` (with the UPC-A↔EAN-13 variants) — never
 * fuzzy, because a fuzzy barcode match would pick the wrong product. Every attempt, whatever its
 * outcome, is written to the scan log. No cost is ever returned.
 */
@Service
class ScanResolutionService(
    private val support: BarcodeSupport,
    private val tx: TenantScopedTransaction,
    private val availability: StockAvailability,
    private val customers: CustomerDirectory,
    private val hierarchy: LocationHierarchy,
    private val imageDecoder: BarcodeImageDecoder,
) {
    fun resolve(req: ScanRequest): ScanResolution = tx.execute {
        val ctx = parseContext(req.context)
        checkAccess(ctx, req.locationId)
        resolveOne(ctx, req.code, req.locationId, req.customerId, req.inputMethod, req.clientRef)
    }

    fun batch(req: BatchScanRequest): List<ScanResolution> = tx.execute {
        val ctx = parseContext(req.context)
        checkAccess(ctx, req.locationId)
        req.codes.map { resolveOne(ctx, it, req.locationId, req.customerId, "SCANNER", null) }
    }

    /** Decode the barcode(s) in [image] (docs/11 §4.3), then resolve each exactly as a typed scan. */
    fun resolveImage(context: String, image: ByteArray, locationId: UUID?, customerId: UUID?): List<ScanResolution> {
        val codes = imageDecoder.decode(image)
        return tx.execute {
            val ctx = parseContext(context)
            checkAccess(ctx, locationId)
            codes.map { resolveOne(ctx, it, locationId, customerId, "IMAGE", null) }
        }
    }

    private fun parseContext(raw: String): ScanContext =
        runCatching { ScanContext.valueOf(raw.trim().uppercase()) }.getOrElse { throw ApiErrors.validationFailed(mapOf("context" to "unknown scan context")) }

    private fun checkAccess(ctx: ScanContext, locationId: UUID?) {
        if (!support.can(ctx.permission)) throw ApiErrors.accessDenied()
        if (ctx.locationBound) {
            val loc = locationId ?: throw ApiErrors.validationFailed(mapOf("locationId" to "this scan context requires a location"))
            support.assertAccess(loc)
        } else if (locationId != null) {
            support.assertAccess(locationId)
        }
    }

    private fun resolveOne(ctx: ScanContext, rawCode: String, locationId: UUID?, customerId: UUID?, inputMethod: String, clientRef: String?): ScanResolution {
        val cleaned = rawCode.filterNot { it.isWhitespace() || it.isISOControl() }
        val received = rawCode.take(200)
        if (cleaned.length > 200) return record(received, ctx, locationId, inputMethod, clientRef, miss("MISREAD", cleaned, "Code too long — rescan"))
        if (cleaned.length in setOf(8, 12, 13) && cleaned.all { it.isDigit() } && !CheckDigits.isValidGtin(cleaned)) {
            return record(received, ctx, locationId, inputMethod, clientRef, miss("MISREAD", cleaned, "Check digit invalid — rescan"))
        }
        val normalized = cleaned.uppercase()
        val row = StockItemCodesTable
            .join(StockItemsTable, JoinType.INNER, StockItemCodesTable.stockItemId, StockItemsTable.id)
            .select(
                StockItemCodesTable.id, StockItemCodesTable.status, StockItemCodesTable.symbology, StockItemCodesTable.packQuantity, StockItemCodesTable.packLabel,
                StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, StockItemsTable.itemType, StockItemsTable.status, StockItemsTable.locationId,
                StockSql.unitName(), StockSql.categoryName(), StockSql.retailPriceAt(support.today()), StockSql.dealerPriceAt(support.today()),
            )
            .where { (StockItemCodesTable.tenantId eq support.tenantId()) and (StockItemCodesTable.codeNormalized inList codeVariants(normalized)) }
            .firstOrNull()
            ?: return record(
                received, ctx, locationId, inputMethod, clientRef,
                ScanResolution(
                    outcome = "NOT_RECOGNISED", blockReason = null, code = cleaned, item = null, packQuantity = null, packLabel = null,
                    price = null, stock = null, canRegister = support.can("inventory.stock.manage"), canRegisterInline = ctx == ScanContext.DIRECT_ENTRY,
                    message = "Code not recognised — register this item or enter it manually",
                ),
            )
        return record(received, ctx, locationId, inputMethod, clientRef, evaluate(ctx, cleaned, row, locationId, customerId))
    }

    private fun evaluate(ctx: ScanContext, code: String, row: ResultRow, locationId: UUID?, customerId: UUID?): ScanResolution {
        val itemId = row[StockItemsTable.id].value
        val kind = row[StockItemsTable.itemType]
        val itemStatus = row[StockItemsTable.status]
        val itemLoc = row[StockItemsTable.locationId]
        val retail = row[StockSql.retailPriceAt(support.today())]

        val block: String? = when {
            row[StockItemCodesTable.status] == "RETIRED" -> "CODE_RETIRED"
            ctx.activeOnly && itemStatus != "ACTIVE" -> if (itemStatus in setOf("DELETED", "PURGED")) itemStatus else "SUSPENDED"
            ctx.allowedKinds.isNotEmpty() && kind !in ctx.allowedKinds -> ctx.wrongKindReason()
            kind == "DIRECT_ENTRY" && locationId != null && itemLoc != locationId && ctx in setOf(ScanContext.DIRECT_ENTRY, ScanContext.STOCK_TRANSFER) -> "NOT_AT_LOCATION"
            ctx.requiresPrice && retail == null -> "NO_PRICE"
            else -> null
        }

        val item = ScannedItem(
            stockItem = Ref.stockItem(itemId), unit = row[StockSql.unitName()] ?: "Unit", status = itemStatus,
            symbology = row[StockItemCodesTable.symbology], category = row[StockSql.categoryName()],
        )
        if (block != null) {
            return ScanResolution(
                outcome = "BLOCKED", blockReason = block, code = code, item = item, packQuantity = null, packLabel = null,
                price = null, stock = null, message = blockMessage(block, row[StockItemsTable.name]),
            )
        }
        val dealer = customerId?.let { customers.get(it).type == CustomerType.AUTHORISED_DEALER } ?: false
        val price = if (ctx.showsPrice && retail != null) {
            val dealerPrice = row[StockSql.dealerPriceAt(support.today())]?.let(Money::round) ?: Money.round(retail)
            ScanPrice(basis = if (dealer) "DEALER" else "RETAIL", unitPrice = if (dealer) dealerPrice else Money.round(retail), retailPrice = Money.round(retail))
        } else null
        return ScanResolution(
            outcome = "MATCHED", blockReason = null, code = code, item = item,
            packQuantity = row[StockItemCodesTable.packQuantity], packLabel = row[StockItemCodesTable.packLabel],
            price = price, stock = locationId?.let { stockAt(it, itemId) },
            message = "${row[StockItemsTable.name]} — ready to use",
        )
    }

    private fun stockAt(locationId: UUID, itemId: UUID): ScanStock {
        val onHandExpr = StockBalancesTable.quantityOnHand.sum()
        val onHand = StockBalancesTable.select(onHandExpr)
            .where { (StockBalancesTable.tenantId eq support.tenantId()) and (StockBalancesTable.stockItemId eq itemId) and (StockBalancesTable.locationId inList hierarchy.withAreas(locationId)) }
            .firstOrNull()?.get(onHandExpr) ?: BigDecimal.ZERO
        val available = availability.available(locationId, listOf(itemId))[itemId] ?: BigDecimal.ZERO
        return ScanStock(location = Ref.location(locationId), onHand = onHand, available = available)
    }

    private fun miss(outcome: String, code: String, message: String) = ScanResolution(
        outcome = outcome, blockReason = null, code = code, item = null, packQuantity = null, packLabel = null, price = null, stock = null, message = message,
    )

    private fun blockMessage(reason: String, name: String): String = when (reason) {
        "CODE_RETIRED" -> "$name — this code was retired; use the current one"
        "SUSPENDED" -> "$name is suspended — review it in the Recycle Bin"
        "DELETED" -> "$name has been deleted"
        "PURGED" -> "$name has been purged"
        "NOT_SELLABLE" -> "$name is not sellable here"
        "WRONG_KIND" -> "$name is the wrong kind of item for this screen"
        "NOT_AT_LOCATION" -> "$name belongs to another location"
        "NO_PRICE" -> "$name has no price set"
        else -> name
    }

    private fun codeVariants(normalized: String): List<String> = buildList {
        add(normalized)
        if (normalized.length == 12 && normalized.all { it.isDigit() }) add("0$normalized")       // UPC-A read as EAN-13
        if (normalized.length == 13 && normalized.startsWith("0")) add(normalized.substring(1))    // EAN-13 read as UPC-A
    }

    private fun record(received: String, ctx: ScanContext, locationId: UUID?, inputMethod: String, clientRef: String?, resolution: ScanResolution): ScanResolution {
        ScanEventsTable.insert {
            it[tenantId] = support.tenantId()
            it[scannedAt] = Instant.now()
            it[code] = received
            it[ScanEventsTable.inputMethod] = inputMethod
            it[context] = ctx.name
            it[outcome] = resolution.outcome
            it[blockReason] = resolution.blockReason
            it[stockItemId] = resolution.item?.stockItem?.id
            it[matchedCodeId] = null
            it[ScanEventsTable.locationId] = locationId
            it[userId] = support.userId()
            it[ScanEventsTable.clientRef] = clientRef
        }
        return resolution
    }
}
