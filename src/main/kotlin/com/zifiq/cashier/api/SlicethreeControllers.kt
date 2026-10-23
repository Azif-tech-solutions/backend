package com.zifiq.cashier.api

import com.zifiq.cashier.dto.InstantDiscountRequest
import com.zifiq.cashier.dto.ProFormaConvertRequest
import com.zifiq.cashier.dto.ProFormaRequest
import com.zifiq.cashier.dto.RejectRequest
import com.zifiq.cashier.service.InstantDiscountFilter
import com.zifiq.cashier.service.ProFormaFilter
import com.zifiq.cashier.service.ProFormaService
import com.zifiq.cashier.service.SaleDiscounts
import com.zifiq.cashier.service.SalesReportService
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

private const val DISCOUNT_APPLY = "@permissionAuthorizer.hasPermission(authentication, 'cashier.discount.apply')"
private const val DISCOUNT_APPROVE = "@permissionAuthorizer.hasPermission(authentication, 'cashier.discount.approve')"
private const val DISCOUNT_READ = "@permissionAuthorizer.hasPermission(authentication, 'cashier.discount.apply') or " +
    "@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read-all')"
private const val PF = "@permissionAuthorizer.hasPermission(authentication, 'cashier.pro-forma.manage')"
private const val PF_READ = "@permissionAuthorizer.hasPermission(authentication, 'cashier.pro-forma.manage') or " +
    "@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read-all')"
private const val CONVERT = "@permissionAuthorizer.hasPermission(authentication, 'cashier.pro-forma.manage') and " +
    "@permissionAuthorizer.hasPermission(authentication, 'invoicing.invoice.issue')"
private const val REPORTS = "@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read-all')"

@RestController
@RequestMapping("/api/v1/cashier")
class InstantDiscountController(private val discounts: SaleDiscounts) : InstantDiscountApi {
    @PostMapping("/sales/{id}/instant-discount")
    @PreAuthorize(DISCOUNT_APPLY)
    override fun applyDiscount(@Valid @RequestBody request: InstantDiscountRequest) = ApiEnvelope(discounts.apply(request))

    @PostMapping("/instant-discounts/{id}/approve")
    @PreAuthorize(DISCOUNT_APPROVE)
    override fun approveDiscount(@PathVariable id: UUID) = ApiEnvelope(discounts.approve(id))

    @PostMapping("/instant-discounts/{id}/reject")
    @PreAuthorize(DISCOUNT_APPROVE)
    override fun rejectDiscount(@PathVariable id: UUID, @Valid @RequestBody request: RejectRequest) = ApiEnvelope(discounts.reject(id, request))

    @PostMapping("/instant-discounts/{id}/withdraw")
    @PreAuthorize(DISCOUNT_APPLY)
    override fun withdrawDiscount(@PathVariable id: UUID) = ApiEnvelope(discounts.withdraw(id))

    @GetMapping("/instant-discounts")
    @PreAuthorize(DISCOUNT_READ)
    override fun listDiscounts(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) reason: String?,
        @RequestParam(required = false) saleId: UUID?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(discounts.list(InstantDiscountFilter(status, reason, saleId), paging.toRequest()))
}

@RestController
@RequestMapping("/api/v1/cashier/pro-formas")
class ProFormaController(private val proFormas: ProFormaService) : ProFormaApi {
    @PostMapping
    @PreAuthorize(PF)
    override fun createProForma(@RequestParam(required = false) send: Boolean?, @Valid @RequestBody request: ProFormaRequest) =
        ApiEnvelope(proFormas.create(request, send ?: false))

    @PutMapping("/{id}")
    @PreAuthorize(PF)
    override fun updateProForma(@PathVariable id: UUID, @Valid @RequestBody request: ProFormaRequest) = ApiEnvelope(proFormas.update(id, request))

    @PostMapping("/{id}/send")
    @PreAuthorize(PF)
    override fun sendProForma(@PathVariable id: UUID) = ApiEnvelope(proFormas.send(id))

    @PostMapping("/{id}/decline")
    @PreAuthorize(PF)
    override fun declineProForma(@PathVariable id: UUID, @Valid @RequestBody request: RejectRequest) = ApiEnvelope(proFormas.decline(id, request))

    @PostMapping("/{id}/cancel")
    @PreAuthorize(PF)
    override fun cancelProForma(@PathVariable id: UUID, @Valid @RequestBody request: RejectRequest) = ApiEnvelope(proFormas.cancel(id, request))

    @PostMapping("/{id}/reissue")
    @PreAuthorize(PF)
    override fun reissueProForma(@PathVariable id: UUID) = ApiEnvelope(proFormas.reissue(id))

    @PostMapping("/{id}/approve-discount")
    @PreAuthorize(DISCOUNT_APPROVE)
    override fun approveProFormaDiscount(@PathVariable id: UUID) = ApiEnvelope(proFormas.approveDiscount(id))

    @PostMapping("/{id}/reject-discount")
    @PreAuthorize(DISCOUNT_APPROVE)
    override fun rejectProFormaDiscount(@PathVariable id: UUID, @Valid @RequestBody request: RejectRequest) = ApiEnvelope(proFormas.rejectDiscount(id, request))

    @PostMapping("/{id}/convert")
    @PreAuthorize(CONVERT)
    override fun convertProForma(@PathVariable id: UUID, @Valid @RequestBody request: ProFormaConvertRequest) = ApiEnvelope(proFormas.convert(id, request))

    @GetMapping("/{id}")
    @PreAuthorize(PF_READ)
    override fun getProForma(@PathVariable id: UUID) = ApiEnvelope(proFormas.get(id))

    @GetMapping
    @PreAuthorize(PF_READ)
    override fun listProFormas(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(required = false) ref: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(proFormas.list(ProFormaFilter(status, locationId, customerId, ref, from, to), paging.toRequest()))

    @GetMapping("/{id}/pro-forma.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize(PF_READ)
    override fun proFormaPdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val document = proFormas.pdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(document.fileName).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(document.pdf)
    }
}

@RestController
@RequestMapping("/api/v1/cashier/reports")
class SalesReportController(private val reports: SalesReportService) : SalesReportApi {
    @GetMapping("/sales-daily")
    @PreAuthorize(REPORTS)
    override fun salesDaily(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) cashierUserId: UUID?,
        @RequestParam(required = false) channel: String?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ) = ApiEnvelope(reports.salesDaily(locationId, cashierUserId, channel, from, to))

    @GetMapping("/customer-activity")
    @PreAuthorize(REPORTS)
    override fun customerActivity(
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ) = ApiEnvelope(reports.customerActivity(kind, customerId, locationId, from, to))
}
