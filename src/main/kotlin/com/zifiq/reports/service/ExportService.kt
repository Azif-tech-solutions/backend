package com.zifiq.reports.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.reporting.ReportColumn
import com.zifiq.platform.reporting.ReportColumnType
import com.zifiq.platform.reporting.ReportProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.model.ReportExportsTable
import com.zifiq.settings.spi.CompanyProfileProvider
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * The export engine (docs/13 §4.6): runs a report provider and writes CSV or XLSX. It runs inside a
 * tenant-scoped transaction with the caller as actor, so the provider's own permission, location
 * scope and cost masking apply — an export is exactly what the user sees on screen. (Queued/async
 * exports and PDF arrive in a later slice; this slice produces files synchronously.)
 */
@Service
class ExportService(
    private val support: ReportsSupport,
    private val tx: TenantScopedTransaction,
    private val sequences: DocumentSequenceService,
    private val pdfRenderer: PdfRenderer,
    private val companyProfile: CompanyProfileProvider,
    providers: List<ReportProvider>,
) {
    private val byKey = providers.associateBy { it.key }

    data class ExportFile(val fileName: String, val contentType: String, val bytes: ByteArray)

    fun exportSync(reportKey: String, params: Map<String, Any?>, format: String): ExportFile = tx.execute {
        val provider = byKey[reportKey] ?: throw ApiErrors.reportNotFound()
        if (!provider.canRun()) throw ApiErrors.accessDenied()
        val fmt = format.trim().uppercase()
        if (fmt !in setOf("CSV", "XLSX", "PDF")) throw ApiErrors.formatNotSupported(fmt)
        if (fmt == "PDF" && !provider.supportsPdf) throw ApiErrors.formatNotSupported(fmt)
        val costVisible = support.costVisible()
        val columns = provider.columns(params, costVisible)
        var count = 0
        val bytes = when (fmt) {
            "CSV" -> csv(provider, params, columns) { count++ }
            "XLSX" -> xlsx(provider, params, columns) { count++ }
            else -> pdf(provider, params, columns, costVisible) { count++ }
        }
        val (contentType, ext) = when (fmt) {
            "CSV" -> "text/csv" to "csv"
            "XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx"
            else -> "application/pdf" to "pdf"
        }
        val fileName = "${provider.title}.$ext"
        val exportRef = sequences.next("RPT")
        ReportExportsTable.insert {
            it[tenantId] = support.tenantId(); it[ref] = exportRef; it[ReportExportsTable.reportKey] = reportKey; it[ReportExportsTable.params] = params
            it[ReportExportsTable.format] = fmt; it[status] = "DONE"; it[rowCount] = count; it[ReportExportsTable.fileName] = fileName
            it[ReportExportsTable.contentType] = contentType; it[sizeBytes] = bytes.size.toLong(); it[requestedByUserId] = support.userId()
            it[requestedAt] = Instant.now(); it[finishedAt] = Instant.now()
        }
        ExportFile(fileName, contentType, bytes)
    }

    fun list(all: Boolean, paging: PageRequest): PageResult<Map<String, Any?>> = tx.execute {
        var cond = ReportExportsTable.tenantId eq support.tenantId()
        if (!(all && support.can("iam.users.manage"))) cond = cond and (ReportExportsTable.requestedByUserId eq support.userId())
        Paging.fetch(ReportExportsTable.selectAll().where(cond).orderBy(ReportExportsTable.requestedAt to SortOrder.DESC), paging) { toRow(it) }
    }

    private fun toRow(r: ResultRow): Map<String, Any?> = mapOf(
        "ref" to r[ReportExportsTable.ref], "reportKey" to r[ReportExportsTable.reportKey], "format" to r[ReportExportsTable.format],
        "status" to r[ReportExportsTable.status], "rowCount" to r[ReportExportsTable.rowCount], "sizeBytes" to r[ReportExportsTable.sizeBytes],
        "requestedBy" to UserRef.of(r[ReportExportsTable.requestedByUserId].value), "requestedAt" to r[ReportExportsTable.requestedAt],
        "finishedAt" to r[ReportExportsTable.finishedAt], "expiresAt" to r[ReportExportsTable.expiresAt],
    )

    private fun csv(provider: ReportProvider, params: Map<String, Any?>, columns: List<ReportColumn>, onRow: () -> Unit): ByteArray {
        val writer = StringWriter()
        CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(*columns.map { it.label }.toTypedArray()).build()).use { printer ->
            provider.rows(params) { row ->
                printer.printRecord(columns.map { row[it.key]?.toString() ?: "" })
                onRow()
            }
        }
        return writer.toString().toByteArray(Charsets.UTF_8)
    }

    private fun xlsx(provider: ReportProvider, params: Map<String, Any?>, columns: List<ReportColumn>, onRow: () -> Unit): ByteArray {
        val wb = SXSSFWorkbook(100)
        try {
            val sheet = wb.createSheet("Report")
            var r = 0
            sheet.createRow(r++).createCell(0).setCellValue(provider.title)
            provider.summary(params).forEach { s ->
                val row = sheet.createRow(r++)
                row.createCell(0).setCellValue(s.label)
                setCell(row.createCell(1), s.value, ReportColumnType.TEXT)
            }
            val header = sheet.createRow(r++)
            columns.forEachIndexed { i, c -> header.createCell(i).setCellValue(c.label) }
            provider.rows(params) { row ->
                val dataRow = sheet.createRow(r++)
                columns.forEachIndexed { i, c -> setCell(dataRow.createCell(i), row[c.key], c.type) }
                onRow()
            }
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        } finally {
            wb.dispose()
        }
    }

    private fun pdf(provider: ReportProvider, params: Map<String, Any?>, columns: List<ReportColumn>, costVisible: Boolean, onRow: () -> Unit): ByteArray {
        val numeric = setOf(ReportColumnType.MONEY, ReportColumnType.QTY, ReportColumnType.PCT)
        val rows = ArrayList<List<Map<String, Any?>>>()
        provider.rows(params) { row ->
            rows += columns.map { c -> mapOf("value" to (row[c.key]?.toString() ?: ""), "num" to (c.type in numeric)) }
            onRow()
        }
        val company = companyProfile.get()
        val filters = params.filterValues { it != null && it.toString().isNotBlank() }.map { (k, v) -> mapOf("label" to k, "value" to v.toString()) }
        val model = mapOf(
            "title" to provider.title,
            "generatedAt" to "Generated " + ZonedDateTime.now(support.zone()).format(GENERATED_FMT),
            "company" to company,
            "logo" to company.logoDataUri(),
            "footer" to company.documentFooter,
            "filters" to filters,
            "note" to if (costVisible) null else "Cost columns hidden for your role",
            "summaries" to provider.summary(params).map { mapOf("label" to it.label, "value" to it.value?.toString()) },
            "columns" to columns.map { mapOf("label" to it.label, "num" to (it.type in numeric)) },
            "rows" to rows,
        )
        return pdfRenderer.render("report", model)
    }

    private fun setCell(cell: org.apache.poi.ss.usermodel.Cell, value: Any?, type: ReportColumnType) {
        if (value == null) return
        when (type) {
            ReportColumnType.MONEY, ReportColumnType.QTY, ReportColumnType.PCT ->
                when (value) {
                    is BigDecimal -> cell.setCellValue(value.toDouble())
                    is Number -> cell.setCellValue(value.toDouble())
                    else -> cell.setCellValue(value.toString())
                }
            else -> cell.setCellValue(value.toString())
        }
    }

    companion object {
        private val GENERATED_FMT = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
    }
}
