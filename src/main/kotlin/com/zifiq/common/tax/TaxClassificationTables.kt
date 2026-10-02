package com.zifiq.common.tax

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.TenantsTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Global, system-seeded tax/trade classification catalogs -- never tenant-created, the same
 * pattern as [com.zifiq.platform.accesscontrol.model.PermissionCatalogTable]. See
 * V5__tax_classification.sql for the seeded rows and the scope note on HSN/ISIC granularity.
 *
 * Lives in `common` (an OPEN Spring Modulith module) rather than `account`, even though the VAT
 * Control *workflow* (com.zifiq.account.service.TaxClassificationService, the HTTP API tenants use
 * to activate categories) is account-owned per sketch/index.html. Every stock item (in `inventory`,
 * itself OPEN) must carry a vat_category_id/hsn_section_id/isic_section_id, and inventory must not
 * depend on the account business module directly -- the same "never depend on a peer business
 * module" rule that keeps factory and showroom from depending on each other. Putting the shared
 * catalogs here, where every module can already see them, resolves that without inverting any
 * dependency direction.
 */
object VatCategoriesTable : UUIDTable("vat_categories") {
    val code = varchar("code", 30).uniqueIndex()
    val name = varchar("name", 100)
    val rate = decimal("rate", 5, 2)
    val inputVatRecoverable = bool("input_vat_recoverable")
    val description = varchar("description", 600)
    /** The matching [NrsTaxCategoriesTable] code -- what actually goes on a submitted NRS invoice. */
    val nrsTaxCategoryCode = varchar("nrs_tax_category_code", 40).references(NrsTaxCategoriesTable.code)
}

object HsnSectionsTable : UUIDTable("hsn_sections") {
    val code = varchar("code", 6).uniqueIndex()
    val chapterRange = varchar("chapter_range", 20)
    val name = varchar("name", 200)
}

object IsicSectionsTable : UUIDTable("isic_sections") {
    val code = varchar("code", 1).uniqueIndex()
    val name = varchar("name", 200)
}

/** A tenant's activated subset of [VatCategoriesTable]. */
object TenantVatCategoriesTable : Table("tenant_vat_categories") {
    val tenantId = reference("tenant_id", TenantsTable)
    val vatCategoryId = reference("vat_category_id", VatCategoriesTable)
    val isActive = bool("is_active")
    val activatedByUserId = reference("activated_by_user_id", AppUsersTable)
    val activatedAt = timestamp("activated_at")

    override val primaryKey = PrimaryKey(tenantId, vatCategoryId)
}
