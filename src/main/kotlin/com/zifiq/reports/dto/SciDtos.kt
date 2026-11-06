package com.zifiq.reports.dto

import java.math.BigDecimal
import java.time.LocalDate

/** One SCI line, with a value per column (current, then comparison). A null value means "hidden for your role". */
data class SciLine(
    val label: String,
    val values: List<BigDecimal?>,
    val changePct: BigDecimal? = null,
    val note: String? = null,
)

data class SciSection(
    val title: String,
    val cost: Boolean = false,
    val lines: List<SciLine>,
    val subtotal: SciLine? = null,
)

data class SciPeriod(val from: LocalDate, val to: LocalDate, val basis: String? = null)

data class SciResult(
    val scope: String,
    val period: SciPeriod,
    val comparison: SciPeriod?,
    val columns: List<String>,
    val sections: List<SciSection>,
    val result: SciLine,
    val stats: Map<String, Any?>,
    val basisOfPreparation: String,
    val costVisible: Boolean,
)
