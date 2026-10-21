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
 * areas, which is how a showroom's stock is counted. Deliver-later reservations (Sales & POS slice 2)
 * will be subtracted here, so every seller checks the same figure. Runs in the caller's transaction.
 */
@Component
class StockAvailability(private val hierarchy: LocationHierarchy) {
    /**
     * Per item, Σ on hand over [rootId] and its areas (0 for an item never stocked there). With [lock]
     * the balance rows are held FOR UPDATE until the transaction ends — taken in a fixed order, so two
     * tills selling the same last units queue instead of both succeeding, and never deadlock.
     */
    fun available(rootId: UUID, stockItemIds: Collection<UUID>, lock: Boolean = false): Map<UUID, BigDecimal> {
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
