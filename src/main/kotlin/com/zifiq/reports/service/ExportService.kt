package com.zifiq.reports.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.notifications.NotificationChannelType
import com.zifiq.platform.notifications.model.NotificationOutboxTable
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.reporting.ReportColumn
import com.zifiq.platform.reporting.ReportColumnType
import com.zifiq.platform.reporting.ReportProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.model.ReportExportFilesTable
import com.zifiq.reports.model.ReportExportsTable
import com.zifiq.settings.spi.CompanyProfileProvider
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * The export engine (docs/13 §4.6): runs a report provider and writes CSV, XLSX or PDF. It runs inside
 * a tenant-scoped transaction with the caller as actor, so the provider's own permission, location
 * scope and cost masking apply — an export is exactly what the user sees on screen. Small exports run
 * synchronously; larger ones are QUEUED and produced by [ExportQueueWorker] re-running the report as
 * the requesting user (so the stored file reflects that user's permissions), then downloaded by ref.
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

    /** The outcome of requesting an export: a ready file (ran synchronously) or a queued ref (produced in the background). */
    sealed interface Outcome {
        data class Ready(val file: ExportFile) : Outcome
        data class Queued(val ref: String) : Outcome
    }

    /** One row the queue worker claimed, with the user to impersonate when producing it. */
    data class Claim(val exportId: UUID, val requesterId: UUID)

    /**
     * Requests an export. When [wait] and the provider's estimated row count is within the sync limit,
     * it is produced now and the file returned; otherwise it is recorded QUEUED for the worker.
     */
    fun export(reportKey: String, params: Map<String, Any?>, format: String, wait: Boolean = true): Outcome = tx.execute {
        val (provider, fmt) = validate(reportKey, format)
        val exportRef = sequences.next("RPT")
        val runNow = wait && provider.estimateRows(params) <= support.settings().int(ReportsSettings.SYNC_EXPORT_ROW_LIMIT).toLong()
        if (runNow) {
            val costVisible = support.costVisible()
            val (file, count) = renderFile(provider, params, fmt, costVisible)
            ReportExportsTable.insert {
                it[tenantId] = support.tenantId(); it[ref] = exportRef; it[ReportExportsTable.reportKey] = reportKey; it[ReportExportsTable.params] = params
                it[ReportExportsTable.format] = fmt; it[status] = "DONE"; it[rowCount] = count; it[ReportExportsTable.fileName] = file.fileName
                it[ReportExportsTable.contentType] = file.contentType; it[sizeBytes] = file.bytes.size.toLong(); it[requestedByUserId] = support.userId()
                it[requestedAt] = Instant.now(); it[finishedAt] = Instant.now()
            }
            Outcome.Ready(file)
        } else {
            ReportExportsTable.insert {
                it[tenantId] = support.tenantId(); it[ref] = exportRef; it[ReportExportsTable.reportKey] = reportKey; it[ReportExportsTable.params] = params
                it[ReportExportsTable.format] = fmt; it[status] = "QUEUED"; it[requestedByUserId] = support.userId(); it[requestedAt] = Instant.now()
            }
            Outcome.Queued(exportRef)
        }
    }

    /** Produces an export inline regardless of size and records it DONE (screen export buttons, internal callers). */
    fun exportSync(reportKey: String, params: Map<String, Any?>, format: String): ExportFile = tx.execute {
        val (provider, fmt) = validate(reportKey, format)
        val (file, count) = renderFile(provider, params, fmt, support.costVisible())
        val exportRef = sequences.next("RPT")
        ReportExportsTable.insert {
            it[tenantId] = support.tenantId(); it[ref] = exportRef; it[ReportExportsTable.reportKey] = reportKey; it[ReportExportsTable.params] = params
            it[ReportExportsTable.format] = fmt; it[status] = "DONE"; it[rowCount] = count; it[ReportExportsTable.fileName] = file.fileName
            it[ReportExportsTable.contentType] = file.contentType; it[sizeBytes] = file.bytes.size.toLong(); it[requestedByUserId] = support.userId()
            it[requestedAt] = Instant.now(); it[finishedAt] = Instant.now()
        }
        file
    }

    /** The stored file of a finished export, for its requester (or an Administrator). */
    fun download(ref: String): ExportFile = tx.execute {
        val row = ReportExportsTable.selectAll().where { (ReportExportsTable.tenantId eq support.tenantId()) and (ReportExportsTable.ref eq ref) }
            .firstOrNull() ?: throw ApiErrors.exportNotFound()
        if (row[ReportExportsTable.requestedByUserId].value != support.userId() && !support.can("iam.users.manage")) throw ApiErrors.accessDenied()
        if (row[ReportExportsTable.status] != "DONE") throw ApiErrors.exportNotFound()
        row[ReportExportsTable.expiresAt]?.let { if (it.isBefore(Instant.now())) throw ApiErrors.exportExpired() }
        val content = ReportExportFilesTable.select(ReportExportFilesTable.content)
            .where { ReportExportFilesTable.exportId eq row[ReportExportsTable.id].value }
            .firstOrNull()?.get(ReportExportFilesTable.content) ?: throw ApiErrors.exportExpired()
        ExportFile(row[ReportExportsTable.fileName] ?: "export", row[ReportExportsTable.contentType] ?: "application/octet-stream", content)
    }

    /** Claims up to [limit] QUEUED exports for this tenant (guarded QUEUED→PROCESSING update) — called by the worker. */
    fun claimQueued(limit: Int): List<Claim> {
        val candidates = ReportExportsTable
            .select(ReportExportsTable.id, ReportExportsTable.requestedByUserId)
            .where { (ReportExportsTable.tenantId eq support.tenantId()) and (ReportExportsTable.status eq "QUEUED") }
            .orderBy(ReportExportsTable.requestedAt to SortOrder.ASC)
            .limit(limit)
            .map { it[ReportExportsTable.id].value to it[ReportExportsTable.requestedByUserId].value }
        return candidates.mapNotNull { (id, requester) ->
            val claimed = ReportExportsTable.update({ (ReportExportsTable.id eq id) and (ReportExportsTable.status eq "QUEUED") }) {
                it[status] = "RUNNING"; it[startedAt] = Instant.now()
            }
            if (claimed == 1) Claim(id, requester) else null
        }
    }

    /** Produces a claimed export's file and marks it DONE (or FAILED). Runs with the requester impersonated by the worker. */
    fun complete(exportId: UUID) = tx.execute {
        val row = ReportExportsTable.selectAll().where { ReportExportsTable.id eq exportId }.single()
        val ref = row[ReportExportsTable.ref]
        try {
            val provider = byKey[row[ReportExportsTable.reportKey]] ?: throw ApiErrors.reportNotFound()
            if (!provider.canRun()) throw ApiErrors.accessDenied()
            val fmt = row[ReportExportsTable.format]
            val (file, count) = renderFile(provider, row[ReportExportsTable.params], fmt, support.costVisible())
            ReportExportFilesTable.insert { it[ReportExportFilesTable.exportId] = exportId; it[tenantId] = support.tenantId(); it[content] = file.bytes }
            val expires = Instant.now().plus(support.settings().int(ReportsSettings.EXPORT_RETENTION_DAYS).toLong(), ChronoUnit.DAYS)
            ReportExportsTable.update({ ReportExportsTable.id eq exportId }) {
                it[status] = "DONE"; it[rowCount] = count; it[ReportExportsTable.fileName] = file.fileName; it[ReportExportsTable.contentType] = file.contentType
                it[sizeBytes] = file.bytes.size.toLong(); it[finishedAt] = Instant.now(); it[expiresAt] = expires
            }
            notifyReady(row[ReportExportsTable.requestedByUserId].value, ref)
        } catch (e: Exception) {
            ReportExportsTable.update({ ReportExportsTable.id eq exportId }) {
                it[status] = "FAILED"; it[errorMessage] = (e.message ?: e.javaClass.simpleName).take(500); it[finishedAt] = Instant.now()
            }
        }
    }

    private fun notifyReady(requesterId: UUID, ref: String) {
        NotificationOutboxTable.insert {
            it[tenantId] = support.tenantId(); it[channel] = NotificationChannelType.PUSH.name; it[recipient] = requesterId.toString()
            it[subject] = "Your export is ready"; it[body] = "Export $ref is ready to download."; it[metadata] = mapOf("ref" to ref, "kind" to "REPORT_EXPORT")
            it[createdAt] = Instant.now()
        }
    }

    private fun validate(reportKey: String, format: String): Pair<ReportProvider, String> {
        val provider = byKey[reportKey] ?: throw ApiErrors.reportNotFound()
        if (!provider.canRun()) throw ApiErrors.accessDenied()
        val fmt = format.trim().uppercase()
        if (fmt !in setOf("CSV", "XLSX", "PDF")) throw ApiErrors.formatNotSupported(fmt)
        if (fmt == "PDF" && !provider.supportsPdf) throw ApiErrors.formatNotSupported(fmt)
        return provider to fmt
    }

    private fun renderFile(provider: ReportProvider, params: Map<String, Any?>, fmt: String, costVisible: Boolean): Pair<ExportFile, Int> {
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
        return ExportFile("${provider.title}.$ext", contentType, bytes) to count
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
        "errorMessage" to r[ReportExportsTable.errorMessage],
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
