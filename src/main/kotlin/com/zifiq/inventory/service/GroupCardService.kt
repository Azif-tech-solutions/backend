package com.zifiq.inventory.service

import com.zifiq.platform.directory.Ref
import com.zifiq.inventory.repository.StockSql
import org.jetbrains.exposed.sql.sum
import com.zifiq.common.paging.PagingPolicy
import com.zifiq.common.paging.Paging
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.search.RawExpression
import com.zifiq.inventory.dto.BinCardRow
import com.zifiq.inventory.dto.GroupBinCard
import com.zifiq.inventory.dto.GroupCardHeader
import com.zifiq.inventory.dto.GroupCardMember
import com.zifiq.inventory.dto.GroupStockCard
import com.zifiq.inventory.dto.GroupStockCardRow
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.springframework.stereotype.Service

/**
 * Group Stock/Bin Cards (docs/05 §4.10): the movements of every item in a group, with one running
 * total — e.g. a category of drinks at a showroom and its areas. Quantities are only added when the
 * whole group uses one unit. Grouping by parent product needs product variants (docs/03 §12.2),
 * which aren't built yet.
 *
 * Everything runs in SQL: rows are paged with the running balance from a window sum, and the
 * header's opening, received, issued and closing figures are aggregates, so a category with years
 * of movements never loads into memory. The header lists the first [PagingPolicy.SUMMARY_TOP_N]
 * members and the total count.
 */
