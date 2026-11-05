package com.zifiq.communication.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.communication.dto.ApproveRunRequest
import com.zifiq.communication.dto.CancelRunRequest
import com.zifiq.communication.dto.CreateRunRequest
import com.zifiq.communication.dto.RunDetail
import com.zifiq.communication.dto.RunRow
import com.zifiq.communication.service.AllocationService
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

@Tag(name = "Communication — Allocation runs", description = "Monthly commission allocation runs (docs/12 §4.2)")
@SecurityRequirement(name = "bearerAuth")
interface RunsApi {
    @Operation(summary = "Calculate a run for a period")
    fun createRun(request: CreateRunRequest): ApiEnvelope<RunDetail>

    @Operation(summary = "Recalculate a draft run")
    fun recalculate(id: UUID): ApiEnvelope<RunDetail>

    @Operation(summary = "Approve a run (approver differs from the runner)")
    fun approve(id: UUID, request: ApproveRunRequest): ApiEnvelope<RunDetail>

    @Operation(summary = "Cancel a run")
    fun cancel(id: UUID, request: CancelRunRequest): ApiEnvelope<RunDetail>

    @Operation(summary = "Previous runs")
    fun runs(beneficiaryType: String?, status: String?, page: Int?, size: Int?): ApiEnvelope<PageResult<RunRow>>

    @Operation(summary = "A run, by beneficiary or by line")
    fun detail(id: UUID, view: String, beneficiaryId: UUID?): ApiEnvelope<RunDetail>
}

@RestController
@RequestMapping("/api/v1/communication/runs")
class RunsController(
    private val allocation: AllocationService,
) : RunsApi {
    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.allocation.run')")
    override fun createRun(@Valid @RequestBody request: CreateRunRequest): ApiEnvelope<RunDetail> = ApiEnvelope(allocation.createRun(request))

    @PostMapping("/{id}/recalculate")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.allocation.run')")
    override fun recalculate(@PathVariable id: UUID): ApiEnvelope<RunDetail> = ApiEnvelope(allocation.recalculate(id))

    @PostMapping("/{id}/approve")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.allocation.approve')")
    override fun approve(@PathVariable id: UUID, @Valid @RequestBody request: ApproveRunRequest): ApiEnvelope<RunDetail> = ApiEnvelope(allocation.approve(id, request))

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.allocation.run')")
    override fun cancel(@PathVariable id: UUID, @Valid @RequestBody request: CancelRunRequest): ApiEnvelope<RunDetail> = ApiEnvelope(allocation.cancel(id, request))

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.allocation.read')")
    override fun runs(
        @RequestParam(required = false) beneficiaryType: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<RunRow>> = ApiEnvelope(allocation.list(beneficiaryType, status, PageRequest.of(page, size)))

    @GetMapping("/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'communication.allocation.read')")
    override fun detail(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "beneficiary") view: String,
        @RequestParam(required = false) beneficiaryId: UUID?,
    ): ApiEnvelope<RunDetail> = ApiEnvelope(allocation.detail(id, view, beneficiaryId))
}
