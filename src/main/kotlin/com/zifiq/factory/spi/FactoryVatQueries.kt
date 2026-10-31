package com.zifiq.factory.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One delivered special-production run's derived output VAT line, for Audit's VAT report (docs/10 §4.6).
 * The caller applies the location's VAT rate to [base]; the Factory module only knows the agreed price.
 */
data class SpecialProductionVatLineRef(
    val date: LocalDate,
    val ref: String,
    val clientName: String,
    /** The agreed sale price (VAT-exclusive base) for the run. */
    val base: BigDecimal,
)

/** The Factory module's VAT-relevant published reads (special production), for Audit & Compliance. */
interface FactoryVatQueries {
    /** DELIVERED special-production runs at [locationId] created in [from]..[to] (derived output VAT). */
    fun specialProductionDeliveries(locationId: UUID, from: LocalDate, to: LocalDate): List<SpecialProductionVatLineRef>
}
