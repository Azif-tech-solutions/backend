package com.zifiq.inventory.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.inventory.dto.*
import com.zifiq.inventory.model.QuantityAdjustmentsTable
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.model.StockTransferLinesTable
import com.zifiq.inventory.model.StockTransfersTable
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.platform.documents.DocumentRegistry
import com.zifiq.platform.documents.DocumentType
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.otp.OtpChallengeService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.LocationHierarchy
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
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
    private val documentRegistry: DocumentRegistry,
    private val stockLocations: StockLocations,
    private val hierarchy: LocationHierarchy,
    private val driverService: DriverService,
    private val domainEvents: DomainEvents,
    private val notify: Notify,
) {
    private fun today(): LocalDate = LocalDate.now(moduleSettings.zone())

    // ---- Stock Transfer ----

    /**
     * Dispatch: stock leaves the source now (from the area chosen per line) and reaches the
     * destination only on confirmed receipt (PRD 5.2.7, docs/05 §4.3). Moving stock between areas of
     * one location is an area move, not a transfer.
     */
    fun dispatchTransfer(request: StockTransferRequest): StockTransferSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.sourceLocationId)
        val source = stockLocations.requireTrading(request.sourceLocationId)
        val destination = stockLocations.requireCanReceive(request.destinationLocationId)
        if (source.rootId == destination.rootId) throw ApiErrors.sameLocation()
        if (destination.isArea) throw ApiErrors.invalidLocation("Send to the showroom or factory; the receiver chooses the area on receipt")

        val driver = request.driverId?.let(driverService::activeDriver)
        val transferDate = request.transferDate ?: today()
        if (transferDate.isAfter(today())) throw ApiErrors.validationFailed(mapOf("transferDate" to "cannot be in the future"))

        // Availability per (area, item), so two lines drawing on the same stock are checked together.
        val fromLocations = request.lines.map { line ->
            val from = line.fromLocationId ?: request.sourceLocationId
            stockLocations.requireWithin(source.rootId, from, "fromLocationId")
            from
        }
        request.lines.zip(fromLocations).groupBy({ (line, from) -> from to line.stockItemId }, { (line, _) -> line.quantity })
            .forEach { (key, quantities) ->
                val (from, itemId) = key
                requireActiveItem(itemId)
                if (stockLedgerRepository.currentBalance(from, itemId) < quantities.fold(BigDecimal.ZERO, BigDecimal::add)) {
                    throw ApiErrors.insufficientStockForTransfer(itemId)
                }
            }

        val ref = documentSequenceService.next("WB")
        val transferId = StockTransfersTable.insertAndGetId {
            it[StockTransfersTable.tenantId] = actor.tenantId
            it[StockTransfersTable.waybillRef] = ref
            it[StockTransfersTable.sourceLocationId] = request.sourceLocationId
            it[StockTransfersTable.destinationLocationId] = request.destinationLocationId
            it[StockTransfersTable.driverId] = driver?.id
            it[StockTransfersTable.driverName] = driver?.name ?: request.driverName?.trim()?.ifEmpty { null }
            it[StockTransfersTable.driverVehicle] = driver?.vehicleNumber ?: request.driverVehicle?.trim()?.ifEmpty { null }
            it[StockTransfersTable.transferDate] = transferDate
            it[StockTransfersTable.dispatchedByUserId] = actor.userId
            it[StockTransfersTable.dispatchedAt] = Instant.now()
            it[StockTransfersTable.status] = "DISPATCHED"
        }.value

        request.lines.zip(fromLocations).forEach { (line, from) ->
            StockTransferLinesTable.insert {
                it[StockTransferLinesTable.stockTransferId] = transferId
                it[StockTransferLinesTable.stockItemId] = line.stockItemId
                it[StockTransferLinesTable.qtySent] = line.quantity
                it[StockTransferLinesTable.unitPrice] = line.unitPrice
                it[StockTransferLinesTable.fromLocationId] = from.takeIf { id -> id != request.sourceLocationId }
                it[StockTransferLinesTable.batchNo] = line.batchNo?.trim()?.ifEmpty { null }
            }
            stockLedgerRepository.postEntry(
                tenantId = actor.tenantId,
                locationId = from,
                stockItemId = line.stockItemId,
                sourceType = "TRANSFER_OUT",
                sourceReference = ref,
                qtyOut = line.quantity,
                remark = "Transfer to ${destination.name}",
                performedByUserId = actor.userId,
                entryDate = transferDate,
            )
        }

        documentRegistry.register(
            ref = ref,
            docType = DocumentType.WAYBILL,
            sourceModule = "inventory",
            sourceId = transferId,
            locationId = request.sourceLocationId,
            partyName = destination.name,
        )
        val units = request.lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.quantity }
        domainEvents.record(
            type = "inventory.transfer.dispatched",
            aggregateType = "stock-transfer",
            aggregateId = transferId,
            aggregateRef = ref,
            summary = "$ref dispatched: ${units.stripTrailingZeros().toPlainString()} unit(s) from ${source.label} to ${destination.name}",
            payload = mapOf("lines" to request.lines.size, "units" to units, "destinationLocationId" to request.destinationLocationId),
            locationId = request.sourceLocationId,
        )
        loadTransferSummary(transferId)
    }

    /**
     * Receipt (docs/05 §4.2): accepted goods enter the destination (or the chosen area); rejected
     * goods go straight back to where they were dispatched from, so nothing vanishes from the
     * books. Lines can be received in several goes; each line's received + rejected never exceeds
     * what was sent.
     */
    fun receiveTransfer(id: UUID, request: StockTransferReceiveRequest): StockTransferSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val transfer = StockTransfersTable.selectAll()
            .andWhere { (StockTransfersTable.id eq id) and (StockTransfersTable.tenantId eq actor.tenantId) }
            .forUpdate()
            .firstOrNull() ?: throw ApiErrors.transferNotFound()
        val destinationId = transfer[StockTransfersTable.destinationLocationId]
        val sourceId = transfer[StockTransfersTable.sourceLocationId]
        locationAccessService.assertAccess(destinationId)
        val status = transfer[StockTransfersTable.status]
        if (status !in setOf("DISPATCHED", "PARTIALLY_RECEIVED")) throw ApiErrors.invalidStatusTransition(status, "RECEIVED")
        val destination = stockLocations.requireCanReceive(destinationId)
        val ref = transfer[StockTransfersTable.waybillRef]
        val receivedDate = request.receivedDate ?: today()
        if (receivedDate.isAfter(today())) throw ApiErrors.validationFailed(mapOf("receivedDate" to "cannot be in the future"))
        if (receivedDate.isBefore(transfer[StockTransfersTable.transferDate])) {
            throw ApiErrors.validationFailed(mapOf("receivedDate" to "cannot be before the transfer date"))
        }
        if (request.lines.map { it.stockTransferLineId }.toSet().size != request.lines.size) {
            throw ApiErrors.validationFailed(mapOf("lines" to "Each transfer line may appear once"))
        }
        val requireFull = moduleSettings.boolean(InventorySettings.RECEIVE_REQUIRE_FULL_RECONCILIATION)

        val lines = StockTransferLinesTable.selectAll()
            .andWhere { StockTransferLinesTable.stockTransferId eq id }
            .orderBy(StockTransferLinesTable.id to SortOrder.ASC)
            .forUpdate()
            .toList()
        var rejectedTotal = BigDecimal.ZERO
        request.lines.forEachIndexed { index, input ->
            val lineNo = index + 1
            val line = lines.firstOrNull { it[StockTransferLinesTable.id].value == input.stockTransferLineId }
                ?: throw ApiErrors.transferLineNotFound()
            val alreadyReceived = line[StockTransferLinesTable.qtyReceived] ?: BigDecimal.ZERO
            val alreadyRejected = line[StockTransferLinesTable.qtyRejected] ?: BigDecimal.ZERO
            val outstanding = line[StockTransferLinesTable.qtySent] - alreadyReceived - alreadyRejected
            val rejected = input.qtyRejected ?: BigDecimal.ZERO
            val attempted = input.qtyReceived + rejected
            if (attempted > outstanding) throw ApiErrors.receiptExceedsSent(lineNo, outstanding, attempted)
            if (requireFull && attempted.compareTo(outstanding) != 0) throw ApiErrors.receiptNotReconciled(lineNo, outstanding, attempted)
            if (rejected.signum() > 0 && input.rejectionNote.isNullOrBlank()) {
                throw ApiErrors.validationFailed(mapOf("lines[$index].rejectionNote" to "is required when goods are rejected"))
            }
            val into = input.receivedIntoLocationId ?: destinationId
            stockLocations.requireWithin(destination.rootId, into, "receivedIntoLocationId")
            val itemId = line[StockTransferLinesTable.stockItemId].value

            StockTransferLinesTable.update({ StockTransferLinesTable.id eq input.stockTransferLineId }) {
                it[qtyReceived] = alreadyReceived + input.qtyReceived
                it[qtyRejected] = alreadyRejected + rejected
                if (input.qtyReceived.signum() > 0) it[receivedIntoLocationId] = into.takeIf { area -> area != destinationId }
                input.rejectionNote?.trim()?.ifEmpty { null }?.let { note -> it[rejectionNote] = note }
            }
            if (input.qtyReceived.signum() > 0) {
                stockLedgerRepository.postEntry(
                    tenantId = actor.tenantId,
                    locationId = into,
                    stockItemId = itemId,
                    sourceType = "TRANSFER_IN",
                    sourceReference = ref,
                    qtyIn = input.qtyReceived,
                    remark = "Received from ${stockLocations.get(sourceId).name}",
                    performedByUserId = actor.userId,
                    entryDate = receivedDate,
                )
            }
            if (rejected.signum() > 0) {
                stockLedgerRepository.postEntry(
                    tenantId = actor.tenantId,
                    locationId = line[StockTransferLinesTable.fromLocationId] ?: sourceId,
                    stockItemId = itemId,
                    sourceType = "TRANSFER_REJECTED_IN",
                    sourceReference = ref,
                    qtyIn = rejected,
                    remark = "Rejected by ${destination.rootName} — ${input.rejectionNote!!.trim()}".take(300),
                    performedByUserId = actor.userId,
                    entryDate = receivedDate,
                )
                rejectedTotal += rejected
            }
        }

        val reconciled = StockTransferLinesTable.selectAll().andWhere { StockTransferLinesTable.stockTransferId eq id }.all { row ->
            (row[StockTransferLinesTable.qtyReceived] ?: BigDecimal.ZERO) + (row[StockTransferLinesTable.qtyRejected] ?: BigDecimal.ZERO) >=
                row[StockTransferLinesTable.qtySent]
        }
        val newStatus = if (reconciled) "RECEIVED" else "PARTIALLY_RECEIVED"
        StockTransfersTable.update({ StockTransfersTable.id eq id }) {
            it[StockTransfersTable.receivedByUserId] = actor.userId
            it[StockTransfersTable.receivedAt] = Instant.now()
            it[StockTransfersTable.receivedDate] = receivedDate
            request.note?.trim()?.ifEmpty { null }?.let { note -> it[StockTransfersTable.receiptNote] = note }
            it[StockTransfersTable.status] = newStatus
        }

        val received = request.lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.qtyReceived }
        domainEvents.record(
            type = "inventory.transfer.received",
            aggregateType = "stock-transfer",
            aggregateId = id,
            aggregateRef = ref,
            summary = "$ref ${newStatus.lowercase().replace('_', ' ')} at ${destination.rootName}: ${received.stripTrailingZeros().toPlainString()} accepted" +
                if (rejectedTotal.signum() > 0) ", ${rejectedTotal.stripTrailingZeros().toPlainString()} rejected" else "",
            payload = mapOf("receivedQty" to received, "rejectedQty" to rejectedTotal, "status" to newStatus),
            locationId = destinationId,
        )
        if (rejectedTotal.signum() > 0) {
            val source = stockLocations.get(sourceId)
            notify.holdersOf(
                permission = "inventory.transfers.manage",
                locationId = source.rootId,
                category = NotificationCategory.ALERT,
                title = "Goods rejected on $ref",
                body = "$ref: ${rejectedTotal.stripTrailingZeros().toPlainString()} unit(s) rejected by ${destination.rootName} — returned to ${source.label}.",
                linkType = "inventory.stock-transfer",
                linkRef = id.toString(),
                exclude = actor.userId,
            )
        }
        loadTransferSummary(id)
    }

    /**
     * Transfers in the caller's scope, newest first. `status` may be repeated or comma-separated
     * ("incoming consignments" = DISPATCHED,PARTIALLY_RECEIVED to a showroom); `direction` limits to
     * transfers INTO or OUT_OF the location.
     */
    fun listTransfers(
        locationId: UUID?,
        statuses: Set<String>,
        direction: String?,
        q: String?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<StockTransferSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val scope = locationAccessService.resolveFilterSet(locationId)
        val query = StockTransfersTable.selectAll().andWhere { StockTransfersTable.tenantId eq tenantId }
        if (scope != null) {
            when (direction?.uppercase()) {
                "INTO" -> query.andWhere { StockTransfersTable.destinationLocationId inList scope }
                "OUT_OF" -> query.andWhere { StockTransfersTable.sourceLocationId inList scope }
                else -> query.andWhere { (StockTransfersTable.sourceLocationId inList scope) or (StockTransfersTable.destinationLocationId inList scope) }
            }
        }
        if (statuses.isNotEmpty()) query.andWhere { StockTransfersTable.status inList statuses.map { it.uppercase() } }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            query.andWhere {
                RawOp {
                    +"lower("; +StockTransfersTable.waybillRef; +") LIKE '%' || lower("
                    registerArgument(org.jetbrains.exposed.sql.TextColumnType(), com.zifiq.common.search.escapeLike(text)); +") || '%'"
                }
            }
        }
        from?.let { query.andWhere { StockTransfersTable.transferDate greaterEq it } }
        to?.let { query.andWhere { StockTransfersTable.transferDate lessEq it } }
        query.orderBy(StockTransfersTable.transferDate to SortOrder.DESC, StockTransfersTable.dispatchedAt to SortOrder.DESC)
            .limit(500)
            .map { row -> loadTransferSummary(row[StockTransfersTable.id].value) }
    }

    fun getTransfer(id: UUID): StockTransferSummary = tenantScopedTransaction.execute {
        val summary = loadTransferSummary(id)
        requireTransferVisible(summary)
        summary
    }

    /** Either end of the transfer must be in the caller's scope; otherwise it reads as not found. */
    fun requireTransferVisible(summary: StockTransferSummary) {
        val allowed = locationAccessService.allowedLocationIds() ?: return
        if (summary.sourceLocationId !in allowed && summary.destinationLocationId !in allowed) throw ApiErrors.transferNotFound()
    }

    fun loadTransferSummary(transferId: UUID): StockTransferSummary {
        val tenantId = currentActorProvider.current().tenantId
        val row = StockTransfersTable.selectAll()
            .andWhere { (StockTransfersTable.id eq transferId) and (StockTransfersTable.tenantId eq tenantId) }
            .firstOrNull() ?: throw ApiErrors.transferNotFound()
        val source = stockLocations.get(row[StockTransfersTable.sourceLocationId])
        val destination = stockLocations.get(row[StockTransfersTable.destinationLocationId])
        val areaNames = mutableMapOf<UUID, String>()
        fun areaName(id: UUID?) = id?.let { areaNames.getOrPut(it) { stockLocations.get(it).name } }
        val unitName = RawExpression<String>({ +"(select u.name from units_of_measure u where u.id = "; +StockItemsTable.unitOfMeasureId; +")" })
        val lines = StockTransferLinesTable
            .join(StockItemsTable, JoinType.INNER, StockTransferLinesTable.stockItemId, StockItemsTable.id)
            .select(StockTransferLinesTable.columns + StockItemsTable.code + StockItemsTable.name + unitName)
            .where { StockTransferLinesTable.stockTransferId eq transferId }
            .orderBy(StockTransferLinesTable.id to SortOrder.ASC)
            .map {
                val sent = it[StockTransferLinesTable.qtySent]
                val received = it[StockTransferLinesTable.qtyReceived]
                val rejected = it[StockTransferLinesTable.qtyRejected]
                StockTransferLineSummary(
                    id = it[StockTransferLinesTable.id].value,
                    stockItemId = it[StockTransferLinesTable.stockItemId].value,
                    stockItemCode = it[StockItemsTable.code],
                    stockItemName = it[StockItemsTable.name],
                    unit = it.getOrNull(unitName),
                    qtySent = sent,
                    qtyReceived = received,
                    qtyRejected = rejected,
                    outstanding = sent - (received ?: BigDecimal.ZERO) - (rejected ?: BigDecimal.ZERO),
                    fromLocationId = it[StockTransferLinesTable.fromLocationId],
                    fromLocationName = areaName(it[StockTransferLinesTable.fromLocationId]),
                    receivedIntoLocationId = it[StockTransferLinesTable.receivedIntoLocationId],
                    receivedIntoLocationName = areaName(it[StockTransferLinesTable.receivedIntoLocationId]),
                    batchNo = it[StockTransferLinesTable.batchNo],
                    rejectionNote = it[StockTransferLinesTable.rejectionNote],
                )
            }
        return StockTransferSummary(
            id = row[StockTransfersTable.id].value,
            waybillRef = row[StockTransfersTable.waybillRef],
            sourceLocationId = source.id,
            sourceLocationName = source.label,
            sourceLocationType = source.rootType,
            destinationLocationId = destination.id,
            destinationLocationName = destination.label,
            destinationLocationType = destination.rootType,
            status = row[StockTransfersTable.status],
            driverId = row[StockTransfersTable.driverId],
            driverName = row[StockTransfersTable.driverName],
            driverVehicle = row[StockTransfersTable.driverVehicle],
            transferDate = row[StockTransfersTable.transferDate],
            dispatchedAt = row[StockTransfersTable.dispatchedAt],
            receivedDate = row[StockTransfersTable.receivedDate],
            receivedAt = row[StockTransfersTable.receivedAt],
            receiptNote = row[StockTransfersTable.receiptNote],
            totalSent = lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.qtySent },
            totalRejected = lines.fold(BigDecimal.ZERO) { sum, line -> sum + (line.qtyRejected ?: BigDecimal.ZERO) },
            waybillUrl = "/api/v1/inventory/stock/transfers/$transferId/waybill.pdf",
            lines = lines,
        )
    }

    private fun requireActiveItem(itemId: UUID) {
        val row = StockItemsTable.selectAll().andWhere { StockItemsTable.id eq itemId }.firstOrNull() ?: throw ApiErrors.stockItemNotFound()
        if (row[StockItemsTable.status] != "ACTIVE") throw ApiErrors.stockItemSuspended()
    }

    // ---- Quantity Adjustment ----

    /**
     * Stock Keeper corrections (PRD rule 4), OTP-gated above the threshold (rule 8). COUNTED mode
     * locks the balance and works out the difference itself, so a count can't race a sale.
     */
    fun postAdjustment(request: QuantityAdjustmentRequest): QuantityAdjustmentSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        locationAccessService.assertAccess(request.locationId)
        stockLocations.requireCanReceive(request.locationId)
        requireActiveItem(request.stockItemId)
        val mode = request.mode ?: "DELTA"
        val onHand = stockLedgerRepository.lockedBalance(actor.tenantId, request.locationId, request.stockItemId)

        val (direction, quantity) = if (mode == "COUNTED") {
            val counted = request.countedQuantity ?: throw ApiErrors.validationFailed(mapOf("countedQuantity" to "is required in COUNTED mode"))
            val variance = counted - onHand
            if (variance.signum() == 0) throw ApiErrors.noAdjustmentNeeded()
            (if (variance.signum() > 0) "POSITIVE" else "NEGATIVE") to variance.abs()
        } else {
            val direction = request.direction ?: throw ApiErrors.validationFailed(mapOf("direction" to "is required"))
            val quantity = request.quantity ?: throw ApiErrors.validationFailed(mapOf("quantity" to "is required"))
            direction to quantity
        }

        if (quantity > moduleSettings.decimal(InventorySettings.ADJUSTMENT_OTP_THRESHOLD_QTY)) {
            val challengeId = request.otpChallengeId ?: throw ApiErrors.otpRequiredForAdjustment()
            if (!otpChallengeService.isVerified(challengeId, "QUANTITY_ADJUSTMENT")) throw ApiErrors.otpNotVerified()
        }
        if (direction == "NEGATIVE" && onHand < quantity) throw ApiErrors.adjustmentExceedsBalance()

        val ref = documentSequenceService.next("QA")
        val newQuantity = if (direction == "POSITIVE") onHand + quantity else onHand - quantity
        val id = QuantityAdjustmentsTable.insertAndGetId {
            it[QuantityAdjustmentsTable.tenantId] = actor.tenantId
            it[QuantityAdjustmentsTable.locationId] = request.locationId
            it[QuantityAdjustmentsTable.ref] = ref
            it[QuantityAdjustmentsTable.stockItemId] = request.stockItemId
            it[QuantityAdjustmentsTable.direction] = direction
            it[QuantityAdjustmentsTable.quantity] = quantity
            it[QuantityAdjustmentsTable.reason] = request.reason
            it[QuantityAdjustmentsTable.note] = request.note?.trim()?.ifEmpty { null }
            it[QuantityAdjustmentsTable.otpChallengeId] = request.otpChallengeId
            it[QuantityAdjustmentsTable.performedByUserId] = actor.userId
            it[QuantityAdjustmentsTable.createdAt] = Instant.now()
            it[QuantityAdjustmentsTable.mode] = mode
            it[QuantityAdjustmentsTable.recordedQuantity] = onHand
            it[QuantityAdjustmentsTable.newQuantity] = newQuantity
        }.value

        stockLedgerRepository.postEntry(
            tenantId = actor.tenantId,
            locationId = request.locationId,
            stockItemId = request.stockItemId,
            sourceType = "QUANTITY_ADJUSTMENT",
            sourceReference = ref,
            qtyIn = if (direction == "POSITIVE") quantity else BigDecimal.ZERO,
            qtyOut = if (direction == "NEGATIVE") quantity else BigDecimal.ZERO,
            remark = request.note?.trim()?.ifEmpty { null } ?: request.reason.lowercase().replace('_', ' '),
            performedByUserId = actor.userId,
        )
        domainEvents.record(
            type = "inventory.adjustment.posted",
            aggregateType = "quantity-adjustment",
            aggregateId = id,
            aggregateRef = ref,
            summary = "$ref: ${if (direction == "POSITIVE") "+" else "−"}${quantity.stripTrailingZeros().toPlainString()} (${request.reason.lowercase().replace('_', ' ')})",
            payload = mapOf("direction" to direction, "quantity" to quantity, "reason" to request.reason, "mode" to mode),
            locationId = request.locationId,
        )
        val joined = adjustmentsJoined()
        adjustmentRow(joined.select(joined.columns + adjustmentUnit).where { QuantityAdjustmentsTable.id eq id }.single())
    }

    private val adjustmentArea = LocationsTable.alias("adj_location")
    private val adjustmentRoot = LocationsTable.alias("adj_root")

    private fun adjustmentsJoined() = QuantityAdjustmentsTable
        .join(StockItemsTable, JoinType.INNER, QuantityAdjustmentsTable.stockItemId, StockItemsTable.id)
        .join(adjustmentArea, JoinType.INNER, QuantityAdjustmentsTable.locationId, adjustmentArea[LocationsTable.id])
        .join(adjustmentRoot, JoinType.LEFT, adjustmentArea[LocationsTable.parentLocationId], adjustmentRoot[LocationsTable.id])
        .join(AppUsersTable, JoinType.LEFT, QuantityAdjustmentsTable.performedByUserId, AppUsersTable.id)

    private val adjustmentUnit = RawExpression<String>({ +"(select u.name from units_of_measure u where u.id = "; +StockItemsTable.unitOfMeasureId; +")" })

    private val adjustmentFilters = FilterSpec(
        fields = listOf(
            FilterField("locationId", "Location", FilterType.LOCATION, QuantityAdjustmentsTable.locationId, aliases = setOf("location"), facetable = true),
            FilterField("direction", "Direction", FilterType.ENUM, QuantityAdjustmentsTable.direction, listOf(FilterOption("POSITIVE", "Added"), FilterOption("NEGATIVE", "Removed")), facetable = true),
            FilterField("reason", "Reason", FilterType.ENUM, QuantityAdjustmentsTable.reason,
                listOf("STOCKTAKE_VARIANCE", "DAMAGED_BROKEN", "EXPIRED_WRITEOFF", "SUSPECTED_THEFT", "MISCOUNT_CORRECTION", "UNRECORDED_RETURN", "OTHER")
                    .map { FilterOption(it, it.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)) }, facetable = true),
            FilterField("mode", "Mode", FilterType.ENUM, QuantityAdjustmentsTable.mode, listOf(FilterOption("DELTA", "By quantity"), FilterOption("COUNTED", "By count"))),
            FilterField("stockItemId", "Product", FilterType.UUID, QuantityAdjustmentsTable.stockItemId, aliases = setOf("item")),
            FilterField("performedBy", "Adjusted by", FilterType.UUID, QuantityAdjustmentsTable.performedByUserId),
            FilterField("otpVerified", "OTP verified", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +QuantityAdjustmentsTable.otpChallengeId; +" IS NOT NULL)" })),
            FilterField("quantity", "Quantity", FilterType.NUMBER, QuantityAdjustmentsTable.quantity, sortable = true),
            FilterField("createdAt", "Date", FilterType.DATETIME, QuantityAdjustmentsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "createdAt",
        defaultSort = listOf("createdAt" to SortOrder.DESC),
        tiebreaker = QuantityAdjustmentsTable.id,
    )

    private val adjustmentSearch = SearchSpec(
        fields = listOf(
            SearchField(QuantityAdjustmentsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(StockItemsTable.code, SearchKind.CODE, "Product code"),
            SearchField(StockItemsTable.name, SearchKind.TRIGRAM, "Product"),
            SearchField(RawExpression<String>({ +"quantity_adjustments.search_vector" }), SearchKind.FULLTEXT, "Note"),
        ),
        qualifiers = adjustmentFilters,
    )

    private fun adjustmentContext() = FilterContext(costVisible = true, zone = moduleSettings.zone(), expandLocations = hierarchy::withAreas)

    fun adjustmentFilterMetadata(): FilterMetadata = tenantScopedTransaction.execute { adjustmentFilters.metadata(adjustmentContext()) }

    /**
     * Adjustments Report (docs/05 §4.6): a showroom/factory includes its areas; no location = every
     * location in the caller's scope (the "general" report). Summary: count, added, removed, net.
     */
    fun listAdjustments(request: ListSearchRequest): PageResult<QuantityAdjustmentSummary> = tenantScopedTransaction.execute {
        val context = adjustmentContext()
        val parsed = adjustmentSearch.parse(request.q)
        val parts = mutableListOf<Op<Boolean>>(QuantityAdjustmentsTable.tenantId eq currentActorProvider.current().tenantId)
        locationAccessService.allowedLocationIds()?.let { parts += QuantityAdjustmentsTable.locationId inList it }
        adjustmentSearch.condition(parsed)?.let { parts += it }
        adjustmentFilters.fromParams(request.params, context)?.let { parts += it }
        adjustmentFilters.fromJson(request.filter, context)?.let { parts += it }
        val condition = parts.allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) adjustmentSearch.rank(parsed) + adjustmentFilters.sort(emptyList(), context)
        else adjustmentFilters.sort(request.sortParams, context)
        val joined = adjustmentsJoined()
        val query = joined.select(joined.columns + adjustmentUnit).where(condition).orderBy(*sort.toTypedArray())
        val page = Paging.fetch(query, PageRequest.of(request.page, request.size), ::adjustmentRow)

        val summary = if (request.summary) {
            val count = RawExpression<Long>({ +"count(*)" })
            val added = RawExpression<BigDecimal>({ +"coalesce(sum("; +QuantityAdjustmentsTable.quantity; +") filter (where "; +QuantityAdjustmentsTable.direction; +" = 'POSITIVE'), 0)" })
            val removed = RawExpression<BigDecimal>({ +"coalesce(sum("; +QuantityAdjustmentsTable.quantity; +") filter (where "; +QuantityAdjustmentsTable.direction; +" = 'NEGATIVE'), 0)" })
            val row = joined.select(count, added, removed).where(condition).single()
            val qtyAdded = BigDecimal(row[added].toString())
            val qtyRemoved = BigDecimal(row[removed].toString())
            mapOf("count" to (row[count] as Number).toLong(), "qtyAdded" to qtyAdded, "qtyRemoved" to qtyRemoved, "netEffect" to qtyAdded - qtyRemoved)
        } else null
        page.copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    private fun adjustmentRow(row: ResultRow): QuantityAdjustmentSummary {
        val areaName = row[adjustmentArea[LocationsTable.name]]
        val rootName = row.getOrNull(adjustmentRoot[LocationsTable.name])
        return QuantityAdjustmentSummary(
            id = row[QuantityAdjustmentsTable.id].value,
            ref = row[QuantityAdjustmentsTable.ref],
            locationId = row[QuantityAdjustmentsTable.locationId],
            locationName = areaName,
            rootLocationName = rootName ?: areaName,
            stockItemId = row[QuantityAdjustmentsTable.stockItemId].value,
            productCode = row[StockItemsTable.code],
            productName = row[StockItemsTable.name],
            unit = row.getOrNull(adjustmentUnit),
            mode = row[QuantityAdjustmentsTable.mode],
            direction = row[QuantityAdjustmentsTable.direction],
            quantity = row[QuantityAdjustmentsTable.quantity],
            recordedQuantity = row[QuantityAdjustmentsTable.recordedQuantity],
            newQuantity = row[QuantityAdjustmentsTable.newQuantity],
            reason = row[QuantityAdjustmentsTable.reason],
            note = row[QuantityAdjustmentsTable.note],
            otpVerified = row[QuantityAdjustmentsTable.otpChallengeId] != null,
            performedBy = row[QuantityAdjustmentsTable.performedByUserId].value,
            performedByName = row.getOrNull(AppUsersTable.displayName),
            at = row[QuantityAdjustmentsTable.createdAt],
        )
    }
}
