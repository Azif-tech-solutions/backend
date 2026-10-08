package com.zifiq.inventory.service

import com.zifiq.inventory.dto.*
import com.zifiq.inventory.model.QuantityAdjustmentsTable
import com.zifiq.inventory.model.StockTransferLinesTable
import com.zifiq.inventory.model.StockTransfersTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.otp.OtpChallengeService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

@Service
class InventoryOperationsService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val documentSequenceService: DocumentSequenceService,
    private val stockLedgerRepository: StockLedgerRepository,
    private val otpChallengeService: OtpChallengeService,
    private val locationAccessService: LocationAccessService,
    private val moduleSettings: ModuleSettings,
) {
    // ---- Stock Transfer ----

    fun dispatchTransfer(request: StockTransferRequest): StockTransferSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.sourceLocationId)
        val ref = documentSequenceService.next("WB")

        request.lines.forEach { line ->
            val available = stockLedgerRepository.currentBalance(request.sourceLocationId, line.stockItemId)
            if (available < line.quantity) {
                throw ApiErrors.insufficientStockForTransfer(line.stockItemId)
            }
        }

        val transferId = StockTransfersTable.insertAndGetId {
            it[StockTransfersTable.tenantId] = actor.tenantId
            it[StockTransfersTable.waybillRef] = ref
            it[StockTransfersTable.sourceLocationId] = request.sourceLocationId
            it[StockTransfersTable.destinationLocationId] = request.destinationLocationId
            it[StockTransfersTable.driverName] = request.driverName
            it[StockTransfersTable.driverVehicle] = request.driverVehicle
            it[StockTransfersTable.dispatchedByUserId] = actor.userId
            it[StockTransfersTable.dispatchedAt] = Instant.now()
            it[StockTransfersTable.status] = "DISPATCHED"
        }.value

        request.lines.forEach { line ->
            StockTransferLinesTable.insert {
                it[StockTransferLinesTable.stockTransferId] = transferId
                it[StockTransferLinesTable.stockItemId] = line.stockItemId
                it[StockTransferLinesTable.qtySent] = line.quantity
                it[StockTransferLinesTable.unitPrice] = line.unitPrice
            }
            stockLedgerRepository.postEntry(
                tenantId = actor.tenantId,
                locationId = request.sourceLocationId,
                stockItemId = line.stockItemId,
                sourceType = "TRANSFER_OUT",
                sourceReference = ref,
                qtyOut = line.quantity,
                performedByUserId = actor.userId,
            )
        }

        loadTransferSummary(transferId)
    }

    fun receiveTransfer(id: UUID, request: StockTransferReceiveRequest): StockTransferSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val transfer = StockTransfersTable.selectAll().andWhere { StockTransfersTable.id eq id }.firstOrNull()
            ?: throw ApiErrors.transferNotFound()
        locationAccessService.assertAccess(transfer[StockTransfersTable.destinationLocationId])

        request.lines.forEach { line ->
            val transferLine = StockTransferLinesTable.selectAll()
                .andWhere { StockTransferLinesTable.id eq line.stockTransferLineId }
                .firstOrNull() ?: throw ApiErrors.transferLineNotFound()

            StockTransferLinesTable.update({ StockTransferLinesTable.id eq line.stockTransferLineId }) {
                it[StockTransferLinesTable.qtyReceived] = line.qtyReceived
                it[StockTransferLinesTable.qtyRejected] = line.qtyRejected
            }

            stockLedgerRepository.postEntry(
                tenantId = actor.tenantId,
                locationId = transfer[StockTransfersTable.destinationLocationId],
                stockItemId = transferLine[StockTransferLinesTable.stockItemId].value,
                sourceType = "TRANSFER_IN",
                sourceReference = transfer[StockTransfersTable.waybillRef],
                qtyIn = line.qtyReceived,
                performedByUserId = actor.userId,
            )
        }

        val allLines = StockTransferLinesTable.selectAll().andWhere { StockTransferLinesTable.stockTransferId eq id }.toList()
        val fullyReconciled = allLines.all { row ->
            val received = row[StockTransferLinesTable.qtyReceived] ?: BigDecimal.ZERO
            val rejected = row[StockTransferLinesTable.qtyRejected] ?: BigDecimal.ZERO
            received + rejected >= row[StockTransferLinesTable.qtySent]
        }

        StockTransfersTable.update({ StockTransfersTable.id eq id }) {
            it[StockTransfersTable.receivedByUserId] = actor.userId
            it[StockTransfersTable.receivedAt] = Instant.now()
            it[StockTransfersTable.status] = if (fullyReconciled) "RECEIVED" else "PARTIALLY_RECEIVED"
        }

        loadTransferSummary(id)
    }

    fun listTransfers(locationId: UUID?, status: String?): List<StockTransferSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilter(locationId)
        val query = StockTransfersTable.selectAll().andWhere { StockTransfersTable.tenantId eq tenantId }
        if (effectiveLocationId != null) {
            query.andWhere { (StockTransfersTable.sourceLocationId eq effectiveLocationId) or (StockTransfersTable.destinationLocationId eq effectiveLocationId) }
        }
        if (status != null) query.andWhere { StockTransfersTable.status eq status }
        query.map { row -> loadTransferSummary(row[StockTransfersTable.id].value) }
    }

    private fun loadTransferSummary(transferId: UUID): StockTransferSummary {
        val row = StockTransfersTable.selectAll().andWhere { StockTransfersTable.id eq transferId }.single()
        val lines = StockTransferLinesTable.selectAll().andWhere { StockTransferLinesTable.stockTransferId eq transferId }.map {
            StockTransferLineSummary(
                id = it[StockTransferLinesTable.id].value,
                stockItemId = it[StockTransferLinesTable.stockItemId].value,
                qtySent = it[StockTransferLinesTable.qtySent],
                qtyReceived = it[StockTransferLinesTable.qtyReceived],
                qtyRejected = it[StockTransferLinesTable.qtyRejected],
            )
        }
        return StockTransferSummary(
            id = row[StockTransfersTable.id].value,
            waybillRef = row[StockTransfersTable.waybillRef],
            sourceLocationId = row[StockTransfersTable.sourceLocationId],
            destinationLocationId = row[StockTransfersTable.destinationLocationId],
            status = row[StockTransfersTable.status],
            dispatchedAt = row[StockTransfersTable.dispatchedAt],
            receivedAt = row[StockTransfersTable.receivedAt],
            lines = lines,
        )
    }

    // ---- Quantity Adjustment ----

    fun postAdjustment(request: QuantityAdjustmentRequest): QuantityAdjustmentSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.locationId)

        if (request.quantity > moduleSettings.decimal(InventorySettings.ADJUSTMENT_OTP_THRESHOLD_QTY)) {
            val challengeId = request.otpChallengeId
                ?: throw ApiErrors.otpRequiredForAdjustment()
            if (!otpChallengeService.isVerified(challengeId, "QUANTITY_ADJUSTMENT")) {
                throw ApiErrors.otpNotVerified()
            }
        }

        if (request.direction == "NEGATIVE") {
            val available = stockLedgerRepository.currentBalance(request.locationId, request.stockItemId)
            if (available < request.quantity) {
                throw ApiErrors.adjustmentExceedsBalance()
            }
        }

        val ref = documentSequenceService.next("QA")

        QuantityAdjustmentsTable.insert {
            it[QuantityAdjustmentsTable.tenantId] = actor.tenantId
            it[QuantityAdjustmentsTable.locationId] = request.locationId
            it[QuantityAdjustmentsTable.ref] = ref
            it[QuantityAdjustmentsTable.stockItemId] = request.stockItemId
            it[QuantityAdjustmentsTable.direction] = request.direction
            it[QuantityAdjustmentsTable.quantity] = request.quantity
            it[QuantityAdjustmentsTable.reason] = request.reason
            it[QuantityAdjustmentsTable.note] = request.note
            it[QuantityAdjustmentsTable.otpChallengeId] = request.otpChallengeId
            it[QuantityAdjustmentsTable.performedByUserId] = actor.userId
            it[QuantityAdjustmentsTable.createdAt] = Instant.now()
        }

        stockLedgerRepository.postEntry(
            tenantId = actor.tenantId,
            locationId = request.locationId,
            stockItemId = request.stockItemId,
            sourceType = "QUANTITY_ADJUSTMENT",
            sourceReference = ref,
            qtyIn = if (request.direction == "POSITIVE") request.quantity else BigDecimal.ZERO,
            qtyOut = if (request.direction == "NEGATIVE") request.quantity else BigDecimal.ZERO,
            remark = request.note,
            performedByUserId = actor.userId,
        )

        QuantityAdjustmentSummary(ref, request.stockItemId, request.direction, request.quantity, request.reason, request.note, actor.userId, Instant.now())
    }

    fun listAdjustments(locationId: UUID?): List<QuantityAdjustmentSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val effectiveLocationId = locationAccessService.resolveFilter(locationId)
        val query = QuantityAdjustmentsTable.selectAll().andWhere { QuantityAdjustmentsTable.tenantId eq tenantId }
        if (effectiveLocationId != null) query.andWhere { QuantityAdjustmentsTable.locationId eq effectiveLocationId }
        query.orderBy(QuantityAdjustmentsTable.createdAt).map { row ->
            QuantityAdjustmentSummary(
                ref = row[QuantityAdjustmentsTable.ref],
                stockItemId = row[QuantityAdjustmentsTable.stockItemId].value,
                direction = row[QuantityAdjustmentsTable.direction],
                quantity = row[QuantityAdjustmentsTable.quantity],
                reason = row[QuantityAdjustmentsTable.reason],
                note = row[QuantityAdjustmentsTable.note],
                performedBy = row[QuantityAdjustmentsTable.performedByUserId].value,
                at = row[QuantityAdjustmentsTable.createdAt],
            )
        }
    }
}
