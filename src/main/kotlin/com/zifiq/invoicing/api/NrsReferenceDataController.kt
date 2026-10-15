package com.zifiq.invoicing.api

import com.zifiq.invoicing.dto.*
import com.zifiq.invoicing.service.NrsReferenceDataService
import com.zifiq.platform.api.ApiEnvelope
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

// Invoicing reads these code lists for invoice lines; the VAT Control screen reads them too (docs/08 §3).
private const val READ_REFERENCE =
    "@permissionAuthorizer.hasPermission(authentication, 'invoicing.invoice.read') or @permissionAuthorizer.hasPermission(authentication, 'accounting.vat.manage')"

@RestController
@RequestMapping("/api/v1/invoicing/reference")
class NrsReferenceDataController(
    private val nrsReferenceDataService: NrsReferenceDataService,
) : NrsReferenceDataApi {
    @GetMapping("/tax-categories")
    @PreAuthorize(READ_REFERENCE)
    override fun listTaxCategories(): ApiEnvelope<List<NrsTaxCategorySummary>> =
        ApiEnvelope(nrsReferenceDataService.listTaxCategories())

    @GetMapping("/invoice-type-codes")
    @PreAuthorize(READ_REFERENCE)
    override fun listInvoiceTypeCodes(): ApiEnvelope<List<NrsInvoiceTypeCodeSummary>> =
        ApiEnvelope(nrsReferenceDataService.listInvoiceTypeCodes())

    @GetMapping("/payment-means-codes")
    @PreAuthorize(READ_REFERENCE)
    override fun listPaymentMeansCodes(): ApiEnvelope<List<NrsPaymentMeansCodeSummary>> =
        ApiEnvelope(nrsReferenceDataService.listPaymentMeansCodes())

    @GetMapping("/unit-of-measure-codes")
    @PreAuthorize(READ_REFERENCE)
    override fun listUnitOfMeasureCodes(): ApiEnvelope<List<NrsUnitOfMeasureCodeSummary>> =
        ApiEnvelope(nrsReferenceDataService.listUnitOfMeasureCodes())

    @GetMapping("/hsn-codes")
    @PreAuthorize(READ_REFERENCE)
    override fun listHsnCodes(@RequestParam(required = false) sectionCode: String?): ApiEnvelope<List<HsnCodeSummary>> =
        ApiEnvelope(nrsReferenceDataService.listHsnCodes(sectionCode))

    @GetMapping("/isic-classes")
    @PreAuthorize(READ_REFERENCE)
    override fun listIsicClasses(@RequestParam(required = false) sectionCode: String?): ApiEnvelope<List<IsicClassSummary>> =
        ApiEnvelope(nrsReferenceDataService.listIsicClasses(sectionCode))

    @GetMapping("/states")
    @PreAuthorize(READ_REFERENCE)
    override fun listStates(): ApiEnvelope<List<NigeriaStateSummary>> =
        ApiEnvelope(nrsReferenceDataService.listStates())

    @GetMapping("/lgas")
    @PreAuthorize(READ_REFERENCE)
    override fun listLgas(@RequestParam(required = false) stateCode: String?): ApiEnvelope<List<NigeriaLgaSummary>> =
        ApiEnvelope(nrsReferenceDataService.listLgas(stateCode))
}
