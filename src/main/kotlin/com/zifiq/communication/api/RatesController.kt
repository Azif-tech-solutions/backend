package com.zifiq.communication.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.communication.dto.BulkRateRequest
import com.zifiq.communication.dto.BulkRateResult
import com.zifiq.communication.dto.CommissionRateHistoryRow
import com.zifiq.communication.dto.CommissionRateRow
import com.zifiq.communication.dto.SetRateRequest
import com.zifiq.communication.dto.UpdateRateRequest
import com.zifiq.communication.service.RateService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Communication — Comms. Guide", description = "Commission rates per product and beneficiary type (docs/12 §4.1)")
@SecurityRequirement(name = "bearerAuth")
interface RatesApi {
    @Operation(summary = "The Comms. Guide: the rate in force today per product")
    fun rates(beneficiaryType: String, categoryId: UUID?, rateType: String?, status: String?, q: String?, includeUnrated: Boolean, page: Int?, size: Int?): ApiEnvelope<PageResult<CommissionRateRow>>

    @Operation(summary = "One product's rate history")
    fun history(stockItemId: UUID, beneficiaryType: String): ApiEnvelope<List<CommissionRateHistoryRow>>

    @Operation(summary = "Set a rate (effective today or later)")
    fun setRate(request: SetRateRequest): ApiEnvelope<CommissionRateHistoryRow>

    @Operation(summary = "Set many rates at once")
    fun bulk(request: BulkRateRequest): ApiEnvelope<BulkRateResult>

    @Operation(summary = "Edit a future-dated rate")
    fun update(id: UUID, request: UpdateRateRequest): ApiEnvelope<CommissionRateHistoryRow>

    @Operation(summary = "Withdraw a future-dated rate")
    fun withdraw(id: UUID): ApiEnvelope<Unit>
}

@RestController
@RequestMapping("/api/v1/communication/rates")
class RatesController(
    private val rates: RateService,
) : RatesApi {
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.guide.read')")
    override fun rates(
        @RequestParam(required = false, defaultValue = "DEALER") beneficiaryType: String,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) rateType: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "true") includeUnrated: Boolean,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<CommissionRateRow>> =
        ApiEnvelope(rates.list(beneficiaryType, categoryId, rateType, status, q, includeUnrated, PageRequest.of(page, size)))

    @GetMapping("/{stockItemId}/history")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.guide.read')")
    override fun history(
        @PathVariable stockItemId: UUID,
        @RequestParam(required = false, defaultValue = "DEALER") beneficiaryType: String,
    ): ApiEnvelope<List<CommissionRateHistoryRow>> = ApiEnvelope(rates.history(stockItemId, beneficiaryType))

    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.guide.manage')")
    override fun setRate(@Valid @RequestBody request: SetRateRequest): ApiEnvelope<CommissionRateHistoryRow> =
        ApiEnvelope(rates.setRate(request))

    @PostMapping("/bulk")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.guide.manage')")
    override fun bulk(@Valid @RequestBody request: BulkRateRequest): ApiEnvelope<BulkRateResult> =
        ApiEnvelope(rates.bulk(request))

    @PutMapping("/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.guide.manage')")
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateRateRequest): ApiEnvelope<CommissionRateHistoryRow> =
        ApiEnvelope(rates.update(id, request))

    @DeleteMapping("/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.guide.manage')")
    override fun withdraw(@PathVariable id: UUID): ApiEnvelope<Unit> {
        rates.withdraw(id)
        return ApiEnvelope(Unit)
    }
}
