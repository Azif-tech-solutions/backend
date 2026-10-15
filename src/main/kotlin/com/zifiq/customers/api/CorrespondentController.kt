package com.zifiq.customers.api

import org.springdoc.core.annotations.ParameterObject
import com.zifiq.common.paging.PageParams
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.customers.dto.CorrespondentAssignRequest
import com.zifiq.customers.dto.CorrespondentDeassignRequest
import com.zifiq.customers.dto.CorrespondentDetail
import com.zifiq.customers.dto.CorrespondentRequest
import com.zifiq.customers.dto.CorrespondentSummary
import com.zifiq.customers.dto.CorrespondentUpdateRequest
import com.zifiq.customers.dto.CustomerSummary
import com.zifiq.customers.dto.DealerCategoryRequest
import com.zifiq.customers.dto.DealerCategoryStatusRequest
import com.zifiq.customers.dto.DealerCategorySummary
import com.zifiq.customers.service.CorrespondentService
import com.zifiq.customers.service.CustomerListService
import com.zifiq.customers.service.DealerCategoryService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.util.UUID
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

private const val MANAGE_CORRESPONDENTS = "@permissionAuthorizer.hasPermission(authentication, 'customers.correspondents.manage')"

@RestController
@RequestMapping("/api/v1/customers/correspondents")
class CorrespondentController(
    private val correspondentService: CorrespondentService,
    private val customerListService: CustomerListService,
) : CorrespondentApi {
    @PostMapping
    @PreAuthorize(MANAGE_CORRESPONDENTS)
    override fun create(@Valid @RequestBody request: CorrespondentRequest): ApiEnvelope<CorrespondentDetail> =
        ApiEnvelope(correspondentService.create(request))

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: Set<String>?,
        @RequestParam(required = false) region: Set<String>?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) userLinked: Boolean?,
        @RequestParam(required = false) hasCustomers: Boolean?,
        @RequestParam(required = false) sort: String?,
        @ParameterObject paging: PageParams,
    ): ApiEnvelope<PageResult<CorrespondentSummary>> =
        ApiEnvelope(correspondentService.list(q, status.orEmpty(), region.orEmpty(), locationId, userLinked, hasCustomers, sort, paging.toRequest()))

    @GetMapping("/lookup")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun lookup(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "true") assignedOnly: Boolean,
    ): ApiEnvelope<List<CorrespondentSummary>> = ApiEnvelope(correspondentService.lookup(q, assignedOnly))

    @GetMapping("/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun get(@PathVariable id: UUID): ApiEnvelope<CorrespondentDetail> = ApiEnvelope(correspondentService.getDetail(id))

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE_CORRESPONDENTS)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: CorrespondentUpdateRequest): ApiEnvelope<CorrespondentDetail> =
        ApiEnvelope(correspondentService.update(id, request))

    @PostMapping("/{id}/assign")
    @PreAuthorize(MANAGE_CORRESPONDENTS)
    override fun assign(@PathVariable id: UUID, @Valid @RequestBody request: CorrespondentAssignRequest): ApiEnvelope<CorrespondentDetail> =
        ApiEnvelope(correspondentService.assign(id, request))

    @PostMapping("/{id}/reassign")
    @PreAuthorize(MANAGE_CORRESPONDENTS)
    override fun reassign(@PathVariable id: UUID, @Valid @RequestBody request: CorrespondentAssignRequest): ApiEnvelope<CorrespondentDetail> =
        ApiEnvelope(correspondentService.reassign(id, request))

    @PostMapping("/{id}/de-assign")
    @PreAuthorize(MANAGE_CORRESPONDENTS)
    override fun deassign(@PathVariable id: UUID, @Valid @RequestBody request: CorrespondentDeassignRequest): ApiEnvelope<CorrespondentDetail> =
        ApiEnvelope(correspondentService.deassign(id, request))

    @GetMapping("/{id}/customers")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun customers(@PathVariable id: UUID, @RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<CustomerSummary>> {
        correspondentService.getDetail(id)
        return ApiEnvelope(customerListService.list(ListSearchRequest.fromQuery(query), correspondentId = id))
    }
}

@RestController
@RequestMapping("/api/v1/customers/dealer-categories")
class DealerCategoryController(
    private val dealerCategoryService: DealerCategoryService,
) : DealerCategoryApi {
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) q: String?,
    ): ApiEnvelope<List<DealerCategorySummary>> = ApiEnvelope(dealerCategoryService.list(status, q))

    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.categories.manage')")
    override fun create(@Valid @RequestBody request: DealerCategoryRequest): ApiEnvelope<DealerCategorySummary> =
        ApiEnvelope(dealerCategoryService.create(request))

    @PutMapping("/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.categories.manage')")
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: DealerCategoryRequest): ApiEnvelope<DealerCategorySummary> =
        ApiEnvelope(dealerCategoryService.update(id, request))

    @PutMapping("/{id}/status")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.categories.manage')")
    override fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: DealerCategoryStatusRequest): ApiEnvelope<DealerCategorySummary> =
        ApiEnvelope(dealerCategoryService.changeStatus(id, request))
}
