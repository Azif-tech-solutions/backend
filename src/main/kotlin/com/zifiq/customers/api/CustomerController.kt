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
import com.zifiq.customers.service.CorrespondentService
import com.zifiq.customers.service.CustomerListService
import com.zifiq.customers.service.CustomerService
import com.zifiq.customers.spi.CustomerType
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

private const val READ = "@permissionAuthorizer.hasPermission(authentication, 'customers.read') or " +
    "@permissionAuthorizer.hasPermission(authentication, 'customers.read.own')"
private const val MANAGE = "@permissionAuthorizer.hasPermission(authentication, 'customers.manage') or " +
    "@permissionAuthorizer.hasPermission(authentication, 'customers.dealers.manage')"

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController(
    private val customerService: CustomerService,
    private val customerListService: CustomerListService,
    private val correspondentService: CorrespondentService,
) : CustomerApi {
    @PostMapping
    @PreAuthorize(MANAGE)
    override fun register(@Valid @RequestBody request: CustomerRequest): ApiEnvelope<CustomerDetail> =
        ApiEnvelope(customerService.register(request))

    @GetMapping
    @PreAuthorize(READ)
    override fun list(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<CustomerSummary>> =
        ApiEnvelope(customerListService.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ)
    override fun search(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<CustomerSummary>> =
        ApiEnvelope(customerListService.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ)
    override fun filters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(customerListService.filters())

    @GetMapping("/stats")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun stats(
        @RequestParam(required = false) type: CustomerType?,
        @RequestParam(required = false) locationId: UUID?,
    ): ApiEnvelope<CustomerStats> = ApiEnvelope(customerListService.stats(type, locationId))

    @GetMapping("/lookup")
    @PreAuthorize(READ)
    override fun lookup(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) types: Set<CustomerType>?,
        @RequestParam(defaultValue = "false") forSale: Boolean,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ApiEnvelope<List<CustomerLookupRow>> = ApiEnvelope(customerListService.lookup(q, types, forSale, limit))

    @GetMapping("/me")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.self.read')")
    override fun me(): ApiEnvelope<CustomerDetail> = ApiEnvelope(customerService.me())

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    override fun get(@PathVariable id: UUID): ApiEnvelope<CustomerDetail> = ApiEnvelope(customerService.get(id))

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: CustomerUpdateRequest): ApiEnvelope<CustomerDetail> =
        ApiEnvelope(customerService.update(id, request))

    @PutMapping("/{id}/status")
    @PreAuthorize(MANAGE)
    override fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: CustomerStatusRequest): ApiEnvelope<CustomerDetail> =
        ApiEnvelope(customerService.changeStatus(id, request))

    @PostMapping("/{id}/register")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.manage')")
    override fun convertTemporary(@PathVariable id: UUID, @Valid @RequestBody request: CustomerRequest): ApiEnvelope<CustomerDetail> =
        ApiEnvelope(customerService.convertTemporary(id, request))

    @PutMapping("/{id}/correspondent")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.correspondents.manage')")
    override fun linkCorrespondent(@PathVariable id: UUID, @RequestBody request: CustomerCorrespondentRequest): ApiEnvelope<CustomerDetail> =
        ApiEnvelope(correspondentService.linkCustomer(id, request))
}
