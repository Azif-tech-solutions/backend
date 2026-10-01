package com.zifiq.platform.accesscontrol.api

import com.zifiq.platform.accesscontrol.RoleAssignmentService
import com.zifiq.platform.accesscontrol.RoleAssignmentSummary
import com.zifiq.platform.api.ApiEnvelope
import java.util.UUID
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tenants/members/{userId}/roles/{roleId}")
class RoleAssignmentController(private val roleAssignmentService: RoleAssignmentService) : RoleAssignmentApi {
    @PutMapping
    override fun assignRole(
        @PathVariable userId: UUID,
        @PathVariable roleId: UUID,
    ): ApiEnvelope<RoleAssignmentSummary> = ApiEnvelope(roleAssignmentService.assignRole(userId, roleId))
}
