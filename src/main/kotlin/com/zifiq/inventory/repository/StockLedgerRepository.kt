package com.zifiq.inventory.repository

import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.inventory.model.StockLedgerEntriesTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component

/**
 * Writes a stock_ledger_entries row and updates stock_balances in the same call — the only
 * sanctioned way any module (Factory, Showroom) touches either table, so quantity_on_hand can
 * never drift from the ledger's sum. See docs/03-inventory.md §2-3. Public API other modules
 * depend on (Factory's Production/Trade services post entries here) — Inventory is the shared
 * stock substrate, analogous to how every module depends on `platform`.
 *
 * Locks the balance row with SELECT ... FOR UPDATE before computing the new total, the same
 * pattern DocumentSequenceService uses, so concurrent postings against the same
 * (location, stock_item) serialize correctly.
 */
@Component
class StockLedgerRepository {
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
    }

    fun currentBalance(locationId: UUID, stockItemId: UUID): BigDecimal =
        StockBalancesTable.select(StockBalancesTable.quantityOnHand)
            .andWhere { StockBalancesTable.locationId eq locationId }
            .andWhere { StockBalancesTable.stockItemId eq stockItemId }
            .firstOrNull()?.get(StockBalancesTable.quantityOnHand) ?: BigDecimal.ZERO
}
