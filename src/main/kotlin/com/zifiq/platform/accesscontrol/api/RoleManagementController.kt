package com.zifiq.platform.accesscontrol.api

import com.zifiq.platform.accesscontrol.CreateRoleCommand
import com.zifiq.platform.accesscontrol.RoleManagementService
import com.zifiq.platform.accesscontrol.TenantRoleSummary
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tenants/roles")
class RoleManagementController(private val roleManagementService: RoleManagementService) : RoleManagementApi {
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.roles.manage')")
    override fun listRoles(): ApiEnvelope<List<TenantRoleSummary>> = ApiEnvelope(roleManagementService.listRoles())

    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.roles.manage')")
    override fun createRole(@Valid @RequestBody body: CreateRoleBody): ApiEnvelope<TenantRoleSummary> =
        ApiEnvelope(
            roleManagementService.createRole(
                CreateRoleCommand(body.name, body.description, body.permissions),
            ),
        )
}
