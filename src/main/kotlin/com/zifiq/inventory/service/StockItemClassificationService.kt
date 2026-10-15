package com.zifiq.inventory.service

import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.tax.HsnCodesTable
import com.zifiq.common.tax.HsnSectionsTable
import com.zifiq.common.tax.IsicClassesTable
import com.zifiq.common.tax.IsicSectionsTable
import com.zifiq.common.tax.NrsUnitOfMeasureCodesTable
import com.zifiq.inventory.dto.StockItemClassification
import com.zifiq.inventory.dto.StockItemClassificationRequest
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Granular NRS classification on a stock item (docs/08 §2.1): the dotted HS subheading for goods or
 * the 4-digit ISIC class for services, and the NRS unit code. All optional — V6 only sample-seeds
 * these lists, so an invoice line falls back to the item's section and never fails for a missing code.
 * A granular code must sit in the item's own section (HSN_SECTION_MISMATCH otherwise).
 */
@Service
class StockItemClassificationService(
    private val actors: CurrentActorProvider,
    private val tx: TenantScopedTransaction,
    private val locationAccess: LocationAccessService,
    private val domainEvents: DomainEvents,
) {
    fun get(id: UUID): StockItemClassification = tx.execute {
        val item = item(id)
        // Visible where it is registered or stocked; anywhere else it is "not found".
        locationAccess.allowedLocationIds()?.let { allowed ->
            val stockedHere = !StockBalancesTable.selectAll().where { (StockBalancesTable.stockItemId eq id) and (StockBalancesTable.locationId inList allowed) }.empty()
            if (item[StockItemsTable.locationId] !in allowed && !stockedHere) throw ApiErrors.stockItemNotFound()
        }
        view(item)
    }

    /** PATCH semantics: an omitted (null) field is unchanged; an empty string clears it. */
    fun update(id: UUID, request: StockItemClassificationRequest): StockItemClassification = tx.execute {
        val item = item(id)
        locationAccess.assertAccess(item[StockItemsTable.locationId])
        val hsnSectionCode = item[StockItemsTable.hsnSectionId]?.let(::hsnSectionCode)
        val isicSectionCode = item[StockItemsTable.isicSectionId]?.let(::isicSectionCode)

        val hsnCode = request.hsnCode?.trim()?.let { code ->
            if (code.isEmpty()) return@let ""
            val section = HsnCodesTable.select(HsnCodesTable.sectionCode).where { HsnCodesTable.code eq code }.firstOrNull()?.get(HsnCodesTable.sectionCode)
                ?: throw ApiErrors.validationFailed(mapOf("hsnCode" to "unknown HS code"))
            if (section != hsnSectionCode) throw ApiErrors.hsnSectionMismatch()
            code
        }
        val isicCode = request.isicCode?.trim()?.let { code ->
            if (code.isEmpty()) return@let ""
            val section = IsicClassesTable.select(IsicClassesTable.sectionCode).where { IsicClassesTable.code eq code }.firstOrNull()?.get(IsicClassesTable.sectionCode)
                ?: throw ApiErrors.validationFailed(mapOf("isicCode" to "unknown ISIC class"))
            if (section != isicSectionCode) throw ApiErrors.hsnSectionMismatch()
            code
        }
        val unitCode = request.nrsUnitCode?.trim()?.let { code ->
            if (code.isEmpty()) return@let ""
            if (NrsUnitOfMeasureCodesTable.selectAll().where { NrsUnitOfMeasureCodesTable.code eq code }.empty()) {
                throw ApiErrors.validationFailed(mapOf("nrsUnitCode" to "unknown NRS unit code"))
            }
            code
        }

        StockItemsTable.update({ (StockItemsTable.id eq id) and (StockItemsTable.tenantId eq actors.current().tenantId) }) {
            if (hsnCode != null) it[StockItemsTable.hsnCode] = hsnCode.ifEmpty { null }
            if (isicCode != null) it[StockItemsTable.isicCode] = isicCode.ifEmpty { null }
            if (unitCode != null) it[StockItemsTable.nrsUnitCode] = unitCode.ifEmpty { null }
            if (request.isService != null) it[StockItemsTable.isService] = request.isService
        }
        val updated = item(id)
        domainEvents.record(
            type = "inventory.stock-item.classified", aggregateType = "stock-item", aggregateId = id, aggregateRef = updated[StockItemsTable.code],
            summary = "Stock item ${updated[StockItemsTable.code]} NRS classification updated",
            payload = mapOf("hsnCode" to updated[StockItemsTable.hsnCode], "isicCode" to updated[StockItemsTable.isicCode],
                "nrsUnitCode" to updated[StockItemsTable.nrsUnitCode], "isService" to updated[StockItemsTable.isService]),
            locationId = updated[StockItemsTable.locationId],
        )
        view(updated)
    }

    private fun item(id: UUID): ResultRow =
        StockItemsTable.selectAll().where { (StockItemsTable.id eq id) and (StockItemsTable.tenantId eq actors.current().tenantId) }.firstOrNull()
            ?: throw ApiErrors.stockItemNotFound()

    private fun hsnSectionCode(id: UUID): String? = HsnSectionsTable.select(HsnSectionsTable.code).where { HsnSectionsTable.id eq id }.firstOrNull()?.get(HsnSectionsTable.code)
    private fun isicSectionCode(id: UUID): String? = IsicSectionsTable.select(IsicSectionsTable.code).where { IsicSectionsTable.id eq id }.firstOrNull()?.get(IsicSectionsTable.code)

    private fun view(item: ResultRow) = StockItemClassification(
        id = item[StockItemsTable.id].value, code = item[StockItemsTable.code], name = item[StockItemsTable.name],
        hsnSectionCode = item[StockItemsTable.hsnSectionId]?.let(::hsnSectionCode), hsnCode = item[StockItemsTable.hsnCode],
        isicSectionCode = item[StockItemsTable.isicSectionId]?.let(::isicSectionCode), isicCode = item[StockItemsTable.isicCode],
        nrsUnitCode = item[StockItemsTable.nrsUnitCode], isService = item[StockItemsTable.isService],
    )
}
