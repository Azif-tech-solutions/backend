package com.zifiq.customers.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.customers.dto.CustomerCorrespondentRequest
import com.zifiq.customers.dto.CustomerDetail
import com.zifiq.customers.dto.CustomerLookupRow
import com.zifiq.customers.dto.CustomerRequest
import com.zifiq.customers.dto.CustomerStats
import com.zifiq.customers.dto.CustomerStatusRequest
import com.zifiq.customers.dto.CustomerSummary
import com.zifiq.customers.dto.CustomerUpdateRequest
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.util.MultiValueMap

@Tag(name = "Customers", description = "Customer & dealer register, search, status and self-service (docs/04 §4.1–§4.3, §4.7, §4.11, §9)")
@SecurityRequirement(name = "bearerAuth")
interface CustomerApi {
    @Operation(summary = "Register a customer of any type (dealers and credit limits also need customers.dealers.manage)")
    fun register(request: CustomerRequest): ApiEnvelope<CustomerDetail>

    @Operation(summary = "List / search customers: q, simple filters, facets, summary, highlight (docs/04 §9)")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<CustomerSummary>>

    @Operation(summary = "Search customers with an advanced filter tree (docs/03 §5.3)")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<CustomerSummary>>

    @Operation(summary = "Filterable fields, operators, options and sorts for the customer list")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Register header tiles: counts and balance totals")
    fun stats(type: CustomerType?, locationId: UUID?): ApiEnvelope<CustomerStats>

    @Operation(summary = "Customer picker for POS, pro-forma and returns (not location-restricted)")
    fun lookup(q: String?, types: Set<CustomerType>?, forSale: Boolean, limit: Int): ApiEnvelope<List<CustomerLookupRow>>

    @Operation(summary = "Get a customer")
    fun get(id: UUID): ApiEnvelope<CustomerDetail>

    @Operation(summary = "Edit a customer (type and dealer category change only through reclassification)")
    fun update(id: UUID, request: CustomerUpdateRequest): ApiEnvelope<CustomerDetail>

    @Operation(summary = "Set ACTIVE / WATCH / SUSPENDED (SUSPENDED needs customers.dealers.manage)")
    fun changeStatus(id: UUID, request: CustomerStatusRequest): ApiEnvelope<CustomerDetail>

    @Operation(summary = "Register a temporary walk-in customer properly; code and wallet are kept")
    fun convertTemporary(id: UUID, request: CustomerRequest): ApiEnvelope<CustomerDetail>

    @Operation(summary = "Link or unlink the customer's correspondent (must be ASSIGNED)")
    fun linkCorrespondent(id: UUID, request: CustomerCorrespondentRequest): ApiEnvelope<CustomerDetail>

    @Operation(summary = "Self-service: the dealer/customer record linked to the caller's login")
    fun me(): ApiEnvelope<CustomerDetail>
}
