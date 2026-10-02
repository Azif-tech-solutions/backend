package com.zifiq.account.service

import com.zifiq.account.dto.*
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.tax.HsnSectionsTable
import com.zifiq.common.tax.IsicSectionsTable
import com.zifiq.common.tax.TenantVatCategoriesTable
import com.zifiq.common.tax.VatCategoriesTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.time.Instant
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * VAT Control (sketch/index.html: Finance & Accounting > Tendering & Financial Controls). The
 * three catalogs (VAT categories, HSN sections, ISIC sections) are global and system-seeded --
 * see V5__tax_classification.sql -- never created here. A tenant only ever *activates* a subset
 * of [VatCategoriesTable]; HSN/ISIC sections are plain shared lookups every tenant's products
 * classify against directly, with no per-tenant activation step.
 */
@Service
class TaxClassificationService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
) {
    fun listVatCategories(): List<VatCategorySummary> = tenantScopedTransaction.execute {
        VatCategoriesTable.selectAll().orderBy(VatCategoriesTable.name).map(::rowToVatCategorySummary)
    }

    fun listHsnSections(): List<HsnSectionSummary> = tenantScopedTransaction.execute {
        HsnSectionsTable.selectAll().orderBy(HsnSectionsTable.code).map {
            HsnSectionSummary(
                id = it[HsnSectionsTable.id].value,
                code = it[HsnSectionsTable.code],
                chapterRange = it[HsnSectionsTable.chapterRange],
                name = it[HsnSectionsTable.name],
            )
        }
    }

    fun listIsicSections(): List<IsicSectionSummary> = tenantScopedTransaction.execute {
        IsicSectionsTable.selectAll().orderBy(IsicSectionsTable.code).map {
            IsicSectionSummary(
                id = it[IsicSectionsTable.id].value,
                code = it[IsicSectionsTable.code],
                name = it[IsicSectionsTable.name],
            )
        }
    }

    fun getTenantVatCategories(): List<TenantVatCategorySummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        VatCategoriesTable
            .join(TenantVatCategoriesTable, JoinType.LEFT) {
                (TenantVatCategoriesTable.vatCategoryId eq VatCategoriesTable.id) and
                    (TenantVatCategoriesTable.tenantId eq tenantId)
            }
            .selectAll()
            .orderBy(VatCategoriesTable.name)
            .map { row ->
                TenantVatCategorySummary(
                    vatCategoryId = row[VatCategoriesTable.id].value,
                    code = row[VatCategoriesTable.code],
                    name = row[VatCategoriesTable.name],
                    rate = row[VatCategoriesTable.rate],
                    isActive = row.getOrNull(TenantVatCategoriesTable.isActive) ?: false,
                    activatedAt = row.getOrNull(TenantVatCategoriesTable.activatedAt) ?: Instant.EPOCH,
                )
            }
    }

    /** Replaces the tenant's active VAT category set with exactly the codes given. */
    fun selectTenantVatCategories(request: TenantVatCategorySelectionRequest): List<TenantVatCategorySummary> =
        tenantScopedTransaction.execute {
            val actor = currentActorProvider.current()
            val requestedCodes = request.vatCategoryCodes.map { it.trim().uppercase() }.toSet()

            val matched = VatCategoriesTable.selectAll()
                .andWhere { VatCategoriesTable.code inList requestedCodes }
                .associateBy { it[VatCategoriesTable.code] }

            val unknown = requestedCodes - matched.keys
            if (unknown.isNotEmpty()) throw ApiErrors.unknownVatCategories(unknown)

            val allCategories = VatCategoriesTable.selectAll().toList()
            allCategories.forEach { category ->
                val categoryId = category[VatCategoriesTable.id]
                val shouldBeActive = category[VatCategoriesTable.code] in requestedCodes

                val existing = TenantVatCategoriesTable.selectAll()
                    .andWhere { TenantVatCategoriesTable.tenantId eq actor.tenantId }
                    .andWhere { TenantVatCategoriesTable.vatCategoryId eq categoryId }
                    .firstOrNull()

                if (existing == null) {
                    TenantVatCategoriesTable.insert {
                        it[TenantVatCategoriesTable.tenantId] = actor.tenantId
                        it[TenantVatCategoriesTable.vatCategoryId] = categoryId
                        it[TenantVatCategoriesTable.isActive] = shouldBeActive
                        it[TenantVatCategoriesTable.activatedByUserId] = actor.userId
                        it[TenantVatCategoriesTable.activatedAt] = Instant.now()
                    }
                } else if (existing[TenantVatCategoriesTable.isActive] != shouldBeActive) {
                    TenantVatCategoriesTable.update({
                        (TenantVatCategoriesTable.tenantId eq actor.tenantId) and
                            (TenantVatCategoriesTable.vatCategoryId eq categoryId)
                    }) {
                        it[TenantVatCategoriesTable.isActive] = shouldBeActive
                        it[TenantVatCategoriesTable.activatedByUserId] = actor.userId
                        it[TenantVatCategoriesTable.activatedAt] = Instant.now()
                    }
                }
            }

            getTenantVatCategories()
        }

    private fun rowToVatCategorySummary(row: org.jetbrains.exposed.sql.ResultRow) = VatCategorySummary(
        id = row[VatCategoriesTable.id].value,
        code = row[VatCategoriesTable.code],
        name = row[VatCategoriesTable.name],
        rate = row[VatCategoriesTable.rate],
        inputVatRecoverable = row[VatCategoriesTable.inputVatRecoverable],
        description = row[VatCategoriesTable.description],
        nrsTaxCategoryCode = row[VatCategoriesTable.nrsTaxCategoryCode],
    )
}
