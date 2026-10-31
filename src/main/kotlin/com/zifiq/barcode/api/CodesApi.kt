package com.zifiq.barcode.api

import com.zifiq.barcode.dto.AssignCodeRequest
import com.zifiq.barcode.dto.GenerateCodeRequest
import com.zifiq.barcode.dto.ReplacePrimaryRequest
import com.zifiq.barcode.dto.ReplacePrimaryResult
import com.zifiq.barcode.dto.RetireCodeRequest
import com.zifiq.barcode.dto.StockItemCodeRow
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Barcode & QR — Codes", description = "Assign, generate, replace and retire a product's barcode/QR codes")
@SecurityRequirement(name = "bearerAuth")
interface CodesApi {
    @Operation(summary = "List a stock item's codes")
    fun listCodes(stockItemId: UUID, status: String?): ApiEnvelope<List<StockItemCodeRow>>

    @Operation(summary = "Generate and assign a primary code")
    fun generateCode(stockItemId: UUID, request: GenerateCodeRequest): ApiEnvelope<StockItemCodeRow>

    @Operation(summary = "Assign an existing (manufacturer/manual) code")
    fun assignCode(stockItemId: UUID, request: AssignCodeRequest): ApiEnvelope<StockItemCodeRow>

    @Operation(summary = "Make a code the primary; the old primary stays as an alias")
    fun replacePrimary(stockItemId: UUID, codeId: UUID, request: ReplacePrimaryRequest): ApiEnvelope<ReplacePrimaryResult>

    @Operation(summary = "Retire a code (never reused); retiring the primary needs a replacement")
    fun retireCode(stockItemId: UUID, codeId: UUID, request: RetireCodeRequest): ApiEnvelope<List<StockItemCodeRow>>
}
