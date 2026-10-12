package com.zifiq.inventory.repository

import com.zifiq.common.errors.ApiErrors
import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.tenancy.LocationHierarchy
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component

/**
 * Writes a stock_ledger_entries row and updates stock_balances in the same call — the only
 * sanctioned way any module (Factory, Showroom) touches either table, so quantity_on_hand can
 * never drift from the ledger's sum. See docs/implemented/02-factory.md and docs/implemented/05-showroom.md §1.3. Public API other modules
 * depend on (Factory's Production/Trade services post entries here) — Inventory is the shared
 * stock substrate, analogous to how every module depends on `platform`.
 *
 * Locks the balance row with SELECT ... FOR UPDATE before computing the new total, the same
 * pattern DocumentSequenceService uses, so concurrent postings against the same
 * (location, stock_item) serialize correctly.
 */
@Component
class StockLedgerRepository(
    private val notify: Notify,
    private val hierarchy: LocationHierarchy = LocationHierarchy(),
) {
    fun postEntry(
        tenantId: UUID,
        locationId: UUID,
        stockItemId: UUID,
        sourceType: String,
        sourceReference: String,
        qtyIn: BigDecimal = BigDecimal.ZERO,
        qtyOut: BigDecimal = BigDecimal.ZERO,
        unitCost: BigDecimal? = null,
        remark: String? = null,
        performedByUserId: UUID,
        entryDate: LocalDate = LocalDate.now(),
    ) {
        requireItemAllowedAt(stockItemId, locationId)
        StockLedgerEntriesTable.insert {
            it[StockLedgerEntriesTable.tenantId] = tenantId
            it[StockLedgerEntriesTable.locationId] = locationId
            it[StockLedgerEntriesTable.stockItemId] = stockItemId
            it[StockLedgerEntriesTable.sourceType] = sourceType
            it[StockLedgerEntriesTable.sourceReference] = sourceReference
            it[StockLedgerEntriesTable.qtyIn] = qtyIn
            it[StockLedgerEntriesTable.qtyOut] = qtyOut
            it[StockLedgerEntriesTable.unitCost] = unitCost
            it[StockLedgerEntriesTable.remark] = remark
            it[StockLedgerEntriesTable.entryDate] = entryDate
            it[StockLedgerEntriesTable.createdByUserId] = performedByUserId
            it[StockLedgerEntriesTable.createdAt] = Instant.now()
        }

        StockBalancesTable.insertIgnore {
            it[StockBalancesTable.tenantId] = tenantId
            it[StockBalancesTable.locationId] = locationId
            it[StockBalancesTable.stockItemId] = stockItemId
            it[StockBalancesTable.quantityOnHand] = BigDecimal.ZERO
            it[StockBalancesTable.updatedAt] = Instant.now()
        }

        val currentBalance = StockBalancesTable
            .select(StockBalancesTable.quantityOnHand)
            .andWhere { StockBalancesTable.locationId eq locationId }
            .andWhere { StockBalancesTable.stockItemId eq stockItemId }
            .forUpdate()
            .single()[StockBalancesTable.quantityOnHand]

        val newBalance = currentBalance + qtyIn - qtyOut

        StockBalancesTable.update({
            (StockBalancesTable.locationId eq locationId) and (StockBalancesTable.stockItemId eq stockItemId)
        }) {
            it[StockBalancesTable.quantityOnHand] = newBalance
            it[StockBalancesTable.updatedAt] = Instant.now()
        }

        if (qtyOut > BigDecimal.ZERO) {
            notifyIfCrossedReorderLevel(tenantId, locationId, stockItemId, previousBalance = currentBalance, newBalance = newBalance)
        }
    }

    /**
     * Re-Order Report (docs/implemented/02-factory.md) is pull-only -- nothing paged anyone when a level was
     * actually crossed. Fires once per crossing (previous balance above the level, new balance at
     * or below it), not on every subsequent decrease while already below, so dropping stock to zero
     * over several postings doesn't spam the outbox. reorderLevel == 0 means "no threshold
     * configured" and never fires.
     */
    private fun notifyIfCrossedReorderLevel(
        tenantId: UUID,
        locationId: UUID,
        stockItemId: UUID,
        previousBalance: BigDecimal,
        newBalance: BigDecimal,
    ) {
        val item = StockItemsTable.selectAll().andWhere { StockItemsTable.id eq stockItemId }.firstOrNull() ?: return
        val reorderLevel = item[StockItemsTable.reorderLevel]
        if (reorderLevel <= BigDecimal.ZERO) return
        if (!(previousBalance > reorderLevel && newBalance <= reorderLevel)) return

        // Holders of inventory.stock.manage at this location (PRD §8: "Stock Personnel / Admin",
        // in-app alert). This used to address a literal "stock-managers" recipient that matched no one.
        notify.holdersOf(
            permission = "inventory.stock.manage",
            locationId = locationId,
            category = NotificationCategory.ALERT,
            title = "Re-order level reached",
            body = "${item[StockItemsTable.name]} (${item[StockItemsTable.code]}) has reached its re-order level " +
                "($newBalance on hand, threshold $reorderLevel) at this location.",
            linkType = "inventory.stock-item",
            linkRef = stockItemId.toString(),
        )
    }

    /** Direct-entry items are held only at the showroom that entered them (or its areas) — docs/05 §2.2. */
    private fun requireItemAllowedAt(stockItemId: UUID, locationId: UUID) {
        val item = StockItemsTable.select(StockItemsTable.itemType, StockItemsTable.locationId)
            .andWhere { StockItemsTable.id eq stockItemId }
            .firstOrNull() ?: return
        if (item[StockItemsTable.itemType] == "DIRECT_ENTRY" && hierarchy.rootOf(locationId) != item[StockItemsTable.locationId]) {
            throw ApiErrors.itemLocationRestricted()
        }
    }

    /**
     * Balance at one exact location, locked FOR UPDATE until the caller's transaction ends — for
     * decisions that must not race another posting (a counted adjustment against a concurrent sale).
     */
    fun lockedBalance(tenantId: UUID, locationId: UUID, stockItemId: UUID): BigDecimal {
        StockBalancesTable.insertIgnore {
            it[StockBalancesTable.tenantId] = tenantId
            it[StockBalancesTable.locationId] = locationId
            it[StockBalancesTable.stockItemId] = stockItemId
            it[StockBalancesTable.quantityOnHand] = BigDecimal.ZERO
            it[StockBalancesTable.updatedAt] = Instant.now()
        }
        return StockBalancesTable.select(StockBalancesTable.quantityOnHand)
            .andWhere { StockBalancesTable.locationId eq locationId }
            .andWhere { StockBalancesTable.stockItemId eq stockItemId }
            .forUpdate()
            .single()[StockBalancesTable.quantityOnHand]
    }

    /** Balance at one exact location (an area is separate from its showroom). */
    fun currentBalance(locationId: UUID, stockItemId: UUID): BigDecimal =
        StockBalancesTable.select(StockBalancesTable.quantityOnHand)
            .andWhere { StockBalancesTable.locationId eq locationId }
            .andWhere { StockBalancesTable.stockItemId eq stockItemId }
            .firstOrNull()?.get(StockBalancesTable.quantityOnHand) ?: BigDecimal.ZERO
}
