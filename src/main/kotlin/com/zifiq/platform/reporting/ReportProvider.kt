package com.zifiq.platform.reporting

/** The 14 reporting streams the sketch defines (docs/13 §1.1). */
enum class ReportStream(val title: String) {
    SALES("Sales"), STOCK("Stock"), FACTORY("Factory"), SHOWROOM("Showroom"), FINANCE("Finance"), CUSTOMERS("Customers"),
    RETURNS("Returns"), TRANSFERS("Transfers"), SCI("SCI"), VAT("VAT"), PENDING_SUPPLY("Pending Supply"),
    CORRESPONDENT("Correspondent"), COMMISSION("Commission"), ADJUSTMENTS("Adjustments"),
}

enum class ReportColumnType { TEXT, MONEY, QTY, DATE, PCT }
enum class ReportParameterType { DATE, DATE_RANGE, LOCATION, ITEM, CUSTOMER, ENUM, TEXT }

/** A column of a report: [cost] columns are blanked for callers without the report's cost permission. */
data class ReportColumn(val key: String, val label: String, val type: ReportColumnType, val cost: Boolean = false)

/** A declared filter of a report, for the catalogue and export validation. */
data class ReportParameter(
    val name: String,
    val type: ReportParameterType,
    val required: Boolean = false,
    val label: String = name,
    val options: List<String> = emptyList(),
)

/** A total/stat line printed above the table (and on exports). */
data class ReportSummaryLine(val label: String, val value: Any?)

/**
 * A reporting data source each module implements for its own reports (docs/13 §3), discovered as a
 * Spring bean by the Reports module — dependency inversion, like `EventConsumer` (doc 03 §7.6), so
 * platform/Reports never depend on business modules. Every method runs inside a tenant-scoped
 * transaction opened by the export engine with the requesting (or schedule-owner) user as the current
 * actor, so the provider's own `LocationAccessService` / `CostVisibility` / `PermissionAuthorizer`
 * behave exactly as they do on the screen. An export is therefore exactly what the user sees.
 */
interface ReportProvider {
    /** '<module>.<report>', unique. */
    val key: String
    val stream: ReportStream
    val title: String
    val description: String
    /** Frontend route, for the catalogue link. */
    val screenPath: String?
    /** The module's JSON endpoint (documentation/link). */
    val endpoint: String
    val parameters: List<ReportParameter>
    val supportsPdf: Boolean

    /**
     * The single permission a recipient must hold to be scheduled this report (docs/13 §4.7). Null when
     * eligibility can't be reduced to one code — a schedule then only requires active membership.
     */
    val permission: String? get() = null

    /** The report's own permission check, against the ambient actor. */
    fun canRun(): Boolean

    fun columns(params: Map<String, Any?>, costVisible: Boolean): List<ReportColumn>

    /** Streams rows (never loads everything into memory); applies location scope and cost masking itself. */
    fun rows(params: Map<String, Any?>, sink: (Map<String, Any?>) -> Unit)

    fun summary(params: Map<String, Any?>): List<ReportSummaryLine> = emptyList()

    /** A cheap count used to choose synchronous vs queued export. */
    fun estimateRows(params: Map<String, Any?>): Long
}
