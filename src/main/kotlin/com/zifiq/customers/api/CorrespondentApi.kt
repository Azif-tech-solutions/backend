package com.zifiq.customers.api

import com.zifiq.common.paging.PageParams
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
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.util.MultiValueMap

@Tag(name = "Customers — correspondents", description = "Sales representatives and their assignment lifecycle (docs/04 §4.10)")
@SecurityRequirement(name = "bearerAuth")
interface CorrespondentApi {
    @Operation(summary = "Register a correspondent (and assign in one step when region + locationId are given)")
    fun create(request: CorrespondentRequest): ApiEnvelope<CorrespondentDetail>

    @Operation(summary = "List correspondents (paged): q, status, region, locationId, userLinked, hasCustomers, sort (name, customerCount, dealerCount, assignedAt)")
    fun list(
        q: String?,
        status: Set<String>?,
        region: Set<String>?,
        locationId: UUID?,
        userLinked: Boolean?,
        hasCustomers: Boolean?,
        sort: String?,
        paging: PageParams,
    ): ApiEnvelope<PageResult<CorrespondentSummary>>

    @Operation(summary = "Correspondent picker")
    fun lookup(q: String?, assignedOnly: Boolean): ApiEnvelope<List<CorrespondentSummary>>

    @Operation(summary = "Correspondent detail with assignment history")
    fun get(id: UUID): ApiEnvelope<CorrespondentDetail>

    @Operation(summary = "Edit name, phone, e-mail, linked user, responsibilities")
    fun update(id: UUID, request: CorrespondentUpdateRequest): ApiEnvelope<CorrespondentDetail>

    @Operation(summary = "Assign an unassigned / de-assigned correspondent")
    fun assign(id: UUID, request: CorrespondentAssignRequest): ApiEnvelope<CorrespondentDetail>

    @Operation(summary = "Move an assigned correspondent to another region / location")
    fun reassign(id: UUID, request: CorrespondentAssignRequest): ApiEnvelope<CorrespondentDetail>

    @Operation(summary = "De-assign; linked customers keep the link (affectedCustomers says how many)")
    fun deassign(id: UUID, request: CorrespondentDeassignRequest): ApiEnvelope<CorrespondentDetail>

    @Operation(summary = "The correspondent's customers (customer list filters apply)")
    fun customers(id: UUID, query: MultiValueMap<String, String>): ApiEnvelope<PageResult<CustomerSummary>>
}

@Tag(name = "Customers — dealer categories", description = "Platinum / Gold / Silver / Bronze and custom categories (docs/04 §4.9)")
@SecurityRequirement(name = "bearerAuth")
interface DealerCategoryApi {
    @Operation(summary = "List dealer categories with their dealer counts")
    fun list(status: String?, q: String?): ApiEnvelope<List<DealerCategorySummary>>

    @Operation(summary = "Create a dealer category")
    fun create(request: DealerCategoryRequest): ApiEnvelope<DealerCategorySummary>

    @Operation(summary = "Edit a dealer category (existing dealers' credit limits are unchanged)")
    fun update(id: UUID, request: DealerCategoryRequest): ApiEnvelope<DealerCategorySummary>

    @Operation(summary = "Activate / deactivate (blocked while dealers use it)")
    fun changeStatus(id: UUID, request: DealerCategoryStatusRequest): ApiEnvelope<DealerCategorySummary>
}
