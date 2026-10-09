package com.zifiq.showroom.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.search.allOf
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.showroom.model.CustomerReturnLinesTable
import com.zifiq.showroom.model.CustomerReturnsTable
import com.zifiq.showroom.model.ShowroomStockEntriesTable
import com.zifiq.showroom.model.ShowroomStockEntryLinesTable
import com.zifiq.showroom.spi.ReturnFilter
import com.zifiq.showroom.spi.ReturnLineRef
import com.zifiq.showroom.spi.ShowroomQueries
import com.zifiq.showroom.spi.ShowroomRef
import com.zifiq.showroom.spi.StockEntryFilter
import com.zifiq.showroom.spi.StockEntryLineRef
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/** ShowroomQueries (docs/05 §5). Runs in the caller's transaction. */
@Service
class ShowroomSpiService(private val tenantContext: TenantContext) : ShowroomQueries {
    override fun showroom(id: UUID): ShowroomRef {
        val row = LocationsTable.selectAll()
            .where { (LocationsTable.id eq id) and (LocationsTable.tenantId eq tenantContext.requireTenantId()) and (LocationsTable.type eq "SHOWROOM") }
            .firstOrNull() ?: throw ApiErrors.showroomNotFound()
        return ShowroomRef(
            id = id, code = row[LocationsTable.code], name = row[LocationsTable.name], streetAddress = row[LocationsTable.streetAddress],
            city = row[LocationsTable.city], stateCode = row[LocationsTable.state], phone = row[LocationsTable.phone], email = row[LocationsTable.email],
            vatRate = row[LocationsTable.vatRate], vatEnabled = row[LocationsTable.vatEnabled],
            vatRegistrationNumber = row[LocationsTable.vatRegistrationNumber], status = row[LocationsTable.status],
        )
    }

    override fun returns(filter: ReturnFilter): List<ReturnLineRef> {
        val parts = mutableListOf<Op<Boolean>>(CustomerReturnsTable.tenantId eq tenantContext.requireTenantId())
        filter.locationIds?.let { parts += CustomerReturnsTable.locationId inList it }
        filter.from?.let { parts += CustomerReturnsTable.returnDate greaterEq it }
        filter.to?.let { parts += CustomerReturnsTable.returnDate lessEq it }
        filter.status?.let { parts += CustomerReturnsTable.status eq it }
        return CustomerReturnLinesTable.join(CustomerReturnsTable, JoinType.INNER, CustomerReturnLinesTable.returnId, CustomerReturnsTable.id)
            .selectAll().where(parts.allOf()!!)
            .orderBy(CustomerReturnsTable.returnDate to SortOrder.ASC, CustomerReturnLinesTable.id to SortOrder.ASC)
            .map {
                ReturnLineRef(
                    returnId = it[CustomerReturnsTable.id].value, ref = it[CustomerReturnsTable.ref], returnType = it[CustomerReturnsTable.returnType],
                    locationId = it[CustomerReturnsTable.locationId], customerId = it[CustomerReturnsTable.customerId], customerType = it[CustomerReturnsTable.customerType],
                    returnDate = it[CustomerReturnsTable.returnDate], stockItemId = it[CustomerReturnLinesTable.stockItemId], quantity = it[CustomerReturnLinesTable.quantity],
                    unitPrice = it[CustomerReturnLinesTable.unitPrice], lineValue = it[CustomerReturnLinesTable.lineValue], reason = it[CustomerReturnLinesTable.reason],
                    condition = it[CustomerReturnLinesTable.condition], status = it[CustomerReturnsTable.status],
                )
            }
    }

    override fun stockEntries(filter: StockEntryFilter): List<StockEntryLineRef> {
        val parts = mutableListOf<Op<Boolean>>(ShowroomStockEntriesTable.tenantId eq tenantContext.requireTenantId())
        filter.kind?.let { parts += ShowroomStockEntriesTable.kind eq it }
        filter.locationIds?.let { parts += ShowroomStockEntriesTable.locationId inList it }
        filter.from?.let { parts += ShowroomStockEntriesTable.receivedDate greaterEq it }
        filter.to?.let { parts += ShowroomStockEntriesTable.receivedDate lessEq it }
        return ShowroomStockEntryLinesTable.join(ShowroomStockEntriesTable, JoinType.INNER, ShowroomStockEntryLinesTable.entryId, ShowroomStockEntriesTable.id)
            .selectAll().where(parts.allOf()!!)
            .orderBy(ShowroomStockEntriesTable.receivedDate to SortOrder.ASC, ShowroomStockEntryLinesTable.id to SortOrder.ASC)
            .map {
                StockEntryLineRef(
                    entryId = it[ShowroomStockEntriesTable.id].value, ref = it[ShowroomStockEntriesTable.ref], kind = it[ShowroomStockEntriesTable.kind],
                    locationId = it[ShowroomStockEntriesTable.locationId], receivedDate = it[ShowroomStockEntriesTable.receivedDate],
                    stockItemId = it[ShowroomStockEntryLinesTable.stockItemId], quantity = it[ShowroomStockEntryLinesTable.quantity],
                    unitCost = it[ShowroomStockEntryLinesTable.unitCost], lineCost = it[ShowroomStockEntryLinesTable.lineCost],
                    inputVatRate = it[ShowroomStockEntryLinesTable.inputVatRate], inputVatAmount = it[ShowroomStockEntryLinesTable.inputVatAmount],
                    supplierId = it[ShowroomStockEntriesTable.supplierId], supplierName = it[ShowroomStockEntriesTable.supplierName],
                    manufacturerId = it[ShowroomStockEntriesTable.manufacturerId],
                )
            }
    }
}

/** Global search: showrooms (docs/05 §9). */
@Component
class ShowroomSearchProvider(
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val register: ShowroomRegisterService,
) : SearchProvider {
    override val type = "showroom"
    override val label = "Showrooms"
    override val listRoute = "/showrooms"

    override fun canSearch() = permissionAuthorizer.hasPermission(currentActorProvider.current().userId, "showroom.stock.read")

    override fun search(q: String, limit: Int): List<SearchHit> =
        register.list(q, emptySet(), emptySet()).items.take(limit).map {
            SearchHit(type, it.id, it.name, listOfNotNull(it.code, it.city, it.status.lowercase()).joinToString(" · "), it.code, "/showrooms/${it.id}")
        }
}

/** Global search: customer returns by RT reference, customer or product. */
@Component
class ReturnSearchProvider(
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val returns: CustomerReturnService,
) : SearchProvider {
    override val type = "return"
    override val label = "Returns"
    override val listRoute = "/showroom/returns"

    override fun canSearch() = permissionAuthorizer.hasPermission(currentActorProvider.current().userId, "showroom.returns.read")

    override fun search(q: String, limit: Int): List<SearchHit> =
        returns.list(ListSearchRequest(q = q, size = limit * 4, summary = false, params = mapOf("status" to listOf("POSTED", "REVERSED"))))
            .items.distinctBy { it.returnId }.take(limit).map {
                SearchHit(type, it.returnId, it.ref, "${it.customerName} · ${it.returnType.lowercase().replace('_', '-')} · ${it.date}", it.ref, "/showroom/returns/${it.returnId}")
            }
}
