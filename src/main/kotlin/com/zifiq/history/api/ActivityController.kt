package com.zifiq.history.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.ActivityRow
import com.zifiq.history.service.ActivityLogService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val ACTIVITY = "@permissionAuthorizer.hasPermission(authentication, 'platform.activity.read')"

@Tag(name = "Audit — activity log", description = "The business-event trail, and one record's timeline (docs/10 §4.7)")
@SecurityRequirement(name = "bearerAuth")
interface ActivityApi {
    @Operation(summary = "The activity log, filtered and paged (module, eventType, actor, ref, location, costAccessOnly, dates, q)")
    fun listActivity(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ActivityRow>>

    @Operation(summary = "Filters the activity log accepts")
    fun activityFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "The full timeline of one record (oldest first) — the cross-module History tab")
    fun entityTimeline(aggregateType: String, aggregateId: UUID): ApiEnvelope<List<ActivityRow>>
}

@RestController
@RequestMapping("/api/v1/audit/activity")
class ActivityController(private val activity: ActivityLogService) : ActivityApi {
    @GetMapping
    @PreAuthorize(ACTIVITY)
    override fun listActivity(@ParameterObject @RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(activity.list(ListSearchRequest.fromQuery(query)))

    @GetMapping("/filters")
    @PreAuthorize(ACTIVITY)
    override fun activityFilters() = ApiEnvelope(activity.filterMetadata())

    @GetMapping("/entity")
    @PreAuthorize(ACTIVITY)
    override fun entityTimeline(
        @RequestParam aggregateType: String,
        @RequestParam aggregateId: UUID,
    ) = ApiEnvelope(activity.entityTimeline(aggregateType, aggregateId))
}
