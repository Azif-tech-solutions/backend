package com.zifiq.invoicing.api

import com.zifiq.invoicing.dto.*
import com.zifiq.invoicing.service.NrsReferenceDataService
import com.zifiq.platform.api.ApiEnvelope
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/invoicing/reference")
class NrsReferenceDataController(
    private val nrsReferenceDataService: NrsReferenceDataService,
) : NrsReferenceDataApi {
    @GetMapping("/tax-categories")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun listTaxCategories(): ApiEnvelope<List<NrsTaxCategorySummary>> =
        ApiEnvelope(nrsReferenceDataService.listTaxCategories())

    @GetMapping("/invoice-type-codes")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun listInvoiceTypeCodes(): ApiEnvelope<List<NrsInvoiceTypeCodeSummary>> =
        ApiEnvelope(nrsReferenceDataService.listInvoiceTypeCodes())

    @GetMapping("/payment-means-codes")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun listPaymentMeansCodes(): ApiEnvelope<List<NrsPaymentMeansCodeSummary>> =
        ApiEnvelope(nrsReferenceDataService.listPaymentMeansCodes())

    @GetMapping("/unit-of-measure-codes")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun listUnitOfMeasureCodes(): ApiEnvelope<List<NrsUnitOfMeasureCodeSummary>> =
        ApiEnvelope(nrsReferenceDataService.listUnitOfMeasureCodes())

    @GetMapping("/hsn-codes")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun listHsnCodes(@RequestParam(required = false) sectionCode: String?): ApiEnvelope<List<HsnCodeSummary>> =
        ApiEnvelope(nrsReferenceDataService.listHsnCodes(sectionCode))

    @GetMapping("/isic-classes")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun listIsicClasses(@RequestParam(required = false) sectionCode: String?): ApiEnvelope<List<IsicClassSummary>> =
        ApiEnvelope(nrsReferenceDataService.listIsicClasses(sectionCode))

    @GetMapping("/states")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun listStates(): ApiEnvelope<List<NigeriaStateSummary>> =
        ApiEnvelope(nrsReferenceDataService.listStates())

    @GetMapping("/lgas")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun listLgas(@RequestParam(required = false) stateCode: String?): ApiEnvelope<List<NigeriaLgaSummary>> =
        ApiEnvelope(nrsReferenceDataService.listLgas(stateCode))
}
