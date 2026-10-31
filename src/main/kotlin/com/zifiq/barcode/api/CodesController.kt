package com.zifiq.barcode.api

import com.zifiq.barcode.dto.AssignCodeRequest
import com.zifiq.barcode.dto.GenerateCodeRequest
import com.zifiq.barcode.dto.ReplacePrimaryRequest
import com.zifiq.barcode.dto.ReplacePrimaryResult
import com.zifiq.barcode.dto.RetireCodeRequest
import com.zifiq.barcode.dto.StockItemCodeRow
import com.zifiq.barcode.service.CodeManagementService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/barcode/items/{stockItemId}/codes")
class CodesController(
    private val codes: CodeManagementService,
) : CodesApi {
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.lookup')")
    override fun listCodes(
        @PathVariable stockItemId: UUID,
        @RequestParam(required = false) status: String?,
    ): ApiEnvelope<List<StockItemCodeRow>> = ApiEnvelope(codes.list(stockItemId, status))

    @PostMapping("/generate")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.codes.manage')")
    override fun generateCode(
        @PathVariable stockItemId: UUID,
        @Valid @RequestBody request: GenerateCodeRequest,
    ): ApiEnvelope<StockItemCodeRow> = ApiEnvelope(codes.generate(stockItemId, request))

    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.codes.manage')")
    override fun assignCode(
        @PathVariable stockItemId: UUID,
        @Valid @RequestBody request: AssignCodeRequest,
    ): ApiEnvelope<StockItemCodeRow> = ApiEnvelope(codes.assign(stockItemId, request))

    @PostMapping("/{codeId}/replace-primary")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.codes.manage')")
    override fun replacePrimary(
        @PathVariable stockItemId: UUID,
        @PathVariable codeId: UUID,
        @Valid @RequestBody request: ReplacePrimaryRequest,
    ): ApiEnvelope<ReplacePrimaryResult> = ApiEnvelope(codes.replacePrimary(stockItemId, codeId, request))

    @PostMapping("/{codeId}/retire")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'barcode.codes.manage')")
    override fun retireCode(
        @PathVariable stockItemId: UUID,
        @PathVariable codeId: UUID,
        @Valid @RequestBody request: RetireCodeRequest,
    ): ApiEnvelope<List<StockItemCodeRow>> = ApiEnvelope(codes.retire(stockItemId, codeId, request))
}
