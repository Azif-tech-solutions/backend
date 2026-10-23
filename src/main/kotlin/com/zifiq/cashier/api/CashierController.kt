package com.zifiq.cashier.api

import com.zifiq.cashier.dto.SaleCompleteRequest
import com.zifiq.cashier.dto.SaleCustomerRequest
import com.zifiq.cashier.dto.SaleInvoiceRequest
import com.zifiq.cashier.dto.SaleLinesRequest
import com.zifiq.cashier.dto.SaleStartRequest
import com.zifiq.cashier.dto.SaleVersionRequest
import com.zifiq.cashier.dto.SaleVoidRequest
import com.zifiq.cashier.dto.TillCloseRequest
import com.zifiq.cashier.dto.TillOpenRequest
import com.zifiq.cashier.service.CatalogueService
import com.zifiq.cashier.service.SaleCheckout
import com.zifiq.cashier.service.SaleDocuments
import com.zifiq.cashier.service.SaleFilter
import com.zifiq.cashier.service.SaleService
import com.zifiq.cashier.service.SaleVoids
import com.zifiq.cashier.service.TillSessionFilter
import com.zifiq.cashier.service.TillSessionService
import com.zifiq.common.paging.PageParams
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.springdoc.core.annotations.ParameterObject
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val READ = "@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')"
private const val MANAGE = "@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage')"
private const val CLOSE = "@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage') or " +
    "@permissionAuthorizer.hasPermission(authentication, 'cashier.till.supervise')"
private const val VOID = "@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.void')"
private const val INVOICE = "@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.manage') or " +
    "@permissionAuthorizer.hasPermission(authentication, 'invoicing.invoice.issue')"

@RestController
@RequestMapping("/api/v1/cashier/till-sessions")
class TillSessionsController(private val tills: TillSessionService) : TillSessionsApi {
    @PostMapping
    @PreAuthorize(MANAGE)
    override fun open(@Valid @RequestBody request: TillOpenRequest) = ApiEnvelope(tills.open(request))

    @GetMapping("/current")
    @PreAuthorize(MANAGE)
    override fun current() = ApiEnvelope(tills.current())

    @GetMapping
    @PreAuthorize(READ)
    override fun list(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) cashierUserId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) withVarianceOnly: Boolean?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(tills.list(TillSessionFilter(locationId, cashierUserId, status, from, to, withVarianceOnly ?: false), paging.toRequest()))

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    override fun get(@PathVariable id: UUID) = ApiEnvelope(tills.get(id))

    @PostMapping("/{id}/close")
    @PreAuthorize(CLOSE)
    override fun close(@PathVariable id: UUID, @Valid @RequestBody request: TillCloseRequest) = ApiEnvelope(tills.close(id, request))
}

