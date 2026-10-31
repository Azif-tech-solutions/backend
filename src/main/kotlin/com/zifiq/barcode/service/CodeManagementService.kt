package com.zifiq.barcode.service

import com.zifiq.barcode.dto.AssignCodeRequest
import com.zifiq.barcode.dto.GenerateCodeRequest
import com.zifiq.barcode.dto.ReplacePrimaryRequest
import com.zifiq.barcode.dto.ReplacePrimaryResult
import com.zifiq.barcode.dto.RetireCodeRequest
import com.zifiq.barcode.dto.StockItemCodeRow
import com.zifiq.inventory.codes.StockItemCodeService
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * The barcode module's Codes API (docs/11 §4.1). A thin layer over the OPEN inventory
 * [StockItemCodeService] (which owns uniqueness, primary rules, the `stock_items.barcode` mirror and
 * the `barcode.code.*` events): this adds the module's transaction boundary and DTO mapping, while
 * the controller enforces `barcode.codes.manage` / `barcode.lookup`.
 */
@Service
class CodeManagementService(
    private val tx: TenantScopedTransaction,
    private val codes: StockItemCodeService,
) {
    fun list(stockItemId: UUID, status: String?): List<StockItemCodeRow> = tx.execute {
        codes.list(stockItemId, status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }).map(::toRow)
    }

    fun generate(stockItemId: UUID, request: GenerateCodeRequest): StockItemCodeRow = tx.execute {
        toRow(codes.generatePrimary(stockItemId, request.symbology))
    }

    fun assign(stockItemId: UUID, request: AssignCodeRequest): StockItemCodeRow = tx.execute {
        toRow(
            codes.assign(
                stockItemId = stockItemId, code = request.code.trim(), symbology = request.symbology, source = request.source,
                isPrimary = request.isPrimary, packQuantity = request.packQuantity ?: BigDecimal.ONE, packLabel = request.packLabel?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    fun replacePrimary(stockItemId: UUID, codeId: UUID, request: ReplacePrimaryRequest): ReplacePrimaryResult = tx.execute {
        ReplacePrimaryResult(toRow(codes.replacePrimary(stockItemId, codeId)), reprintSuggested = true)
    }

    fun retire(stockItemId: UUID, codeId: UUID, request: RetireCodeRequest): List<StockItemCodeRow> = tx.execute {
        codes.retire(stockItemId, codeId, request.reason.trim(), request.newPrimaryCodeId)
        codes.list(stockItemId).map(::toRow)
    }

    private fun toRow(c: StockItemCodeService.CodeView) = StockItemCodeRow(
        id = c.id, code = c.code, symbology = c.symbology, source = c.source, isPrimary = c.isPrimary,
        packQuantity = c.packQuantity, packLabel = c.packLabel, status = c.status,
        assignedBy = UserRef.of(c.assignedByUserId), assignedAt = c.assignedAt,
    )
}
