package com.zifiq.menu.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.identity.MemberService
import com.zifiq.platform.identity.dto.InviteMemberRequest
import com.zifiq.platform.identity.dto.MemberSummary
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/menu")
class MenuController(
    private val memberService: MemberService,
) : MenuApi {
    @PostMapping("/users/register")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.users.manage')")
    override fun registerUserAndAssignRole(
        @Valid @RequestBody request: InviteMemberRequest,
    ): ApiEnvelope<MemberSummary> =
        ApiEnvelope(memberService.inviteMember(request))
}
