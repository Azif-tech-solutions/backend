package com.zifiq.process.api

import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.process.dto.*
import com.zifiq.process.service.ProcessGuideService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/process")
class ProcessGuideController(
    private val processGuideService: ProcessGuideService,
) : ProcessGuideApi {
    @GetMapping("/commissions/guide")
    override fun getCommissionRules(): ApiEnvelope<List<CommissionRuleSummary>> =
        ApiEnvelope(processGuideService.getCommissionRules())

    @PostMapping("/commissions/guide")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.roles.manage')")
    override fun createCommissionRule(
        @Valid @RequestBody request: CommissionRuleRequest,
    ): ApiEnvelope<CommissionRuleSummary> =
        ApiEnvelope(processGuideService.createCommissionRule(request))

    @GetMapping("/errors")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.roles.manage')")
    override fun getSystemErrors(): ApiEnvelope<List<SystemErrorLogItem>> =
        ApiEnvelope(processGuideService.getSystemErrors())

    @GetMapping("/recycle-bin")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.roles.manage')")
    override fun getRecycleBinItems(): ApiEnvelope<List<RecycleBinItem>> =
        ApiEnvelope(processGuideService.getRecycleBinItems())

    @PostMapping("/correspondents")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.roles.manage')")
    override fun registerCorrespondent(
        @Valid @RequestBody request: SalesCorrespondentRequest,
    ): ApiEnvelope<SalesCorrespondentSummary> =
        ApiEnvelope(processGuideService.registerCorrespondent(request))
}
