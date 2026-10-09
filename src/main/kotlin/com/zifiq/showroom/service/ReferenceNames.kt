package com.zifiq.showroom.service

import java.util.UUID
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.springframework.stereotype.Component

/**
 * Reads Factory Setup's registers (suppliers, manufacturers, units) and the tax catalogue by plain
 * SQL: Showroom must not depend on the factory module's Kotlin types (docs/03 §2), and it only
 * needs names and existence. RLS scopes every query to the caller's tenant.
 */
@Component
class ReferenceNames {
    private fun single(sql: String, id: UUID): String? =
        TransactionManager.current().exec(sql, listOf(UUIDColumnType() to id)) { rs -> if (rs.next()) rs.getString(1) else null }

    /** Active suppliers only: an inactive supplier can't be used on new entries. */
    fun activeSupplierName(id: UUID): String? = single("select company_name from suppliers where id = ? and status = 'ACTIVE'", id)
    fun supplierName(id: UUID): String? = single("select company_name from suppliers where id = ?", id)
    fun manufacturerName(id: UUID): String? = single("select name from manufacturers where id = ?", id)
    fun unitName(id: UUID): String? = single("select name from units_of_measure where id = ?", id)

    /** The tenant's active standard-rate VAT category (the default for direct-entry goods). */
    fun activeStandardVatCategory(): UUID? =
        TransactionManager.current().exec(
            "select vc.id from vat_categories vc join tenant_vat_categories t on t.vat_category_id = vc.id " +
                "where vc.code = 'STANDARD_RATE' and t.is_active",
        ) { rs -> if (rs.next()) UUID.fromString(rs.getString(1)) else null }
}
