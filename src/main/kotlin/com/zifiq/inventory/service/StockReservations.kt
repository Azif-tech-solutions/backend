package com.zifiq.inventory.service

import com.zifiq.inventory.model.StockReservationsTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component

/**
 * Open reservations held against a selling location (docs/09 §2.1). A reservation keeps goods on the
 * books (they are still physically there, so a stocktake counts them) while making them unavailable
 * to sell, transfer or trade until a waybill releases them. Writing a release posts the stock out of
 * the ledger in the same call, so a release and the stock movement can never diverge. Caller's
 * transaction.
 */
@Component
class StockReservations(
    private val stockLedger: StockLedgerRepository,
    private val hierarchy: LocationHierarchy,
) {
    /** Reserves [quantity] of [stockItemId] at the selling root [locationId] for a source document. */
    fun reserve(tenantId: UUID, locationId: UUID, stockItemId: UUID, sourceModule: String, sourceType: String, sourceId: UUID, sourceRef: String, quantity: BigDecimal): UUID =
        StockReservationsTable.insertAndGetId {
            it[StockReservationsTable.tenantId] = tenantId
            it[StockReservationsTable.locationId] = locationId
            it[StockReservationsTable.stockItemId] = stockItemId
            it[StockReservationsTable.sourceModule] = sourceModule
            it[StockReservationsTable.sourceType] = sourceType
            it[StockReservationsTable.sourceId] = sourceId
            it[StockReservationsTable.sourceRef] = sourceRef
            it[quantityReserved] = quantity
            it[quantityReleased] = BigDecimal.ZERO
            it[status] = "OPEN"
            it[createdAt] = Instant.now()
        }.value

    /**
     * Releases [quantity] of an open reservation, posting it out of the ledger from [fromLocation]
     * under [sourceType] (CUSTOMER_WAYBILL), and closing the reservation once fully released. Returns
     * the quantity still outstanding on the reservation.
     */
    fun release(reservationId: UUID, quantity: BigDecimal, fromLocation: UUID, sourceType: String, sourceRef: String, remark: String?, performedByUserId: UUID, entryDate: LocalDate): BigDecimal {
        val reservation = lock(reservationId)
        val outstanding = reservation[StockReservationsTable.quantityReserved] - reservation[StockReservationsTable.quantityReleased]
        require(quantity.signum() > 0 && quantity <= outstanding) { "release $quantity exceeds the $outstanding outstanding on reservation $reservationId" }
        stockLedger.postEntry(
            tenantId = reservation[StockReservationsTable.tenantId].value, locationId = fromLocation, stockItemId = reservation[StockReservationsTable.stockItemId],
            sourceType = sourceType, sourceReference = sourceRef, qtyOut = quantity, remark = remark, performedByUserId = performedByUserId, entryDate = entryDate,
        )
        val released = reservation[StockReservationsTable.quantityReleased] + quantity
        val fullyReleased = released >= reservation[StockReservationsTable.quantityReserved]
        StockReservationsTable.update({ StockReservationsTable.id eq reservationId }) {
            it[quantityReleased] = released
            if (fullyReleased) {
                it[status] = "CLOSED"
                it[closedAt] = Instant.now()
            }
        }
        return reservation[StockReservationsTable.quantityReserved] - released
    }

    /** Puts a released quantity back on the reservation (a waybill was cancelled); re-opens it if it had closed. */
    fun restore(reservationId: UUID, quantity: BigDecimal) {
        val reservation = lock(reservationId)
        StockReservationsTable.update({ StockReservationsTable.id eq reservationId }) {
            it[quantityReleased] = (reservation[StockReservationsTable.quantityReleased] - quantity).max(BigDecimal.ZERO)
            it[status] = "OPEN"
            it[closedAt] = null
        }
    }

    /** Cancels the open reservations of a source document (its sale was voided or its draft discarded). */
    fun cancelForSource(tenantId: UUID, sourceType: String, sourceIds: Collection<UUID>) {
        if (sourceIds.isEmpty()) return
        StockReservationsTable.update({
            (StockReservationsTable.tenantId eq tenantId) and (StockReservationsTable.sourceType eq sourceType) and
                (StockReservationsTable.sourceId inList sourceIds.toSet()) and (StockReservationsTable.status eq "OPEN")
        }) {
            it[status] = "CANCELLED"
            it[closedAt] = Instant.now()
        }
    }

    /** The open reservation for a source row (a sale line), or null. */
    fun openForSource(sourceType: String, sourceId: UUID): ResultRow? =
        StockReservationsTable.selectAll()
            .where { (StockReservationsTable.sourceType eq sourceType) and (StockReservationsTable.sourceId eq sourceId) and (StockReservationsTable.status eq "OPEN") }
            .firstOrNull()

    /** The reservation for a source row, whatever its status (there is at most one per source). */
    fun findBySource(sourceType: String, sourceId: UUID): ResultRow? =
        StockReservationsTable.selectAll()
            .where { (StockReservationsTable.sourceType eq sourceType) and (StockReservationsTable.sourceId eq sourceId) }
            .firstOrNull()

    /**
     * Per item, the quantity still reserved (reserved − released) across open reservations at the
     * root [locationId]. With [lock] the open rows are held FOR UPDATE, so a concurrent release or a
     * new reservation serializes against an availability check.
     */
    fun openReservedAt(locationId: UUID, stockItemIds: Collection<UUID>, lock: Boolean = false): Map<UUID, BigDecimal> {
        if (stockItemIds.isEmpty()) return emptyMap()
        val root = hierarchy.rootOf(locationId)
        val rows = StockReservationsTable.select(StockReservationsTable.stockItemId, StockReservationsTable.quantityReserved, StockReservationsTable.quantityReleased)
            .where { (StockReservationsTable.locationId eq root) and (StockReservationsTable.stockItemId inList stockItemIds.toSet()) and (StockReservationsTable.status eq "OPEN") }
            .orderBy(StockReservationsTable.stockItemId to SortOrder.ASC, StockReservationsTable.id to SortOrder.ASC)
            .let { if (lock) it.forUpdate() else it }
            .toList()
        return rows.groupBy({ it[StockReservationsTable.stockItemId] }, { it[StockReservationsTable.quantityReserved] - it[StockReservationsTable.quantityReleased] })
            .mapValues { (_, qs) -> qs.fold(BigDecimal.ZERO, BigDecimal::add) }
    }

    private fun lock(reservationId: UUID): ResultRow =
        StockReservationsTable.selectAll().where { StockReservationsTable.id eq reservationId }.forUpdate().single()
}