@Service
class GroupCardService(
    private val tx: TenantScopedTransaction,
    private val currentActorProvider: CurrentActorProvider,
    private val locationAccess: LocationAccessService,
) {
    fun stockCard(groupBy: String, groupId: UUID, locationId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageRequest = PageRequest.of(null, null)): GroupStockCard = tx.execute {
        val scope = prepare(groupBy, groupId, locationId, from, to)
        val running = StockSql.runningBalance()
        val query = joined()
            .select(StockLedgerEntriesTable.columns + StockItemsTable.code + StockItemsTable.name + LocationsTable.name + running)
            .where { scope.inRange() }
            .orderBy(StockLedgerEntriesTable.entryDate to SortOrder.ASC, StockLedgerEntriesTable.createdAt to SortOrder.ASC, StockLedgerEntriesTable.id to SortOrder.ASC)
        GroupStockCard(scope.header, Paging.fetchNumbered(query, paging) { sn, row ->
            GroupStockCardRow(
                sn = sn,
                date = row[StockLedgerEntriesTable.entryDate],
                itemCode = row[StockItemsTable.code],
                itemName = row[StockItemsTable.name],
                locationName = row[LocationsTable.name],
                remark = row[StockLedgerEntriesTable.remark] ?: row[StockLedgerEntriesTable.sourceReference],
                qtyIn = row[StockLedgerEntriesTable.qtyIn],
                qtyOut = row[StockLedgerEntriesTable.qtyOut],
                qtyRemain = scope.header.openingBalance + row[running],
            )
        })
    }

    fun binCard(groupBy: String, groupId: UUID, locationId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageRequest = PageRequest.of(null, null)): GroupBinCard = tx.execute {
        val scope = prepare(groupBy, groupId, locationId, from, to)
        val qtyIn = StockLedgerEntriesTable.qtyIn.sum()
        val qtyOut = StockLedgerEntriesTable.qtyOut.sum()
        val running = StockSql.dailyRunningBalance()
        val query = joined().select(StockLedgerEntriesTable.entryDate, qtyIn, qtyOut, running)
            .where { scope.inRange() }
            .groupBy(StockLedgerEntriesTable.entryDate)
            .orderBy(StockLedgerEntriesTable.entryDate to SortOrder.ASC)
        GroupBinCard(scope.header, Paging.fetchNumbered(query, paging) { sn, row ->
            BinCardRow(sn, row[StockLedgerEntriesTable.entryDate], row[qtyIn] ?: BigDecimal.ZERO, row[qtyOut] ?: BigDecimal.ZERO, scope.header.openingBalance + row[running])
        })
    }

    private class Scope(val header: GroupCardHeader, val inRange: () -> Op<Boolean>)

    private fun joined() = StockLedgerEntriesTable
        .join(StockItemsTable, JoinType.INNER, StockLedgerEntriesTable.stockItemId, StockItemsTable.id)
        .join(LocationsTable, JoinType.INNER, StockLedgerEntriesTable.locationId, LocationsTable.id)

    /** Validates the group, checks one unit, and computes the header with SQL aggregates (caller's transaction). */
    private fun prepare(groupBy: String, groupId: UUID, locationId: UUID?, from: LocalDate?, to: LocalDate?): Scope {
        when (groupBy.uppercase()) {
            "CATEGORY" -> Unit
            "PARENT_PRODUCT" -> throw ApiErrors.invalidFilter("groupBy", "Grouping by parent product needs product variants, which aren't available yet", listOf("CATEGORY"))
            else -> throw ApiErrors.invalidFilter("groupBy", "Unknown grouping", listOf("CATEGORY"))
        }
        if (from != null && to != null && from.isAfter(to)) throw ApiErrors.invalidFilter("from", "'from' is after 'to'")
        val tenantId = currentActorProvider.current().tenantId
        val groupName = TransactionManager.current().exec(
            "select name from categories where id = ?", listOf(org.jetbrains.exposed.sql.UUIDColumnType() to groupId),
        ) { rs -> if (rs.next()) rs.getString(1) else null } ?: throw ApiErrors.invalidFilter("groupId", "Category not found")

        val unitName = StockSql.unitName(StockItemsTable.unitOfMeasureId)
        val inGroup = (StockItemsTable.tenantId eq tenantId) and (StockItemsTable.categoryId eq groupId)
        val units = StockItemsTable.select(unitName).where { inGroup }.withDistinct().limit(PagingPolicy.SUMMARY_TOP_N).map { it[unitName] }
        if (units.size > 1) throw ApiErrors.groupMixedUnits(units)
        val memberCount = StockItemsTable.select(StockItemsTable.id).where { inGroup }.count()
        val members = StockItemsTable.select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, unitName)
            .where { inGroup }.orderBy(StockItemsTable.name to SortOrder.ASC).limit(PagingPolicy.SUMMARY_TOP_N)
            .map { GroupCardMember(Ref.stockItem(it[StockItemsTable.id].value), it.getOrNull(unitName)) }

        val locations = locationAccess.resolveFilterSet(locationId)
        fun scoped(): Op<Boolean> {
            var condition: Op<Boolean> = (StockLedgerEntriesTable.tenantId eq tenantId) and (StockItemsTable.categoryId eq groupId)
            if (locations != null) condition = condition and (StockLedgerEntriesTable.locationId inList locations)
            return condition
        }
        fun inRange(): Op<Boolean> {
            var condition = scoped()
            if (from != null) condition = condition and (StockLedgerEntriesTable.entryDate greaterEq from)
            if (to != null) condition = condition and (StockLedgerEntriesTable.entryDate lessEq to)
            return condition
        }
        val net = StockSql.net()
        val opening = if (from == null) BigDecimal.ZERO
        else joined().select(net).where { scoped() and (StockLedgerEntriesTable.entryDate less from) }.single()[net] ?: BigDecimal.ZERO
        val received = StockSql.received()
        val issued = StockSql.issued()
        val totals = joined().select(received, issued).where { inRange() }.single()
        val totalReceived = totals[received] ?: BigDecimal.ZERO
        val totalIssued = totals[issued] ?: BigDecimal.ZERO
        return Scope(
            GroupCardHeader(
                groupType = "CATEGORY", group = Ref.category(groupId), members = members, memberCount = memberCount,
                openingBalance = opening, totalReceived = totalReceived, totalIssued = totalIssued, closingBalance = opening + totalReceived - totalIssued,
            ),
            ::inRange,
        )
    }
}
