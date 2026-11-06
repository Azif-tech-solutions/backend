package com.zifiq.reports.service

import com.zifiq.platform.reporting.ReportProvider
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import java.text.Normalizer
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Global search over the report catalogue (docs/13 §10): typing "vat return", "P&L" or "bin card" in
 * the header search jumps to the report. Only reports the caller can run are returned. Matching is
 * in-memory over the catalogue (accent-insensitive, with a few synonyms), since the catalogue is a
 * small bean set, not a table.
 */
@Component
class ReportSearchProvider(
    private val providers: List<ReportProvider>,
) : SearchProvider {
    override val type = "report"
    override val label = "Reports"
    override val listRoute = "/reports"

    override fun canSearch() = true

    override fun search(q: String, limit: Int): List<SearchHit> {
        val terms = expand(normalize(q)).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return providers.asSequence()
            .filter { it.canRun() }
            .mapNotNull { p ->
                val haystack = normalize("${p.title} ${p.description} ${p.stream.title} ${p.key}")
                if (terms.any { haystack.contains(it) }) p else null
            }
            .sortedBy { it.title }
            .take(limit)
            .map { p ->
                SearchHit(
                    type = type,
                    id = UUID.nameUUIDFromBytes(p.key.toByteArray()),
                    title = p.title,
                    subtitle = p.stream.title,
                    ref = p.key,
                    route = p.screenPath ?: p.endpoint,
                )
            }
            .toList()
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim()

    /** A few report-name synonyms so "profit"/"P&L" find the SCI, etc. (docs/13 §10). */
    private fun expand(q: String): List<String> {
        val extra = SYNONYMS.filterKeys { q.contains(it) }.values.flatten()
        return (listOf(q) + extra).distinct()
    }

    companion object {
        private val DIACRITICS = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        private val SYNONYMS = mapOf(
            "p&l" to listOf("income", "sci"),
            "pnl" to listOf("income", "sci"),
            "profit" to listOf("income", "sci"),
            "income statement" to listOf("income", "sci"),
            "debtors" to listOf("receivable"),
            "receivables" to listOf("receivable"),
            "bin card" to listOf("stock"),
            "stock card" to listOf("stock"),
        )
    }
}
