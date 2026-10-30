package com.zifiq.history.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.AuditReviewLookupRow
import com.zifiq.history.dto.AuditReviewRow
import com.zifiq.history.dto.AuditReviewUpsertRequest
import com.zifiq.history.service.AuditReviewService
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val REVIEWS = "@permissionAuthorizer.hasPermission(authentication, 'history.reviews.manage')"

@Tag(name = "Audit — reviews", description = "Mark any record under review / reviewed / flagged (docs/10 §4.11)")
@SecurityRequirement(name = "bearerAuth")
interface AuditReviewApi {
    @Operation(summary = "Set the audit-review state of a record (one state per record; FLAGGED needs a note)")
    fun upsertReview(request: AuditReviewUpsertRequest): ApiEnvelope<AuditReviewRow>

    @Operation(summary = "Audit reviews, filtered and paged")
    fun listReviews(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<AuditReviewRow>>

    @Operation(summary = "Filters the reviews list accepts")
    fun reviewFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "The review badge for records the caller already holds (batch)")
    fun lookupReviews(recordType: String, recordIds: List<UUID>?): ApiEnvelope<List<AuditReviewLookupRow>>
}

@RestController
@RequestMapping("/api/v1/audit/reviews")
class AuditReviewController(private val reviews: AuditReviewService) : AuditReviewApi {
    @PutMapping
    @PreAuthorize(REVIEWS)
    override fun upsertReview(@Valid @RequestBody request: AuditReviewUpsertRequest) = ApiEnvelope(reviews.upsert(request))

    @GetMapping
    @PreAuthorize(REVIEWS)
    override fun listReviews(@ParameterObject @RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(reviews.list(ListSearchRequest.fromQuery(query)))

    @GetMapping("/filters")
    @PreAuthorize(REVIEWS)
    override fun reviewFilters() = ApiEnvelope(reviews.filterMetadata())

    @GetMapping("/lookup")
    @PreAuthorize(REVIEWS)
    override fun lookupReviews(
        @RequestParam recordType: String,
        @RequestParam(required = false) recordIds: List<UUID>?,
    ) = ApiEnvelope(reviews.lookup(recordType, recordIds.orEmpty()))
}
