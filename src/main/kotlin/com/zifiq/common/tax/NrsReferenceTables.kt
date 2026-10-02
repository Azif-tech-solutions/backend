package com.zifiq.common.tax

import org.jetbrains.exposed.sql.Table

/**
 * Global, system-seeded NRS (Nigeria Revenue Service) e-invoicing reference data -- the exact
 * code/name/description values NRS's real UBL-based "Invoice Schema" API
 * (POST base_url/api/v1/invoice/validate) expects on a submitted invoice. See
 * V6__nrs_reference_data.sql for the seeded rows and the scope note on which lists are partial.
 *
 * Lives alongside [VatCategoriesTable]/[HsnSectionsTable]/[IsicSectionsTable] in `common` (OPEN)
 * for the same reason: this is shared reference data every business module may eventually need
 * (invoicing today, potentially cashier/account later), not something owned by one business
 * module.
 */
object NrsTaxCategoriesTable : Table("nrs_tax_categories") {
    val code = varchar("code", 40)
    val name = varchar("name", 120)

    override val primaryKey = PrimaryKey(code)
}

object NrsInvoiceTypeCodesTable : Table("nrs_invoice_type_codes") {
    val code = varchar("code", 3)
    val name = varchar("name", 80)
    val description = varchar("description", 200)

    override val primaryKey = PrimaryKey(code)
}

object NrsPaymentMeansCodesTable : Table("nrs_payment_means_codes") {
    val code = varchar("code", 3)
    val name = varchar("name", 80)
    val description = varchar("description", 200)

    override val primaryKey = PrimaryKey(code)
}

object NrsUnitOfMeasureCodesTable : Table("nrs_unit_of_measure_codes") {
    val code = varchar("code", 10)
    val name = varchar("name", 120)
    val description = varchar("description", 300)

    override val primaryKey = PrimaryKey(code)
}

/** True HS subheading granularity (e.g. "1006.30"), grouped under [HsnSectionsTable]. */
object HsnCodesTable : Table("hsn_codes") {
    val code = varchar("code", 10)
    val sectionCode = varchar("section_code", 6).references(HsnSectionsTable.code)
    val description = varchar("description", 500)

    override val primaryKey = PrimaryKey(code)
}

/** True ISIC Rev.4 4-digit class granularity, grouped under [IsicSectionsTable]. */
object IsicClassesTable : Table("isic_classes") {
    val code = varchar("code", 4)
    val sectionCode = varchar("section_code", 1).references(IsicSectionsTable.code)
    val description = varchar("description", 300)

    override val primaryKey = PrimaryKey(code)
}

object NigeriaStatesTable : Table("nigeria_states") {
    val code = varchar("code", 5)
    val name = varchar("name", 40)

    override val primaryKey = PrimaryKey(code)
}

object NigeriaLgasTable : Table("nigeria_lgas") {
    val code = varchar("code", 10)
    val stateCode = varchar("state_code", 5).references(NigeriaStatesTable.code)
    val name = varchar("name", 80)

    override val primaryKey = PrimaryKey(code)
}
