package com.zifiq.inventory.service

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
 */
@Service
class GroupCardService(
    private val tx: TenantScopedTransaction,
    private val currentActorProvider: CurrentActorProvider,
    private val locationAccess: LocationAccessService,
) {
    private data class Prepared(val header: GroupCardHeader, val entries: List<GroupStockCardRow>)

    fun stockCard(groupBy: String, groupId: UUID, locationId: UUID?, from: LocalDate?, to: LocalDate?): GroupStockCard {
        val prepared = prepare(groupBy, groupId, locationId, from, to)
        return GroupStockCard(prepared.header, prepared.entries)
    }

    fun binCard(groupBy: String, groupId: UUID, locationId: UUID?, from: LocalDate?, to: LocalDate?): GroupBinCard {
        val prepared = prepare(groupBy, groupId, locationId, from, to)
        var running = prepared.header.openingBalance
        val rows = prepared.entries.groupBy { it.date }.toSortedMap().entries.mapIndexed { index, (date, rows) ->
            val qtyIn = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.qtyIn }
            val qtyOut = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.qtyOut }
            running += qtyIn - qtyOut
            BinCardRow(index + 1, date, qtyIn, qtyOut, running)
        }
        return GroupBinCard(prepared.header, rows)
    }

    private fun prepare(groupBy: String, groupId: UUID, locationId: UUID?, from: LocalDate?, to: LocalDate?): Prepared = tx.execute {
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

        val unitName = RawExpression<String>({ +"(select u.name from units_of_measure u where u.id = "; +StockItemsTable.unitOfMeasureId; +")" })
        val members = StockItemsTable.select(StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, StockItemsTable.unitOfMeasureId, unitName)
            .where { (StockItemsTable.tenantId eq tenantId) and (StockItemsTable.categoryId eq groupId) }
            .orderBy(StockItemsTable.name to SortOrder.ASC)
            .toList()
        val units = members.map { it[StockItemsTable.unitOfMeasureId] }.toSet()
        if (units.size > 1) throw ApiErrors.groupMixedUnits(members.mapNotNull { it.getOrNull(unitName) }.distinct())
        val memberIds = members.map { it[StockItemsTable.id].value }

        val locations = locationAccess.resolveFilterSet(locationId)
        fun scoped(): Op<Boolean> {
            var condition: Op<Boolean> = (StockLedgerEntriesTable.tenantId eq tenantId) and (StockLedgerEntriesTable.stockItemId inList memberIds)
            if (locations != null) condition = condition and (StockLedgerEntriesTable.locationId inList locations)
            return condition
        }

        val opening = if (from == null || memberIds.isEmpty()) BigDecimal.ZERO else
            StockLedgerEntriesTable.select(StockLedgerEntriesTable.qtyIn, StockLedgerEntriesTable.qtyOut)
                .where { scoped() and (StockLedgerEntriesTable.entryDate less from) }
                .fold(BigDecimal.ZERO) { sum, row -> sum + row[StockLedgerEntriesTable.qtyIn] - row[StockLedgerEntriesTable.qtyOut] }

        var running = opening
        val entries = if (memberIds.isEmpty()) emptyList() else StockLedgerEntriesTable
            .join(StockItemsTable, JoinType.INNER, StockLedgerEntriesTable.stockItemId, StockItemsTable.id)
            .join(LocationsTable, JoinType.INNER, StockLedgerEntriesTable.locationId, LocationsTable.id)
            .select(StockLedgerEntriesTable.columns + StockItemsTable.code + StockItemsTable.name + LocationsTable.name)
            .where {
                var condition = scoped()
                if (from != null) condition = condition and (StockLedgerEntriesTable.entryDate greaterEq from)
                if (to != null) condition = condition and (StockLedgerEntriesTable.entryDate lessEq to)
                condition
            }
            .orderBy(StockLedgerEntriesTable.entryDate to SortOrder.ASC, StockLedgerEntriesTable.createdAt to SortOrder.ASC, StockLedgerEntriesTable.id to SortOrder.ASC)
            .mapIndexed { index, row ->
                running += row[StockLedgerEntriesTable.qtyIn] - row[StockLedgerEntriesTable.qtyOut]
                GroupStockCardRow(
                    sn = index + 1,
                    date = row[StockLedgerEntriesTable.entryDate],
                    itemCode = row[StockItemsTable.code],
                    itemName = row[StockItemsTable.name],
                    locationName = row[LocationsTable.name],
                    remark = row[StockLedgerEntriesTable.remark] ?: row[StockLedgerEntriesTable.sourceReference],
                    qtyIn = row[StockLedgerEntriesTable.qtyIn],
                    qtyOut = row[StockLedgerEntriesTable.qtyOut],
                    qtyRemain = running,
                )
            }

        val received = entries.fold(BigDecimal.ZERO) { sum, row -> sum + row.qtyIn }
        val issued = entries.fold(BigDecimal.ZERO) { sum, row -> sum + row.qtyOut }
        Prepared(
            GroupCardHeader(
                groupType = "CATEGORY",
                groupId = groupId,
                groupName = groupName,
                members = members.map { GroupCardMember(it[StockItemsTable.id].value, it[StockItemsTable.code], it[StockItemsTable.name], it.getOrNull(unitName)) },
                openingBalance = opening,
                totalReceived = received,
                totalIssued = issued,
                closingBalance = opening + received - issued,
            ),
            entries,
        )
    }
}
