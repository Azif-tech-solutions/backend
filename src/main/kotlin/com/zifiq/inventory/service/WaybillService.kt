package com.zifiq.inventory.service

import com.zifiq.platform.directory.Refs
import com.zifiq.platform.documents.DocumentLinkProvider
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.model.TenantsTable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Transfer waybills as PDF (docs/05 §4.3, PRD §6 rule 6). A copy printed after a recorded reprint
 * (POST /api/v1/documents/{ref}/reprints) carries "DUPLICATE COPY" (docs/03 §8.2).
 */
@Service
class WaybillService(
    private val tx: TenantScopedTransaction,
    private val currentActorProvider: CurrentActorProvider,
    private val operations: InventoryOperationsService,
    private val documentRegistry: DocumentRegistry,
    private val moduleSettings: ModuleSettings,
    private val pdfRenderer: PdfRenderer,
) {
    data class Waybill(val ref: String, val pdf: ByteArray)

    fun render(transferId: UUID): Waybill {
        val model = tx.execute {
            val transfer = operations.loadTransferSummary(transferId).also(Refs::resolve)
            operations.requireTransferVisible(transfer)
            val company = TenantsTable.selectAll().where { TenantsTable.id eq currentActorProvider.current().tenantId }.single()[TenantsTable.name]
            fun location(id: UUID) = LocationsTable.selectAll().where { LocationsTable.id eq id }.single()
            val sourceRoot = location(rootOf(transfer.sourceLocation.id))
            val destinationRoot = location(rootOf(transfer.destinationLocation.id))
            val route = if (transfer.sourceLocation["type"] == "SHOWROOM" && transfer.destinationLocation["type"] == "SHOWROOM") "Showroom Transfer Waybill" else "Stock Transfer Waybill"
            mapOf(
                "title" to route,
                "company" to company,
                "transfer" to transfer,
                "source" to sourceRoot.let { mapOf("name" to it[LocationsTable.name], "address" to it[LocationsTable.address], "phone" to it[LocationsTable.phone], "vat" to it[LocationsTable.vatRegistrationNumber]) },
                "destination" to destinationRoot.let { mapOf("name" to it[LocationsTable.name], "address" to it[LocationsTable.address], "phone" to it[LocationsTable.phone]) },
                "stamp" to when (transfer.status) {
                    "RECEIVED" -> "Received"
                    "PARTIALLY_RECEIVED" -> "Partially received"
                    else -> "In transit"
                },
                "duplicate" to documentRegistry.isReprint(transfer.waybillRef),
                "totalLines" to transfer.lines.size,
                "totalQuantity" to transfer.totalSent.stripTrailingZeros().toPlainString(),
                "printedAt" to LocalDateTime.now(moduleSettings.zone()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
                "dispatchDate" to transfer.transferDate.format(DateTimeFormatter.ofPattern("d MMM yyyy")),
            )
        }
        val transfer = model["transfer"] as com.zifiq.inventory.dto.StockTransferSummary
        return Waybill(transfer.waybillRef, pdfRenderer.render("waybill", model))
    }

    private fun rootOf(id: UUID): UUID =
        LocationsTable.select(LocationsTable.parentLocationId).where { LocationsTable.id eq id }.single()[LocationsTable.parentLocationId] ?: id
}

/** Lets the document registry link waybills to their PDF. */
@Component
class InventoryDocumentLinks : DocumentLinkProvider {
    override val sourceModule = "inventory"
    override fun pdfUrl(docType: DocumentType, sourceId: UUID): String? =
        if (docType == DocumentType.WAYBILL) "/api/v1/inventory/stock/transfers/$sourceId/waybill.pdf" else null
}
