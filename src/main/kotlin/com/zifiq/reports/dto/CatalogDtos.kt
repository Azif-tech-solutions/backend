package com.zifiq.reports.dto

import com.zifiq.platform.reporting.ReportParameter

/** One catalogued report (docs/13 §4.1). */
data class CatalogReportEntry(
    val key: String,
    val title: String,
    val description: String,
    val screenPath: String?,
    val endpoint: String,
    val stream: String,
    val parameters: List<ReportParameter>,
    val formats: List<String>,
)

data class CatalogStream(val key: String, val title: String, val reports: List<CatalogReportEntry>)

data class ReportCatalog(val streams: List<CatalogStream>)
