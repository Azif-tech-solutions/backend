package com.zifiq.inventory.api

import com.zifiq.inventory.dto.StockItemClassification
import com.zifiq.inventory.dto.StockItemClassificationRequest
import com.zifiq.inventory.service.StockItemClassificationService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

private const val CLASSIFY =
    "@permissionAuthorizer.hasPermission(authentication, 'accounting.vat.manage') or @permissionAuthorizer.hasPermission(authentication, 'inventory.stock.manage')"

@Tag(name = "Inventory — NRS classification", description = "Granular HS / ISIC / unit codes on a stock item for e-invoice lines (docs/08 §2.1)")
@SecurityRequirement(name = "bearerAuth")
interface StockItemClassificationApi {
    @Operation(summary = "An item's NRS classification: section codes plus the optional granular HS/ISIC code and NRS unit")
    fun get(id: UUID): ApiEnvelope<StockItemClassification>

    @Operation(summary = "Set the HS code (goods) or ISIC class (services), NRS unit and service flag; omitted = unchanged, \"\" = cleared")
    fun update(id: UUID, request: StockItemClassificationRequest): ApiEnvelope<StockItemClassification>
}

@RestController
@RequestMapping("/api/v1/inventory/stock-items/{id}/classification")
class StockItemClassificationController(private val classification: StockItemClassificationService) : StockItemClassificationApi {
    @GetMapping
    @PreAuthorize("$CLASSIFY or @permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun get(@PathVariable id: UUID) = ApiEnvelope(classification.get(id))

    @PatchMapping
    @PreAuthorize(CLASSIFY)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: StockItemClassificationRequest) =
        ApiEnvelope(classification.update(id, request))
}
