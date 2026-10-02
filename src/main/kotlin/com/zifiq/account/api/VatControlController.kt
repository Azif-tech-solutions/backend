package com.zifiq.account.api

import com.zifiq.account.dto.*
import com.zifiq.account.service.TaxClassificationService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/account/vat-control")
class VatControlController(
    private val taxClassificationService: TaxClassificationService,
) : VatControlApi {
    @GetMapping("/vat-categories")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun listVatCategories(): ApiEnvelope<List<VatCategorySummary>> =
        ApiEnvelope(taxClassificationService.listVatCategories())

    @GetMapping("/hsn-sections")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun listHsnSections(): ApiEnvelope<List<HsnSectionSummary>> =
        ApiEnvelope(taxClassificationService.listHsnSections())

    @GetMapping("/isic-sections")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun listIsicSections(): ApiEnvelope<List<IsicSectionSummary>> =
        ApiEnvelope(taxClassificationService.listIsicSections())

    @GetMapping("/tenant-vat-categories")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun getTenantVatCategories(): ApiEnvelope<List<TenantVatCategorySummary>> =
        ApiEnvelope(taxClassificationService.getTenantVatCategories())

    @PutMapping("/tenant-vat-categories")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.vat.manage')")
    override fun selectTenantVatCategories(
        @Valid @RequestBody request: TenantVatCategorySelectionRequest,
    ): ApiEnvelope<List<TenantVatCategorySummary>> =
        ApiEnvelope(taxClassificationService.selectTenantVatCategories(request))
}
