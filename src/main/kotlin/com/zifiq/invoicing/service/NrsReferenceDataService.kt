package com.zifiq.invoicing.service

import com.zifiq.common.tax.HsnCodesTable
import com.zifiq.common.tax.IsicClassesTable
import com.zifiq.common.tax.NigeriaLgasTable
import com.zifiq.common.tax.NigeriaStatesTable
import com.zifiq.common.tax.NrsInvoiceTypeCodesTable
import com.zifiq.common.tax.NrsPaymentMeansCodesTable
import com.zifiq.common.tax.NrsTaxCategoriesTable
import com.zifiq.common.tax.NrsUnitOfMeasureCodesTable
import com.zifiq.invoicing.dto.HsnCodeSummary
import com.zifiq.invoicing.dto.IsicClassSummary
import com.zifiq.invoicing.dto.NigeriaLgaSummary
import com.zifiq.invoicing.dto.NigeriaStateSummary
import com.zifiq.invoicing.dto.NrsInvoiceTypeCodeSummary
import com.zifiq.invoicing.dto.NrsPaymentMeansCodeSummary
import com.zifiq.invoicing.dto.NrsTaxCategorySummary
import com.zifiq.invoicing.dto.NrsUnitOfMeasureCodeSummary
import com.zifiq.platform.tenancy.TenantScopedTransaction
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Read-only lookups over the global, system-seeded NRS e-invoicing reference data (see
 * V6__nrs_reference_data.sql) -- the code/name/description catalogs the electronic-invoicing
 * module (com.zifiq.invoicing) will build real NRS invoice submissions against. Every list here
 * mirrors a field in NRS's real "Invoice Schema" API
 * (POST base_url/api/v1/invoice/validate): tax_category.id, invoice_type_code,
 * payment_means_code, price.price_unit, invoice_line.hsn_code, invoice_line.isic_code, and the
 * postal_address state/lga fields.
 */
@Service
class NrsReferenceDataService(
    private val tenantScopedTransaction: TenantScopedTransaction,
) {
    fun listTaxCategories(): List<NrsTaxCategorySummary> = tenantScopedTransaction.execute {
        NrsTaxCategoriesTable.selectAll().orderBy(NrsTaxCategoriesTable.code).map {
            NrsTaxCategorySummary(code = it[NrsTaxCategoriesTable.code], name = it[NrsTaxCategoriesTable.name])
        }
    }

    fun listInvoiceTypeCodes(): List<NrsInvoiceTypeCodeSummary> = tenantScopedTransaction.execute {
        NrsInvoiceTypeCodesTable.selectAll().orderBy(NrsInvoiceTypeCodesTable.code).map {
            NrsInvoiceTypeCodeSummary(
                code = it[NrsInvoiceTypeCodesTable.code],
                name = it[NrsInvoiceTypeCodesTable.name],
                description = it[NrsInvoiceTypeCodesTable.description],
            )
        }
    }

    fun listPaymentMeansCodes(): List<NrsPaymentMeansCodeSummary> = tenantScopedTransaction.execute {
        NrsPaymentMeansCodesTable.selectAll().orderBy(NrsPaymentMeansCodesTable.code).map {
            NrsPaymentMeansCodeSummary(
                code = it[NrsPaymentMeansCodesTable.code],
                name = it[NrsPaymentMeansCodesTable.name],
                description = it[NrsPaymentMeansCodesTable.description],
            )
        }
    }

    fun listUnitOfMeasureCodes(): List<NrsUnitOfMeasureCodeSummary> = tenantScopedTransaction.execute {
        NrsUnitOfMeasureCodesTable.selectAll().orderBy(NrsUnitOfMeasureCodesTable.code).map {
            NrsUnitOfMeasureCodeSummary(
                code = it[NrsUnitOfMeasureCodesTable.code],
                name = it[NrsUnitOfMeasureCodesTable.name],
                description = it[NrsUnitOfMeasureCodesTable.description],
            )
        }
    }

    fun listHsnCodes(sectionCode: String?): List<HsnCodeSummary> = tenantScopedTransaction.execute {
        val query = HsnCodesTable.selectAll()
        if (!sectionCode.isNullOrBlank()) query.andWhere { HsnCodesTable.sectionCode eq sectionCode.uppercase() }
        query.orderBy(HsnCodesTable.code).map {
            HsnCodeSummary(
                code = it[HsnCodesTable.code],
                sectionCode = it[HsnCodesTable.sectionCode],
                description = it[HsnCodesTable.description],
            )
        }
    }

    fun listIsicClasses(sectionCode: String?): List<IsicClassSummary> = tenantScopedTransaction.execute {
        val query = IsicClassesTable.selectAll()
        if (!sectionCode.isNullOrBlank()) query.andWhere { IsicClassesTable.sectionCode eq sectionCode.uppercase() }
        query.orderBy(IsicClassesTable.code).map {
            IsicClassSummary(
                code = it[IsicClassesTable.code],
                sectionCode = it[IsicClassesTable.sectionCode],
                description = it[IsicClassesTable.description],
            )
        }
    }

    fun listStates(): List<NigeriaStateSummary> = tenantScopedTransaction.execute {
        NigeriaStatesTable.selectAll().orderBy(NigeriaStatesTable.name).map {
            NigeriaStateSummary(code = it[NigeriaStatesTable.code], name = it[NigeriaStatesTable.name])
        }
    }

    fun listLgas(stateCode: String?): List<NigeriaLgaSummary> = tenantScopedTransaction.execute {
        val query = NigeriaLgasTable.selectAll()
        if (!stateCode.isNullOrBlank()) query.andWhere { NigeriaLgasTable.stateCode eq stateCode.uppercase() }
        query.orderBy(NigeriaLgasTable.name).map {
            NigeriaLgaSummary(
                code = it[NigeriaLgasTable.code],
                stateCode = it[NigeriaLgasTable.stateCode],
                name = it[NigeriaLgasTable.name],
            )
        }
    }
}
