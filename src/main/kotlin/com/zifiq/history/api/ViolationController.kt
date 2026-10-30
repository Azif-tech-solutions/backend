package com.zifiq.history.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.AlertDismissRequest
import com.zifiq.history.dto.AuditAlertRow
import com.zifiq.history.dto.StaffViolationPanel
import com.zifiq.history.dto.ViolationConcludeRequest
import com.zifiq.history.dto.ViolationDetail
import com.zifiq.history.dto.ViolationNoteRequest
import com.zifiq.history.dto.ViolationRaiseRequest
import com.zifiq.history.dto.ViolationReopenRequest
import com.zifiq.history.dto.ViolationRow
import com.zifiq.history.dto.ViolationUpdateRequest
import com.zifiq.history.service.AuditAlertService
import com.zifiq.history.service.ViolationService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val READ = "@permissionAuthorizer.hasPermission(authentication, 'history.violations.read')"
private const val MANAGE = "@permissionAuthorizer.hasPermission(authentication, 'history.violations.manage')"

@Tag(name = "Audit — violations & alerts", description = "Confidential violation records and the system alerts that feed them (docs/10 §4.4)")
@SecurityRequirement(name = "bearerAuth")
interface ViolationApi {
    @Operation(summary = "Violation records (never records where you are the subject)")
    fun listViolations(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ViolationRow>>

    @Operation(summary = "Filters the violations list accepts")
    fun violationFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "One staff member's record, most recent first")
    fun staffPanel(userId: UUID): ApiEnvelope<StaffViolationPanel>

    @Operation(summary = "One violation record with its investigation trail")
    fun getViolation(id: UUID): ApiEnvelope<ViolationDetail>

    @Operation(summary = "Raise a violation record against a member of staff")
    fun raiseViolation(request: ViolationRaiseRequest): ApiEnvelope<ViolationDetail>

    @Operation(summary = "Add a note to the investigation trail")
    fun addNote(id: UUID, request: ViolationNoteRequest): ApiEnvelope<ViolationDetail>

    @Operation(summary = "Change severity or amount while under investigation")
    fun updateViolation(id: UUID, request: ViolationUpdateRequest): ApiEnvelope<ViolationDetail>

    @Operation(summary = "Conclude with an outcome (a second person, unless an Administrator)")
    fun concludeViolation(id: UUID, request: ViolationConcludeRequest): ApiEnvelope<ViolationDetail>

    @Operation(summary = "Reopen a concluded record on new evidence")
    fun reopenViolation(id: UUID, request: ViolationReopenRequest): ApiEnvelope<ViolationDetail>

    @Operation(summary = "Audit alerts (default OPEN)")
    fun listAlerts(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<AuditAlertRow>>

    @Operation(summary = "Filters the alerts list accepts")
    fun alertFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Open a violation record pre-filled from an alert")
    fun openRecord(id: UUID, request: ViolationRaiseRequest): ApiEnvelope<ViolationDetail>

    @Operation(summary = "Dismiss an alert")
    fun dismissAlert(id: UUID, request: AlertDismissRequest): ApiEnvelope<AuditAlertRow>
}

@RestController
@RequestMapping("/api/v1/history")
class ViolationController(
    private val violations: ViolationService,
    private val alerts: AuditAlertService,
) : ViolationApi {
    @GetMapping("/violations")
    @PreAuthorize(READ)
    override fun listViolations(@ParameterObject @RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(violations.list(ListSearchRequest.fromQuery(query)))

    @GetMapping("/violations/filters")
    @PreAuthorize(READ)
    override fun violationFilters() = ApiEnvelope(violations.filterMetadata())

    @GetMapping("/violations/staff/{userId}")
    @PreAuthorize(READ)
    override fun staffPanel(@PathVariable userId: UUID) = ApiEnvelope(violations.staffPanel(userId))

    @GetMapping("/violations/{id}")
    @PreAuthorize(READ)
    override fun getViolation(@PathVariable id: UUID) = ApiEnvelope(violations.detail(id))

    @PostMapping("/violations")
    @PreAuthorize(MANAGE)
    override fun raiseViolation(@Valid @RequestBody request: ViolationRaiseRequest) = ApiEnvelope(violations.raise(request))

    @PostMapping("/violations/{id}/notes")
    @PreAuthorize(MANAGE)
    override fun addNote(@PathVariable id: UUID, @Valid @RequestBody request: ViolationNoteRequest) = ApiEnvelope(violations.addNote(id, request))

    @PutMapping("/violations/{id}")
    @PreAuthorize(MANAGE)
    override fun updateViolation(@PathVariable id: UUID, @Valid @RequestBody request: ViolationUpdateRequest) = ApiEnvelope(violations.update(id, request))

    @PostMapping("/violations/{id}/conclude")
    @PreAuthorize(MANAGE)
    override fun concludeViolation(@PathVariable id: UUID, @Valid @RequestBody request: ViolationConcludeRequest) = ApiEnvelope(violations.conclude(id, request))

    @PostMapping("/violations/{id}/reopen")
    @PreAuthorize(MANAGE)
    override fun reopenViolation(@PathVariable id: UUID, @Valid @RequestBody request: ViolationReopenRequest) = ApiEnvelope(violations.reopen(id, request))

    @GetMapping("/audit-alerts")
    @PreAuthorize(READ)
    override fun listAlerts(@ParameterObject @RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(alerts.list(ListSearchRequest.fromQuery(query)))

    @GetMapping("/audit-alerts/filters")
    @PreAuthorize(READ)
    override fun alertFilters() = ApiEnvelope(alerts.filterMetadata())

    @PostMapping("/audit-alerts/{id}/open-record")
    @PreAuthorize(MANAGE)
    override fun openRecord(@PathVariable id: UUID, @Valid @RequestBody request: ViolationRaiseRequest) = ApiEnvelope(alerts.openRecord(id, request))

    @PostMapping("/audit-alerts/{id}/dismiss")
    @PreAuthorize(MANAGE)
    override fun dismissAlert(@PathVariable id: UUID, @Valid @RequestBody request: AlertDismissRequest) = ApiEnvelope(alerts.dismiss(id, request))
}
