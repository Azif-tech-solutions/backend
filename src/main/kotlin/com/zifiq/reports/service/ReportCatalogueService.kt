package com.zifiq.reports.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.reports.dto.CatalogReportEntry
import com.zifiq.reports.dto.CatalogStream
import com.zifiq.reports.dto.ReportCatalog
import com.zifiq.platform.reporting.ReportProvider
import com.zifiq.platform.reporting.ReportStream
import com.zifiq.platform.tenancy.TenantScopedTransaction
import org.springframework.stereotype.Service

/**
 * The report catalogue (docs/13 §4.1): every provider the caller may run, grouped into the 14 streams
 * in the sketch's order. Providers are discovered as Spring beans; each applies its own permission.
 */
@Service
class ReportCatalogueService(
    private val providers: List<ReportProvider>,
    private val tx: TenantScopedTransaction,
) {
    fun catalog(q: String?): ReportCatalog = tx.execute {
        val term = q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val runnable = providers.filter { it.canRun() }
            .filter { term == null || it.title.lowercase().contains(term) || it.description.lowercase().contains(term) }
        val streams = ReportStream.entries.mapNotNull { stream ->
            val reports = runnable.filter { it.stream == stream }.sortedBy { it.title }.map(::toEntry)
            if (reports.isEmpty()) null else CatalogStream(stream.name, stream.title, reports)
        }
        ReportCatalog(streams)
    }

    fun entry(reportKey: String): CatalogReportEntry = tx.execute {
        val provider = providers.firstOrNull { it.key == reportKey } ?: throw ApiErrors.reportNotFound()
        if (!provider.canRun()) throw ApiErrors.reportNotFound()
        toEntry(provider)
    }

    private fun toEntry(p: ReportProvider) = CatalogReportEntry(
        key = p.key, title = p.title, description = p.description, screenPath = p.screenPath, endpoint = p.endpoint,
        stream = p.stream.name, parameters = p.parameters, formats = listOf("CSV", "XLSX") + if (p.supportsPdf) listOf("PDF") else emptyList(),
    )
}
