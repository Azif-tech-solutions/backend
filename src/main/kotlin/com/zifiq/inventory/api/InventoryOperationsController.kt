package com.zifiq.inventory.api

import org.springdoc.core.annotations.ParameterObject
import com.zifiq.common.paging.PageParams
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.inventory.dto.*
import com.zifiq.inventory.service.InventoryOperationsService
import com.zifiq.inventory.service.WaybillService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryOperationsController(
    private val inventoryOperationsService: InventoryOperationsService,
    private val waybillService: WaybillService,
) : InventoryOperationsApi {
    @PostMapping("/stock/transfers")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.transfers.manage')")
    override fun dispatchTransfer(@Valid @RequestBody request: StockTransferRequest): ApiEnvelope<StockTransferSummary> =
        ApiEnvelope(inventoryOperationsService.dispatchTransfer(request))

    @PostMapping("/stock/transfers/{id}/receive")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.transfers.manage')")
    override fun receiveTransfer(
        @PathVariable id: UUID,
        @Valid @RequestBody request: StockTransferReceiveRequest,
    ): ApiEnvelope<StockTransferSummary> =
        ApiEnvelope(inventoryOperationsService.receiveTransfer(id, request))

    @GetMapping("/stock/transfers")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read') or @permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')")
    override fun listTransfers(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @ParameterObject paging: PageParams,
    ): ApiEnvelope<PageResult<StockTransferSummary>> =
        ApiEnvelope(
            inventoryOperationsService.listTransfers(
                locationId,
                status.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                direction, q, from, to, paging.toRequest(),
            ),
        )

    @GetMapping("/stock/transfers/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read') or @permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')")
    override fun getTransfer(@PathVariable id: UUID): ApiEnvelope<StockTransferSummary> = ApiEnvelope(inventoryOperationsService.getTransfer(id))

    @GetMapping("/stock/transfers/{id}/waybill.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read') or @permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')")
    override fun waybillPdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val waybill = waybillService.render(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("${waybill.ref}.pdf").build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(waybill.pdf)
    }

    @PostMapping("/quantity-adjustments")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.adjustments.manage')")
    override fun postAdjustment(@Valid @RequestBody request: QuantityAdjustmentRequest): ApiEnvelope<QuantityAdjustmentSummary> =
        ApiEnvelope(inventoryOperationsService.postAdjustment(request))

    @GetMapping("/quantity-adjustments")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read') or @permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')")
    override fun listAdjustments(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<QuantityAdjustmentSummary>> =
        ApiEnvelope(inventoryOperationsService.listAdjustments(ListSearchRequest.fromQuery(query)))

    @PostMapping("/quantity-adjustments/search")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read') or @permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')")
    override fun searchAdjustments(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<QuantityAdjustmentSummary>> =
        ApiEnvelope(inventoryOperationsService.listAdjustments(request))

    @GetMapping("/quantity-adjustments/filters")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read') or @permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')")
    override fun adjustmentFilters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(inventoryOperationsService.adjustmentFilterMetadata())
}
