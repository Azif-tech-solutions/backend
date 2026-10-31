package com.zifiq.history.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.ApplyFlagRequest
import com.zifiq.history.dto.DataQualityFlagRow
import com.zifiq.history.dto.DismissFlagRequest
import com.zifiq.history.dto.ManualFlagRequest
import com.zifiq.history.dto.ScanResult
import com.zifiq.history.service.DataQualityService
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

private const val MANAGE = "@permissionAuthorizer.hasPermission(authentication, 'history.data-quality.manage')"

@Tag(name = "Audit — data quality", description = "Error Handler: spelling / naming flags on product data (docs/10 §4.8)")
@SecurityRequirement(name = "bearerAuth")
interface DataQualityApi {
    @Operation(summary = "Run the data-quality scan now")
    fun scan(): ApiEnvelope<ScanResult>

    @Operation(summary = "Raise a data-quality flag by hand")
    fun createFlag(request: ManualFlagRequest): ApiEnvelope<DataQualityFlagRow>

    @Operation(summary = "Data-quality flags (default OPEN)")
    fun listFlags(status: String?, kind: String?, entityType: String?, page: Int?, size: Int?): ApiEnvelope<PageResult<DataQualityFlagRow>>

    @Operation(summary = "Apply a correction to the master record")
    fun applyFlag(id: UUID, request: ApplyFlagRequest): ApiEnvelope<DataQualityFlagRow>

    @Operation(summary = "Dismiss a flag (optionally never flag the token/value again)")
    fun dismissFlag(id: UUID, request: DismissFlagRequest): ApiEnvelope<DataQualityFlagRow>
}

@RestController
@RequestMapping("/api/v1/history/data-quality")
class DataQualityController(private val dataQuality: DataQualityService) : DataQualityApi {
    @PostMapping("/scan")
    @PreAuthorize(MANAGE)
    override fun scan() = ApiEnvelope(dataQuality.scan())

    @PostMapping("/flags")
    @PreAuthorize(MANAGE)
    override fun createFlag(@Valid @RequestBody request: ManualFlagRequest) = ApiEnvelope(dataQuality.createFlag(request))

    @GetMapping("/flags")
    @PreAuthorize(MANAGE)
    override fun listFlags(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ) = ApiEnvelope(dataQuality.list(status, kind, entityType, PageRequest.of(page, size)))

    @PostMapping("/flags/{id}/apply")
    @PreAuthorize(MANAGE)
    override fun applyFlag(@PathVariable id: UUID, @Valid @RequestBody request: ApplyFlagRequest) = ApiEnvelope(dataQuality.apply(id, request))

    @PostMapping("/flags/{id}/dismiss")
    @PreAuthorize(MANAGE)
    override fun dismissFlag(@PathVariable id: UUID, @Valid @RequestBody request: DismissFlagRequest) = ApiEnvelope(dataQuality.dismiss(id, request))
}
