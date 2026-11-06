package com.zifiq.inventory.reports

import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.reporting.ReportColumn
import com.zifiq.platform.reporting.ReportColumnType
import com.zifiq.platform.reporting.ReportParameter
import com.zifiq.platform.reporting.ReportParameterType
import com.zifiq.platform.reporting.ReportProvider
import com.zifiq.platform.reporting.ReportStream
import com.zifiq.platform.reporting.ReportSummaryLine
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/**
 * Reports provider for the stock register (docs/13 §3): the product master, streamed for the Reports
 * export engine. Runs in the tenant-scoped transaction the engine opens with the caller as actor, so
 * it never opens its own and enforces the owning module's `inventory.stock.read` permission.
 */
@Component
class StockRegisterReportProvider(
    private val actors: CurrentActorProvider,
    private val permissions: PermissionAuthorizer,
) : ReportProvider {
    override val key = "inventory.stock-register"
    override val stream = ReportStream.STOCK
    override val title = "Stock Register"
    override val description = "Every registered product with its code, type and status."
    override val screenPath = "/inventory/stock-register"
    override val endpoint = "/api/v1/inventory/stock-items"
    override val supportsPdf = true
    override val parameters = listOf(
        ReportParameter("status", ReportParameterType.ENUM, required = false, label = "Status", options = listOf("ACTIVE", "SUSPENDED", "DELETED")),
    )

    override fun canRun(): Boolean = permissions.hasPermission(actors.current().userId, "inventory.stock.read")

    override fun columns(params: Map<String, Any?>, costVisible: Boolean): List<ReportColumn> = listOf(
        ReportColumn("code", "Code", ReportColumnType.TEXT),
        ReportColumn("name", "Product", ReportColumnType.TEXT),
        ReportColumn("itemType", "Type", ReportColumnType.TEXT),
        ReportColumn("barcode", "Barcode", ReportColumnType.TEXT),
        ReportColumn("status", "Status", ReportColumnType.TEXT),
    )

    override fun rows(params: Map<String, Any?>, sink: (Map<String, Any?>) -> Unit) {
        val query = StockItemsTable.select(StockItemsTable.code, StockItemsTable.name, StockItemsTable.itemType, StockItemsTable.barcode, StockItemsTable.status)
            .andWhere { StockItemsTable.tenantId eq actors.current().tenantId }
        (params["status"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { s -> query.andWhere { StockItemsTable.status eq s } }
        query.orderBy(StockItemsTable.name to SortOrder.ASC).forEach {
            sink(
                mapOf(
                    "code" to it[StockItemsTable.code], "name" to it[StockItemsTable.name], "itemType" to it[StockItemsTable.itemType],
                    "barcode" to it[StockItemsTable.barcode], "status" to it[StockItemsTable.status],
                ),
            )
        }
    }

    override fun summary(params: Map<String, Any?>): List<ReportSummaryLine> =
        listOf(ReportSummaryLine("Products", estimateRows(params)))

    override fun estimateRows(params: Map<String, Any?>): Long =
        StockItemsTable.selectAll().where { StockItemsTable.tenantId eq actors.current().tenantId }.count()
}
