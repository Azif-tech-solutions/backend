package com.zifiq.invoicing.dto

data class NrsTaxCategorySummary(
    val code: String,
    val name: String,
)

data class NrsInvoiceTypeCodeSummary(
    val code: String,
    val name: String,
    val description: String,
)

data class NrsPaymentMeansCodeSummary(
    val code: String,
    val name: String,
    val description: String,
)

data class NrsUnitOfMeasureCodeSummary(
    val code: String,
    val name: String,
    val description: String,
)

data class HsnCodeSummary(
    val code: String,
    val sectionCode: String,
    val description: String,
)

data class IsicClassSummary(
    val code: String,
    val sectionCode: String,
    val description: String,
)

data class NigeriaStateSummary(
    val code: String,
    val name: String,
)

data class NigeriaLgaSummary(
    val code: String,
    val stateCode: String,
    val name: String,
)
