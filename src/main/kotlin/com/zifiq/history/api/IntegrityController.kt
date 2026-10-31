package com.zifiq.history.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.IntegrityRunDetail
import com.zifiq.history.dto.IntegrityRunRow
import com.zifiq.history.service.IntegrityCheckService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val INTEGRITY = "@permissionAuthorizer.hasPermission(authentication, 'history.integrity.read')"

@Tag(name = "Audit — integrity", description = "Integrity check runs and findings (docs/10 §4.10)")
@SecurityRequirement(name = "bearerAuth")
interface IntegrityApi {
    @Operation(summary = "Run the integrity checks now (one run at a time per tenant)")
    fun run(): ApiEnvelope<IntegrityRunDetail>

    @Operation(summary = "Integrity check runs, newest first")
    fun listRuns(page: Int?, size: Int?): ApiEnvelope<PageResult<IntegrityRunRow>>

    @Operation(summary = "One run with its findings")
    fun runDetail(id: UUID): ApiEnvelope<IntegrityRunDetail>
}

@RestController
@RequestMapping("/api/v1/audit/integrity")
class IntegrityController(private val integrity: IntegrityCheckService) : IntegrityApi {
    @PostMapping("/runs")
    @PreAuthorize(INTEGRITY)
    override fun run() = ApiEnvelope(integrity.run("MANUAL"))

    @GetMapping("/runs")
    @PreAuthorize(INTEGRITY)
    override fun listRuns(@RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?) =
        ApiEnvelope(integrity.listRuns(PageRequest.of(page, size)))

    @GetMapping("/runs/{id}")
    @PreAuthorize(INTEGRITY)
    override fun runDetail(@PathVariable id: UUID) = ApiEnvelope(integrity.detail(id))
}
