package com.zifiq.common.tax

import java.util.UUID
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/**
 * Existence/activation checks against the global tax-classification catalogs, shared by every
 * module that needs to validate a vat_category_id/hsn_section_id/isic_section_id it was handed
 * (currently com.zifiq.inventory when registering a stock item, and com.zifiq.account's VAT
 * Control itself). Assumes it's called from within an existing TenantScopedTransaction, the same
 * convention as com.zifiq.inventory.repository.StockLedgerRepository.
 */
@Component
class TaxClassificationRepository {
    fun hsnSectionExists(id: UUID): Boolean =
        HsnSectionsTable.selectAll().andWhere { HsnSectionsTable.id eq id }.any()

    fun isicSectionExists(id: UUID): Boolean =
        IsicSectionsTable.selectAll().andWhere { IsicSectionsTable.id eq id }.any()

    fun isVatCategoryActiveForTenant(tenantId: UUID, vatCategoryId: UUID): Boolean =
        TenantVatCategoriesTable.selectAll()
            .andWhere { TenantVatCategoriesTable.tenantId eq tenantId }
            .andWhere { TenantVatCategoriesTable.vatCategoryId eq vatCategoryId }
            .andWhere { TenantVatCategoriesTable.isActive eq true }
            .any()
}
