package com.zifiq.inventory.service

import com.zifiq.inventory.model.StockBalancesTable
import com.zifiq.platform.tenancy.LocationHierarchy
import java.math.BigDecimal
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.springframework.stereotype.Component

/**
 * What a factory or showroom has to sell (docs/09 §2.1): stock on hand across it and its storage
 * areas (how a showroom's stock is counted) minus its open reservations — goods sold but not yet
 * collected, held by a later waybill. Every seller (POS, stock transfer, factory trade) checks this
 * one figure, so reserved goods can't be promised twice. Runs in the caller's transaction.
 */
@Component
class StockAvailability(
    private val hierarchy: LocationHierarchy,
    private val reservations: StockReservations,
) {
    /**
     * Per item, available = Σ on hand over [rootId] and its areas − open reservations at the root
     * (0 for an item never stocked or reserved). With [lock] the balance rows and the open
     * reservation rows are held FOR UPDATE until the transaction ends, both taken in a fixed order,
     * so two tills selling the same last units queue instead of both succeeding, and never deadlock.
     */
    fun available(rootId: UUID, stockItemIds: Collection<UUID>, lock: Boolean = false): Map<UUID, BigDecimal> {
        if (stockItemIds.isEmpty()) return emptyMap()
        val onHand = onHand(rootId, stockItemIds, lock)
        val reserved = reservations.openReservedAt(rootId, stockItemIds, lock)
        return stockItemIds.associateWith { (onHand[it] ?: BigDecimal.ZERO) - (reserved[it] ?: BigDecimal.ZERO) }
    }

    /** On-hand only (ignores reservations): what a stocktake would count across the root and its areas. */
    fun onHand(rootId: UUID, stockItemIds: Collection<UUID>, lock: Boolean = false): Map<UUID, BigDecimal> {
        if (stockItemIds.isEmpty()) return emptyMap()
        val rows = StockBalancesTable.select(StockBalancesTable.stockItemId, StockBalancesTable.quantityOnHand)
            .where { (StockBalancesTable.locationId inList hierarchy.withAreas(rootId)) and (StockBalancesTable.stockItemId inList stockItemIds.toSet()) }
            .orderBy(StockBalancesTable.stockItemId to SortOrder.ASC, StockBalancesTable.locationId to SortOrder.ASC)
            .let { if (lock) it.forUpdate() else it }
            .toList()
        val sums = rows.groupBy({ it[StockBalancesTable.stockItemId].value }, { it[StockBalancesTable.quantityOnHand] })
            .mapValues { (_, quantities) -> quantities.fold(BigDecimal.ZERO, BigDecimal::add) }
        return stockItemIds.associateWith { sums[it] ?: BigDecimal.ZERO }
    }
}
