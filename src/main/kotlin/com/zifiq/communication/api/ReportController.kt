package com.zifiq.communication.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.communication.dto.CommsReportRow
import com.zifiq.communication.service.ReportService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Communication — Reports", description = "Commission reports (docs/12 §4.4)")
@SecurityRequirement(name = "bearerAuth")
interface ReportApi {
    @Operation(summary = "Comms. Report: unpaid commission by beneficiary and period")
    fun commissions(beneficiaryType: String, from: LocalDate?, to: LocalDate?, status: String?, beneficiaryId: UUID?, page: Int?, size: Int?): ApiEnvelope<PageResult<CommsReportRow>>
}

@RestController
@RequestMapping("/api/v1/communication/reports")
class ReportController(
    private val reports: ReportService,
) : ReportApi {
    @GetMapping("/commissions")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.reports.read')")
    override fun commissions(
        @RequestParam(required = false, defaultValue = "CORRESPONDENT") beneficiaryType: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) beneficiaryId: UUID?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<CommsReportRow>> = ApiEnvelope(reports.commissions(beneficiaryType, from, to, status, beneficiaryId, PageRequest.of(page, size)))
}
