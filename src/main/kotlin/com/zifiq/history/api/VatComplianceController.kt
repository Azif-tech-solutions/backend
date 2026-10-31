package com.zifiq.history.api

import com.zifiq.history.dto.VatReport
import com.zifiq.history.dto.VatReturn
import com.zifiq.history.service.VatComplianceService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val VAT_READ = "@permissionAuthorizer.hasPermission(authentication, 'compliance.vat.read')"

@Tag(name = "Compliance — VAT", description = "Showroom/Factory VAT report and the VAT return summary (docs/10 §4.6)")
@SecurityRequirement(name = "bearerAuth")
interface VatComplianceApi {
    @Operation(summary = "VAT report for one location: output vs input VAT, reconciling with the Sales Report")
    fun vatReport(locationId: UUID, from: LocalDate, to: LocalDate, type: String?): ApiEnvelope<VatReport>

    @Operation(summary = "VAT return summary for a period (YYYY-MM): one row per location plus a company total")
    fun vatReturn(period: String): ApiEnvelope<VatReturn>

    @Operation(summary = "The VAT return as CSV")
    fun vatReturnCsv(period: String): ResponseEntity<ByteArray>
}

@RestController
@RequestMapping("/api/v1/compliance/vat")
class VatComplianceController(private val vat: VatComplianceService) : VatComplianceApi {
    @GetMapping("/report")
    @PreAuthorize(VAT_READ)
    override fun vatReport(
        @RequestParam locationId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) type: String?,
    ) = ApiEnvelope(vat.report(locationId, from, to, type))

    @GetMapping("/return")
    @PreAuthorize(VAT_READ)
    override fun vatReturn(@RequestParam period: String) = ApiEnvelope(vat.vatReturn(period))

    @GetMapping("/return.csv", produces = ["text/csv"])
    @PreAuthorize(VAT_READ)
    override fun vatReturnCsv(@RequestParam period: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("vat-return-$period.csv").build().toString())
            .body(vat.returnCsv(period))
}
