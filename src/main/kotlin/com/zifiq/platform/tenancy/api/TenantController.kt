package com.zifiq.platform.tenancy.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.tenancy.TenantService
import com.zifiq.platform.tenancy.TenantSummary
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tenants")
class TenantController(private val tenantService: TenantService) : TenantApi {
    @GetMapping
    override fun listChildren(): ApiEnvelope<List<TenantSummary>> = ApiEnvelope(tenantService.listChildren())

    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'tenants.create_child')")
    override fun createChild(@Valid @RequestBody body: CreateTenantBody): ApiEnvelope<TenantSummary> =
        ApiEnvelope(tenantService.createChild(body.name))
}
