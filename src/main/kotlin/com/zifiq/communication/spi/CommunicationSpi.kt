package com.zifiq.communication.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Which allocated commission to total (docs/12 §5). */
data class CommissionFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val beneficiaryType: String? = null,
    val locationId: UUID? = null,
)

/** Commission allocated for one approved-run period (earned, whether or not it is paid). */
data class CommissionTotal(
    val periodMonth: LocalDate,
    val beneficiaryType: String,
    val locationId: UUID?,
    val earned: BigDecimal,
    val paid: BigDecimal,
)

/**
 * The Communication module's published read interface (docs/12 §5): commission allocated per period,
 * for Reports (SCI), the Dashboard and AI. Read-only; runs in the caller's transaction.
 */
interface CommissionQueries {
    fun allocatedFor(filter: CommissionFilter): List<CommissionTotal>
}
