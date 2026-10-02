package com.zifiq.invoicing.api

import com.zifiq.invoicing.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(
    name = "NRS Reference Data",
    description = "System-seeded, real NRS e-invoicing reference data (tax categories, invoice types, " +
        "payment means, unit-of-measure codes, HSN/ISIC codes, Nigeria states/LGAs) -- the foundation " +
        "the Electronic Invoicing module's real NRS submissions will be built against",
)
@SecurityRequirement(name = "bearerAuth")
interface NrsReferenceDataApi {
    @Operation(summary = "List NRS tax_category codes (invoice_line.tax_total[].tax_subtotal[].tax_category.id)")
    fun listTaxCategories(): ApiEnvelope<List<NrsTaxCategorySummary>>

    @Operation(summary = "List NRS invoice_type_code values")
    fun listInvoiceTypeCodes(): ApiEnvelope<List<NrsInvoiceTypeCodeSummary>>

    @Operation(summary = "List NRS payment_means_code values")
    fun listPaymentMeansCodes(): ApiEnvelope<List<NrsPaymentMeansCodeSummary>>

    @Operation(summary = "List NRS unit-of-measure codes (invoice_line.price.price_unit)")
    fun listUnitOfMeasureCodes(): ApiEnvelope<List<NrsUnitOfMeasureCodeSummary>>

    @Operation(summary = "List HSN codes at subheading granularity (invoice_line.hsn_code), optionally filtered by HSN section code")
    fun listHsnCodes(sectionCode: String?): ApiEnvelope<List<HsnCodeSummary>>

    @Operation(summary = "List ISIC Rev.4 class codes (invoice_line.isic_code), optionally filtered by ISIC section code")
    fun listIsicClasses(sectionCode: String?): ApiEnvelope<List<IsicClassSummary>>

    @Operation(summary = "List Nigeria states (postal_address.state)")
    fun listStates(): ApiEnvelope<List<NigeriaStateSummary>>

    @Operation(summary = "List Nigeria LGAs (postal_address.lga), optionally filtered by state code")
    fun listLgas(stateCode: String?): ApiEnvelope<List<NigeriaLgaSummary>>
}