@RestController
@RequestMapping("/api/v1/cashier")
class SalesController(
    private val catalogue: CatalogueService,
    private val sales: SaleService,
    private val checkout: SaleCheckout,
    private val voids: SaleVoids,
    private val documents: SaleDocuments,
) : SalesApi {
    @GetMapping("/catalogue")
    @PreAuthorize(MANAGE)
    override fun catalogue(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(required = false) includeOutOfStock: Boolean?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(catalogue.catalogue(locationId, q, categoryId, customerId, includeOutOfStock ?: false, paging.toRequest()))

    @PostMapping("/sales")
    @PreAuthorize(MANAGE)
    override fun start(@Valid @RequestBody request: SaleStartRequest) = ApiEnvelope(sales.start(request))

    @PutMapping("/sales/{id}/customer")
    @PreAuthorize(MANAGE)
    override fun setCustomer(@PathVariable id: UUID, @Valid @RequestBody request: SaleCustomerRequest) = ApiEnvelope(sales.setCustomer(id, request))

    @PutMapping("/sales/{id}/lines")
    @PreAuthorize(MANAGE)
    override fun setLines(@PathVariable id: UUID, @Valid @RequestBody request: SaleLinesRequest) = ApiEnvelope(sales.setLines(id, request))

    @PostMapping("/sales/{id}/abandon")
    @PreAuthorize(MANAGE)
    override fun abandon(@PathVariable id: UUID, @Valid @RequestBody request: SaleVersionRequest) = ApiEnvelope(sales.abandon(id, request))

    @PostMapping("/sales/{id}/complete")
    @PreAuthorize(MANAGE)
    override fun complete(@PathVariable id: UUID, @Valid @RequestBody request: SaleCompleteRequest) = ApiEnvelope(checkout.complete(id, request))

    @GetMapping("/sales/{id}")
    @PreAuthorize(READ)
    override fun get(@PathVariable id: UUID) = ApiEnvelope(sales.get(id))

    @GetMapping("/sales")
    @PreAuthorize(READ)
    override fun list(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) cashierUserId: UUID?,
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(required = false) channel: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) ref: String?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(sales.list(SaleFilter(locationId, cashierUserId, customerId, channel, status, from, to, ref), paging.toRequest()))

    @PostMapping("/sales/{id}/void")
    @PreAuthorize(VOID)
    override fun void(@PathVariable id: UUID, @Valid @RequestBody request: SaleVoidRequest) = ApiEnvelope(voids.void(id, request))

    @PostMapping("/sales/{id}/invoice")
    @PreAuthorize(INVOICE)
    override fun invoice(@PathVariable id: UUID, @Valid @RequestBody request: SaleInvoiceRequest) = ApiEnvelope(documents.invoice(id, request))

    @PostMapping("/sales/{id}/load-pro-forma")
    @PreAuthorize(MANAGE)
    override fun loadProForma(@PathVariable id: UUID, @Valid @RequestBody request: com.zifiq.cashier.dto.LoadProFormaRequest) = ApiEnvelope(sales.loadProForma(id, request))

    @GetMapping("/sales/{id}/receipt.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize(READ)
    override fun receipt(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val document = documents.receipt(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(document.fileName).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(document.pdf)
    }
}

private const val WAYBILL = "@permissionAuthorizer.hasPermission(authentication, 'cashier.waybill.manage')"
private const val SUPPLY_READ = "@permissionAuthorizer.hasPermission(authentication, 'cashier.supply.read')"
private const val WAYBILL_OR_SUPPLY = "@permissionAuthorizer.hasPermission(authentication, 'cashier.waybill.manage') or " +
    "@permissionAuthorizer.hasPermission(authentication, 'cashier.supply.read')"

@RestController
@RequestMapping("/api/v1/cashier")
class WaybillsController(
    private val waybills: com.zifiq.cashier.service.CustomerWaybillService,
    private val pending: com.zifiq.cashier.service.PendingSupplyService,
) : WaybillsApi {
    @GetMapping("/supply/lookup")
    @PreAuthorize(WAYBILL_OR_SUPPLY)
    override fun supplyLookup(@RequestParam ref: String) = ApiEnvelope(waybills.lookup(ref))

    @PostMapping("/waybills")
    @PreAuthorize(WAYBILL)
    override fun createWaybill(@Valid @RequestBody request: com.zifiq.cashier.dto.WaybillRequest) = ApiEnvelope(waybills.create(request))

    @GetMapping("/waybills/{id}")
    @PreAuthorize(SUPPLY_READ)
    override fun getWaybill(@PathVariable id: UUID) = ApiEnvelope(waybills.get(id))

    @GetMapping("/waybills/{id}/waybill.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize(SUPPLY_READ)
    override fun waybillPdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val document = waybills.pdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(document.fileName).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(document.pdf)
    }

    @PostMapping("/waybills/{id}/acknowledge")
    @PreAuthorize(WAYBILL)
    override fun acknowledgeWaybill(@PathVariable id: UUID, @Valid @RequestBody request: com.zifiq.cashier.dto.WaybillAcknowledgeRequest) = ApiEnvelope(waybills.acknowledge(id, request))

    @PostMapping("/waybills/{id}/cancel")
    @PreAuthorize(WAYBILL)
    override fun cancelWaybill(@PathVariable id: UUID, @Valid @RequestBody request: com.zifiq.cashier.dto.WaybillCancelRequest) = ApiEnvelope(waybills.cancel(id, request))

    @GetMapping("/waybills")
    @PreAuthorize(SUPPLY_READ)
    override fun listWaybills(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) saleId: UUID?,
        @RequestParam(required = false) driverId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) ref: String?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(waybills.list(com.zifiq.cashier.service.WaybillFilter(locationId, saleId, driverId, status, ref), paging.toRequest()))

    @GetMapping("/reports/pending-supply")
    @PreAuthorize(SUPPLY_READ)
    override fun pendingSupply(
        @RequestParam(required = false) invoice: String?,
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(pending.report(com.zifiq.cashier.service.PendingSupplyFilter(invoice, customerId, status, locationId, from, to), paging.toRequest()))
}
