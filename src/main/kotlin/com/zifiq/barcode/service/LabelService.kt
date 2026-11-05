package com.zifiq.barcode.service

import com.zifiq.barcode.dto.LabelItemRow
import com.zifiq.barcode.dto.LabelPreviewRequest
import com.zifiq.barcode.dto.LabelRunRequest
import com.zifiq.barcode.dto.LabelRunResult
import com.zifiq.barcode.dto.LabelRunRow
import com.zifiq.barcode.model.LabelPrintRunItemsTable
import com.zifiq.barcode.model.LabelPrintRunsTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.money.Money
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.inventory.codes.StockItemCodeService
import com.zifiq.inventory.model.StockItemCodesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.repository.StockSql
import com.zifiq.platform.directory.Names
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Label print runs (docs/11 §4.5): pick items, generate any missing codes, store the run as a
 * snapshot of what was printed, and render it to a PDF (symbols drawn by ZXing through the shared
 * PdfRenderer) or Zebra ZPL. Costs never appear on a label — the price shown is the selling price.
 */
@Service
class LabelService(
    private val support: BarcodeSupport,
    private val tx: TenantScopedTransaction,
    private val codes: StockItemCodeService,
    private val sequences: DocumentSequenceService,
    private val symbols: BarcodeSymbols,
    private val labelImages: LabelImages,
    private val pdf: PdfRenderer,
) {
    fun items(hasCode: String?, kind: String?, status: String?, q: String?, paging: PageRequest): PageResult<LabelItemRow> = tx.execute {
        val unit = StockSql.unitName()
        val query = StockItemsTable.select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, StockItemsTable.itemType, StockItemsTable.barcode, StockItemsTable.barcodeSymbology, unit)
            .andWhere { StockItemsTable.tenantId eq support.tenantId() }
            .andWhere { StockItemsTable.status eq (status?.trim()?.uppercase() ?: "ACTIVE") }
        kind?.trim()?.uppercase()?.let { k -> query.andWhere { StockItemsTable.itemType eq k } }
        when (hasCode?.trim()?.lowercase()) {
            "true" -> query.andWhere { StockItemsTable.barcode.isNotNull() }
            "false" -> query.andWhere { StockItemsTable.barcode.isNull() }
            else -> {}
        }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { term ->
            val t = term.lowercase()
            query.andWhere { (StockItemsTable.code.lowerCase() like "$t%") or (StockItemsTable.name.lowerCase() like "%$t%") }
        }
        val page = Paging.fetch(query.orderBy(StockItemsTable.name to SortOrder.ASC, StockItemsTable.id to SortOrder.ASC), paging) { it }
        val packCounts = packCodeCounts(page.items.map { it[StockItemsTable.id].value })
        page.withItems(page.items.map { r ->
            val id = r[StockItemsTable.id].value
            LabelItemRow(
                stockItem = Ref.stockItem(id), code = r[StockItemsTable.code], name = r[StockItemsTable.name], kind = r[StockItemsTable.itemType],
                unit = r[unit] ?: "Unit", primaryCode = r[StockItemsTable.barcode], symbology = r[StockItemsTable.barcodeSymbology],
                packCodes = packCounts[id] ?: 0,
            )
        })
    }

    fun preview(request: LabelPreviewRequest): ByteArray = tx.execute {
        val item = requireItem(request.stockItemId!!)
        val primary = activePrimary(request.stockItemId)
        val code = primary?.get(StockItemCodesTable.code)
        val symbology = primary?.get(StockItemCodesTable.symbology)
        val priceText = if (request.showPrice) priceOf(request.stockItemId, request.priceBasis == "DEALER")?.let { priceText(it) } else null
        labelImages.preview(
            request.size, item[StockItemsTable.name], code, symbology,
            "${code ?: "generated on save"} · ${unitName(item)}", priceText,
            if (code != null && symbology != null) symbols.image(code, symbology) else null,
        )
    }

    fun createRun(request: LabelRunRequest): LabelRunResult = tx.execute {
        val totalCopies = request.items.sumOf { it.copies }
        if (totalCopies > 5000) throw ApiErrors.tooManyLabels()
        val dealer = request.priceBasis == "DEALER"
        var generated = 0
        val ref = sequences.next("LBL")
        val runId = LabelPrintRunsTable.insertAndGetId {
            it[tenantId] = support.tenantId(); it[LabelPrintRunsTable.ref] = ref; it[labelSize] = request.size
            it[outputFormat] = request.outputFormat; it[showPrice] = request.showPrice; it[priceBasis] = request.priceBasis
            it[locationId] = request.locationId; it[totalLabels] = totalCopies; it[codesGenerated] = 0
            it[createdByUserId] = support.userId(); it[createdAt] = Instant.now()
        }.value
        request.items.forEach { line ->
            val item = requireItem(line.stockItemId!!)
            var codeRow = line.codeId?.let { requireCode(line.stockItemId, it) } ?: activePrimary(line.stockItemId)
            if (codeRow == null) {
                if (!request.generateMissing) throw ApiErrors.itemHasNoCode()
                if (!support.can("barcode.codes.manage")) throw ApiErrors.accessDenied()
                val made = codes.generatePrimary(line.stockItemId)
                generated++
                codeRow = requireCode(line.stockItemId, made.id)
            }
            val price = if (request.showPrice) (priceOf(line.stockItemId, dealer) ?: throw ApiErrors.noPriceSet(item[StockItemsTable.code])) else null
            LabelPrintRunItemsTable.insert {
                it[tenantId] = support.tenantId(); it[LabelPrintRunItemsTable.runId] = runId; it[stockItemId] = line.stockItemId
                it[codeId] = codeRow!![StockItemCodesTable.id].value; it[copies] = line.copies
                it[itemName] = item[StockItemsTable.name]; it[unitName] = unitName(item); it[LabelPrintRunItemsTable.price] = price
            }
        }
        if (generated > 0) LabelPrintRunsTable.update({ LabelPrintRunsTable.id eq runId }) { it[codesGenerated] = generated }
        LabelRunResult(ref, totalCopies, generated, "/api/v1/barcode/labels/runs/$runId/labels.${request.outputFormat.lowercase()}")
    }

    fun renderPdf(runId: UUID): ByteArray = tx.execute {
        val (run, lines) = runWithLines(runId)
        val labels = lines.flatMap { line ->
            val code = line[LabelPrintRunItemsTable.codeId]
            val codeRow = StockItemCodesTable.select(StockItemCodesTable.code, StockItemCodesTable.symbology).where { StockItemCodesTable.id eq code }.single()
            val model = mapOf(
                "name" to line[LabelPrintRunItemsTable.itemName],
                "sub" to "${codeRow[StockItemCodesTable.code]} · ${line[LabelPrintRunItemsTable.unitName]}",
                "symbol" to symbols.dataUri(codeRow[StockItemCodesTable.code], codeRow[StockItemCodesTable.symbology]),
                "price" to line[LabelPrintRunItemsTable.price]?.let { priceText(it) },
            )
            List(line[LabelPrintRunItemsTable.copies]) { model }
        }
        pdf.render("labels", mapOf("pageCss" to LabelLayout.css(run[LabelPrintRunsTable.labelSize]), "labels" to labels))
    }

    fun renderZpl(runId: UUID): String = tx.execute {
        val (run, lines) = runWithLines(runId)
        val sb = StringBuilder()
        lines.forEach { line ->
            val codeRow = StockItemCodesTable.select(StockItemCodesTable.code, StockItemCodesTable.symbology)
                .where { StockItemCodesTable.id eq line[LabelPrintRunItemsTable.codeId] }.single()
            repeat(line[LabelPrintRunItemsTable.copies]) {
                sb.append(
                    LabelLayout.zpl(
                        run[LabelPrintRunsTable.labelSize], line[LabelPrintRunItemsTable.itemName],
                        codeRow[StockItemCodesTable.code], codeRow[StockItemCodesTable.symbology],
                        line[LabelPrintRunItemsTable.unitName], line[LabelPrintRunItemsTable.price]?.let { priceText(it) },
                    ),
                )
            }
        }
        sb.toString()
    }

    fun history(paging: PageRequest): PageResult<LabelRunRow> = tx.execute {
        val lineCount = LabelPrintRunItemsTable.id.count()
        val page = Paging.fetch(
            LabelPrintRunsTable.selectAll().where { LabelPrintRunsTable.tenantId eq support.tenantId() }
                .orderBy(LabelPrintRunsTable.createdAt to SortOrder.DESC, LabelPrintRunsTable.id to SortOrder.DESC),
            paging,
        ) { it }
        val names = Names.users(page.items.map { it[LabelPrintRunsTable.createdByUserId].value })
        val counts = LabelPrintRunItemsTable.select(LabelPrintRunItemsTable.runId, lineCount)
            .where { LabelPrintRunItemsTable.runId inList page.items.map { it[LabelPrintRunsTable.id].value } }
            .groupBy(LabelPrintRunItemsTable.runId).associate { it[LabelPrintRunItemsTable.runId] to it[lineCount] }
        page.withItems(page.items.map { r ->
            val id = r[LabelPrintRunsTable.id].value
            LabelRunRow(
                ref = r[LabelPrintRunsTable.ref], createdAt = r[LabelPrintRunsTable.createdAt], createdBy = UserRef.of(r[LabelPrintRunsTable.createdByUserId].value),
                size = r[LabelPrintRunsTable.labelSize], outputFormat = r[LabelPrintRunsTable.outputFormat],
                items = (counts[id] ?: 0L).toInt(), totalLabels = r[LabelPrintRunsTable.totalLabels], codesGenerated = r[LabelPrintRunsTable.codesGenerated],
                downloadUrl = "/api/v1/barcode/labels/runs/$id/labels.${r[LabelPrintRunsTable.outputFormat].lowercase()}",
            )
        })
    }

    // ---- helpers ----

    private fun runWithLines(runId: UUID): Pair<ResultRow, List<ResultRow>> {
        val run = LabelPrintRunsTable.selectAll().where { (LabelPrintRunsTable.id eq runId) and (LabelPrintRunsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.validationFailed(mapOf("id" to "print run not found"))
        val lines = LabelPrintRunItemsTable.selectAll().where { LabelPrintRunItemsTable.runId eq runId }.toList()
        return run to lines
    }

    private fun packCodeCounts(ids: List<UUID>): Map<UUID, Int> {
        if (ids.isEmpty()) return emptyMap()
        val c = StockItemCodesTable.id.count()
        return StockItemCodesTable.select(StockItemCodesTable.stockItemId, c)
            .where { (StockItemCodesTable.stockItemId inList ids) and (StockItemCodesTable.status eq "ACTIVE") and (StockItemCodesTable.packQuantity greater BigDecimal.ONE) }
            .groupBy(StockItemCodesTable.stockItemId).associate { it[StockItemCodesTable.stockItemId].value to it[c].toInt() }
    }

    private fun activePrimary(stockItemId: UUID): ResultRow? =
        StockItemCodesTable.selectAll()
            .where { (StockItemCodesTable.stockItemId eq stockItemId) and (StockItemCodesTable.isPrimary eq true) and (StockItemCodesTable.status eq "ACTIVE") }
            .firstOrNull()

    private fun requireItem(stockItemId: UUID): ResultRow =
        StockItemsTable.selectAll().where { (StockItemsTable.id eq stockItemId) and (StockItemsTable.tenantId eq support.tenantId()) }.firstOrNull()
            ?: throw ApiErrors.stockItemNotFound()

    private fun requireCode(stockItemId: UUID, codeId: UUID): ResultRow =
        StockItemCodesTable.selectAll().where { (StockItemCodesTable.id eq codeId) and (StockItemCodesTable.stockItemId eq stockItemId) }.firstOrNull()
            ?: throw ApiErrors.codeNotFound()

    private fun unitName(item: ResultRow): String {
        val u = StockSql.unitName()
        return StockItemsTable.select(u).where { StockItemsTable.id eq item[StockItemsTable.id].value }.firstOrNull()?.get(u) ?: "Unit"
    }

    private fun priceOf(stockItemId: UUID, dealer: Boolean): BigDecimal? {
        val retail = StockSql.retailPriceAt(support.today())
        val dealerPrice = StockSql.dealerPriceAt(support.today())
        val row = StockItemsTable.select(retail, dealerPrice).where { StockItemsTable.id eq stockItemId }.firstOrNull() ?: return null
        val r = row[retail] ?: return null
        return Money.round(if (dealer) (row[dealerPrice] ?: r) else r)
    }

    private fun priceText(price: BigDecimal): String = "₦${Money.round(price).toPlainString()}"
}
