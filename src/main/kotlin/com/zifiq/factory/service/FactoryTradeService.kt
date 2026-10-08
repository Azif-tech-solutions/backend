package com.zifiq.factory.service

import com.zifiq.factory.dto.FactoryTradeOfferRequest
import com.zifiq.factory.dto.FactoryTradeOfferSummary
import com.zifiq.factory.model.FactoryTradeOffersTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Factory-to-Factory Trade (docs/02-factory.md §3.11). Stays under Factory & Production per the
 * proposed navigation architecture (sketch/index.html), even though it posts to Inventory's shared
 * stock ledger — the same allowed dependency direction Production uses (Factory depends on
 * Inventory, never the reverse).
 */
@Service
class FactoryTradeService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val documentSequenceService: DocumentSequenceService,
    private val stockLedgerRepository: StockLedgerRepository,
    private val locationAccessService: LocationAccessService,
    private val notify: Notify,
) {
    fun offerTrade(request: FactoryTradeOfferRequest): FactoryTradeOfferSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.fromLocationId)
        val ref = documentSequenceService.next("FT")

        val id = FactoryTradeOffersTable.insertAndGetId {
            it[FactoryTradeOffersTable.tenantId] = actor.tenantId
            it[FactoryTradeOffersTable.ref] = ref
            it[FactoryTradeOffersTable.fromLocationId] = request.fromLocationId
            it[FactoryTradeOffersTable.toLocationId] = request.toLocationId
            it[FactoryTradeOffersTable.stockItemId] = request.stockItemId
            it[FactoryTradeOffersTable.quantity] = request.quantity
            it[FactoryTradeOffersTable.reason] = request.reason
            it[FactoryTradeOffersTable.offeredByUserId] = actor.userId
            it[FactoryTradeOffersTable.offeredAt] = Instant.now()
            it[FactoryTradeOffersTable.expiresAt] = request.expiresAt
            it[FactoryTradeOffersTable.status] = "PENDING"
        }.value

        // Holders of factory.trade.manage at the receiving factory. This used to address a literal
        // "factory-trade-managers" recipient that matched no one.
        notify.holdersOf(
            permission = "factory.trade.manage",
            locationId = request.toLocationId,
            category = NotificationCategory.APPROVAL_REQUIRED,
            title = "Factory trade offer received",
            body = "Trade offer $ref for ${request.quantity} units has been offered to your factory.",
            linkType = "factory.trade-offer",
            linkRef = id.toString(),
            exclude = actor.userId,
        )

        rowToTradeOfferSummary(FactoryTradeOffersTable.selectAll().andWhere { FactoryTradeOffersTable.id eq id }.single())
    }

    fun respondToTrade(id: UUID, decision: String): FactoryTradeOfferSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val offer = FactoryTradeOffersTable.selectAll().andWhere { FactoryTradeOffersTable.id eq id }.firstOrNull()
            ?: throw ApiErrors.tradeOfferNotFound()
        locationAccessService.assertAccess(offer[FactoryTradeOffersTable.toLocationId])

        if (offer[FactoryTradeOffersTable.status] !in listOf("PENDING", "OPEN")) {
            throw ApiErrors.tradeAlreadyDecided()
        }

        if (decision == "ACCEPTED") {
            val fromLocationId = offer[FactoryTradeOffersTable.fromLocationId]
            val toLocationId = offer[FactoryTradeOffersTable.toLocationId]
            val stockItemId = offer[FactoryTradeOffersTable.stockItemId].value
            val quantity = offer[FactoryTradeOffersTable.quantity]
            val ref = offer[FactoryTradeOffersTable.ref]

            val available = stockLedgerRepository.currentBalance(fromLocationId, stockItemId)
            if (available < quantity) {
                throw ApiErrors.insufficientStockForTrade()
            }

            stockLedgerRepository.postEntry(
                tenantId = actor.tenantId, locationId = fromLocationId, stockItemId = stockItemId,
                sourceType = "TRADE_OUT", sourceReference = ref, qtyOut = quantity, performedByUserId = actor.userId,
            )
            stockLedgerRepository.postEntry(
                tenantId = actor.tenantId, locationId = toLocationId, stockItemId = stockItemId,
                sourceType = "TRADE_IN", sourceReference = ref, qtyIn = quantity, performedByUserId = actor.userId,
            )
        }

        FactoryTradeOffersTable.update({ FactoryTradeOffersTable.id eq id }) {
            it[FactoryTradeOffersTable.status] = decision
            it[FactoryTradeOffersTable.respondedByUserId] = actor.userId
            it[FactoryTradeOffersTable.respondedAt] = Instant.now()
        }

        rowToTradeOfferSummary(FactoryTradeOffersTable.selectAll().andWhere { FactoryTradeOffersTable.id eq id }.single())
    }

    fun listTrades(locationId: UUID?, status: String?): List<FactoryTradeOfferSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilterSet(locationId)
        val query = FactoryTradeOffersTable.selectAll().andWhere { FactoryTradeOffersTable.tenantId eq tenantId }
        if (effectiveLocationId != null) {
            query.andWhere { (FactoryTradeOffersTable.fromLocationId inList effectiveLocationId) or (FactoryTradeOffersTable.toLocationId inList effectiveLocationId) }
        }
        if (status != null) query.andWhere { FactoryTradeOffersTable.status eq status }
        query.orderBy(FactoryTradeOffersTable.offeredAt).map(::rowToTradeOfferSummary)
    }

    private fun rowToTradeOfferSummary(row: ResultRow) = FactoryTradeOfferSummary(
        id = row[FactoryTradeOffersTable.id].value,
        ref = row[FactoryTradeOffersTable.ref],
        fromLocationId = row[FactoryTradeOffersTable.fromLocationId],
        toLocationId = row[FactoryTradeOffersTable.toLocationId],
        stockItemId = row[FactoryTradeOffersTable.stockItemId].value,
        quantity = row[FactoryTradeOffersTable.quantity],
        reason = row[FactoryTradeOffersTable.reason],
        status = row[FactoryTradeOffersTable.status],
        offeredAt = row[FactoryTradeOffersTable.offeredAt],
        expiresAt = row[FactoryTradeOffersTable.expiresAt],
    )
}
