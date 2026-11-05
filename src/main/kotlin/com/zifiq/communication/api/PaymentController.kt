package com.zifiq.communication.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.communication.dto.CommissionPaymentRow
import com.zifiq.communication.dto.PayAllRequest
import com.zifiq.communication.dto.PayCommissionRequest
import com.zifiq.communication.dto.ReversePaymentRequest
import com.zifiq.communication.service.PaymentService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
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

@Tag(name = "Communication — Payments", description = "Commission payments (docs/12 §4.3)")
@SecurityRequirement(name = "bearerAuth")
interface PaymentApi {
    @Operation(summary = "Pay a beneficiary's commission (partial allowed)")
    fun pay(request: PayCommissionRequest): ApiEnvelope<CommissionPaymentRow>

    @Operation(summary = "Pay every beneficiary's outstanding in a run")
    fun payAll(id: UUID, request: PayAllRequest): ApiEnvelope<List<CommissionPaymentRow>>

    @Operation(summary = "Reverse a payment (same day only)")
    fun reverse(id: UUID, request: ReversePaymentRequest): ApiEnvelope<CommissionPaymentRow>

    @Operation(summary = "List commission payments")
    fun payments(beneficiaryType: String?, beneficiaryId: UUID?, runId: UUID?, method: String?, page: Int?, size: Int?): ApiEnvelope<PageResult<CommissionPaymentRow>>
}

@RestController
@RequestMapping("/api/v1/communication")
class PaymentController(
    private val payments: PaymentService,
) : PaymentApi {
    @PostMapping("/payments")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.payments.manage')")
    override fun pay(@Valid @RequestBody request: PayCommissionRequest): ApiEnvelope<CommissionPaymentRow> = ApiEnvelope(payments.pay(request))

    @PostMapping("/runs/{id}/pay-all")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.payments.manage')")
    override fun payAll(@PathVariable id: UUID, @Valid @RequestBody request: PayAllRequest): ApiEnvelope<List<CommissionPaymentRow>> = ApiEnvelope(payments.payAll(id, request))

    @PostMapping("/payments/{id}/reverse")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.payments.manage')")
    override fun reverse(@PathVariable id: UUID, @Valid @RequestBody request: ReversePaymentRequest): ApiEnvelope<CommissionPaymentRow> = ApiEnvelope(payments.reverse(id, request))

    @GetMapping("/payments")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.allocation.read')")
    override fun payments(
        @RequestParam(required = false) beneficiaryType: String?,
        @RequestParam(required = false) beneficiaryId: UUID?,
        @RequestParam(required = false) runId: UUID?,
        @RequestParam(required = false) method: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<CommissionPaymentRow>> = ApiEnvelope(payments.list(beneficiaryType, beneficiaryId, runId, method, PageRequest.of(page, size)))
}
