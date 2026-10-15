package com.zifiq.platform.identity.api

import org.springframework.web.bind.annotation.RequestParam
import org.springdoc.core.annotations.ParameterObject
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.PageParams
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.identity.MemberService
import com.zifiq.platform.identity.dto.InviteMemberRequest
import com.zifiq.platform.identity.dto.MemberLocationRequest
import com.zifiq.platform.identity.dto.MemberStatusRequest
import com.zifiq.platform.identity.dto.MemberSummary
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/tenants/members")
class MembersController(
    private val memberService: MemberService,
) : MembersApi {
    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.users.manage')")
    override fun inviteMember(@Valid @RequestBody request: InviteMemberRequest): ApiEnvelope<MemberSummary> =
        ApiEnvelope(memberService.inviteMember(request))

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.users.manage')")
    override fun listMembers(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @ParameterObject paging: PageParams,
    ): ApiEnvelope<PageResult<MemberSummary>> = ApiEnvelope(memberService.listMembers(q, status, paging.toRequest()))

    @PutMapping("/{userId}/status")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.users.manage')")
    override fun updateStatus(
        @PathVariable userId: UUID,
        @Valid @RequestBody request: MemberStatusRequest,
    ): ApiEnvelope<MemberSummary> =
        ApiEnvelope(memberService.updateStatus(userId, request.status))

    @PutMapping("/{userId}/location")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.users.manage')")
    override fun updateLocation(
        @PathVariable userId: UUID,
        @Valid @RequestBody request: MemberLocationRequest,
    ): ApiEnvelope<MemberSummary> =
        ApiEnvelope(memberService.updateLocation(userId, request.locationId))
}
