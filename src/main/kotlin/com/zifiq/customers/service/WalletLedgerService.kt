package com.zifiq.customers.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.customers.model.CustomerLedgerEntriesTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerDirection
import com.zifiq.customers.spi.LedgerEntryRef
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.LedgerPosting
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service

/**
 * The only code path that changes a wallet or deposit balance (docs/04 §5.2). Balances on
 * `customers` are a cache of the ledger: each change locks the customer row FOR UPDATE, appends a
 * ledger row with the running balance, and updates the cache in the same transaction — the
 * customer equivalent of StockLedgerRepository. The database's non-negative checks back PRD rule 5
 * even if this code were wrong.
 */
@Service
class WalletLedgerService(
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val domainEvents: DomainEvents,
) : WalletLedger {
    override fun credit(posting: LedgerPosting): LedgerEntryRef {
        if (posting.entryType.direction != LedgerDirection.CREDIT) throw ApiErrors.validationFailed(mapOf("entryType" to "${posting.entryType} is a debit"))
        return post(posting, reversesEntryId = null)
    }

    override fun debit(posting: LedgerPosting): LedgerEntryRef {
        if (posting.entryType.direction != LedgerDirection.DEBIT) throw ApiErrors.validationFailed(mapOf("entryType" to "${posting.entryType} is a credit"))
        return post(posting, reversesEntryId = null)
    }

    override fun reverse(entryId: UUID, reason: String, sourceModule: String): LedgerEntryRef {
        val entry = CustomerLedgerEntriesTable.selectAll()
            .where { (CustomerLedgerEntriesTable.id eq entryId) and (CustomerLedgerEntriesTable.tenantId eq tenantContext.requireTenantId()) }
            .forUpdate()
            .firstOrNull() ?: throw ApiErrors.ledgerEntryNotFound()

        val entryModule = entry[CustomerLedgerEntriesTable.sourceModule]
        if (entryModule != sourceModule) throw ApiErrors.reverseInSourceModule(entryModule)
        val type = LedgerEntryType.valueOf(entry[CustomerLedgerEntriesTable.entryType])
        if (type == LedgerEntryType.REVERSAL_CREDIT || type == LedgerEntryType.REVERSAL_DEBIT) {
            throw ApiErrors.invalidStatusTransition(type.name, "REVERSED")
        }
        val alreadyReversed = !CustomerLedgerEntriesTable.selectAll()
            .where { CustomerLedgerEntriesTable.reversesEntryId eq entryId }
            .empty()
        if (alreadyReversed) throw ApiErrors.alreadyReversed()

        val reversalType = if (type.direction == LedgerDirection.CREDIT) LedgerEntryType.REVERSAL_DEBIT else LedgerEntryType.REVERSAL_CREDIT
        return post(
            LedgerPosting(
                customerId = entry[CustomerLedgerEntriesTable.customerId].value,
                balanceType = BalanceType.valueOf(entry[CustomerLedgerEntriesTable.balanceType]),
                entryType = reversalType,
                amount = entry[CustomerLedgerEntriesTable.amount],
                reference = entry[CustomerLedgerEntriesTable.reference],
                description = "Reversal of ${entry[CustomerLedgerEntriesTable.reference]}: $reason".take(300),
                paymentMethod = entry[CustomerLedgerEntriesTable.paymentMethod],
                sourceModule = sourceModule,
                sourceType = entry[CustomerLedgerEntriesTable.sourceType],
                sourceId = entry[CustomerLedgerEntriesTable.sourceId],
                locationId = entry[CustomerLedgerEntriesTable.locationId],
                reason = reason,
            ),
            reversesEntryId = entryId,
        )
    }

    private fun post(posting: LedgerPosting, reversesEntryId: UUID?): LedgerEntryRef {
        val amount = posting.amount.setScale(2, RoundingMode.HALF_UP)
        if (amount <= BigDecimal.ZERO) throw ApiErrors.validationFailed(mapOf("amount" to "must be greater than zero"))
        val isReversal = reversesEntryId != null
        if (!isReversal && posting.entryType.name.startsWith("ADJUSTMENT") && posting.reason.isNullOrBlank()) {
            throw ApiErrors.validationFailed(mapOf("reason" to "is required for adjustments"))
        }

        val tenantId = tenantContext.requireTenantId()
        val customer = CustomersTable.selectAll()
            .where { (CustomersTable.id eq posting.customerId) and (CustomersTable.tenantId eq tenantId) }
            .forUpdate()
            .firstOrNull() ?: throw ApiErrors.customerNotFound()
        val type = CustomerType.valueOf(customer[CustomersTable.type])
        val status = CustomerStatus.valueOf(customer[CustomersTable.status])

        // Corrections must always be possible; everything else needs a transactable customer.
        if (!isReversal && status in setOf(CustomerStatus.SUSPENDED, CustomerStatus.REMOVED)) {
            throw ApiErrors.customerNotActive(status.name)
        }
        checkBalanceType(type, posting)

        val balanceColumn = if (posting.balanceType == BalanceType.WALLET) CustomersTable.walletBalance else CustomersTable.depositBalance
        val current = customer[balanceColumn]
        val credit = posting.entryType.direction == LedgerDirection.CREDIT
        val newBalance = if (credit) current + amount else current - amount
        if (newBalance < BigDecimal.ZERO) {
            throw if (posting.balanceType == BalanceType.WALLET) ApiErrors.insufficientWalletBalance(current, amount)
            else ApiErrors.insufficientDepositBalance(current, amount)
        }

        val now = Instant.now()
        val entryId = CustomerLedgerEntriesTable.insertAndGetId {
            it[CustomerLedgerEntriesTable.tenantId] = tenantId
            it[customerId] = posting.customerId
            it[balanceType] = posting.balanceType.name
            it[entryType] = posting.entryType.name
            it[direction] = posting.entryType.direction.name
            it[CustomerLedgerEntriesTable.amount] = amount
            it[balanceAfter] = newBalance
            it[reference] = posting.reference.take(40)
            it[description] = posting.description.take(300)
            it[paymentMethod] = posting.paymentMethod
            it[sourceModule] = posting.sourceModule
            it[sourceType] = posting.sourceType
            it[sourceId] = posting.sourceId
            it[locationId] = posting.locationId
            it[CustomerLedgerEntriesTable.reversesEntryId] = reversesEntryId
            it[reason] = posting.reason
            it[performedByUserId] = actorUserId()
            it[entryDate] = LocalDate.now()
            it[createdAt] = now
        }.value

        val isTemporary = customer[CustomersTable.isTemporary]
        // PRD rule 10: a temporary walk-in's record goes once its wallet is fully spent.
        val removeNow = isTemporary && status == CustomerStatus.ACTIVE && posting.balanceType == BalanceType.WALLET && !credit &&
            newBalance.signum() == 0
        // A reversal that puts money back on a removed temporary record brings it back, unless the
        // same phone already has a newer active temporary record.
        val restoreNow = isTemporary && status == CustomerStatus.REMOVED && credit && newBalance.signum() > 0 &&
            CustomersTable.selectAll().where {
                (CustomersTable.tenantId eq tenantId) and (CustomersTable.isTemporary eq true) and
                    (CustomersTable.status eq CustomerStatus.ACTIVE.name) and
                    (CustomersTable.phoneE164 eq (customer[CustomersTable.phoneE164] ?: ""))
            }.empty()

        CustomersTable.update({ CustomersTable.id eq posting.customerId }) {
            it[balanceColumn] = newBalance
            it[updatedAt] = now
            if (removeNow) {
                it[CustomersTable.status] = CustomerStatus.REMOVED.name
                it[removedAt] = now
                it[statusReason] = "Temporary customer removed: wallet fully spent"
            }
            if (restoreNow) {
                it[CustomersTable.status] = CustomerStatus.ACTIVE.name
                it[removedAt] = null
                it[statusReason] = null
            }
        }

        val code = customer[CustomersTable.code]
        val verb = if (credit) "credited" else "debited"
        val label = if (posting.balanceType == BalanceType.WALLET) "Wallet" else "Deposit"
        domainEvents.record(
            type = when {
                isReversal -> "customers.wallet.entry-reversed"
                posting.entryType.name.startsWith("ADJUSTMENT") -> "customers.wallet.adjusted"
                else -> "customers.wallet.$verb"
            },
            aggregateType = "customer",
            aggregateId = posting.customerId,
            aggregateRef = code,
            summary = "$label $verb ₦${amount.toPlainString()} — ${posting.reference} ($code)",
            payload = mapOf(
                "entryId" to entryId, "entryType" to posting.entryType.name, "balanceType" to posting.balanceType.name,
                "amount" to amount, "balanceAfter" to newBalance, "reference" to posting.reference,
                "sourceModule" to posting.sourceModule, "sourceType" to posting.sourceType,
            ),
            locationId = posting.locationId,
        )
        if (removeNow) {
            domainEvents.record(
                type = "customers.customer.removed",
                aggregateType = "customer",
                aggregateId = posting.customerId,
                aggregateRef = code,
                summary = "Temporary customer $code removed — wallet fully spent",
            )
        }

        return LedgerEntryRef(entryId, posting.customerId, posting.balanceType, posting.entryType, amount, newBalance, posting.reference)
    }

    /** docs/04 §1.3: deposits are for Special Customers; direct wallet funding is for dealers. */
    private fun checkBalanceType(type: CustomerType, posting: LedgerPosting) {
        if (posting.balanceType == BalanceType.DEPOSIT && type != CustomerType.SPECIAL_CUSTOMER) {
            throw ApiErrors.balanceTypeNotAllowed("Only Special Customers hold a deposit")
        }
        if (posting.balanceType == BalanceType.WALLET && posting.entryType == LedgerEntryType.FUNDING && type != CustomerType.AUTHORISED_DEALER) {
            throw ApiErrors.balanceTypeNotAllowed("Only Authorised Dealers can fund a wallet directly; other customers' wallets hold return credits")
        }
    }

    private fun actorUserId(): UUID? =
        if (SecurityContextHolder.getContext().authentication is JwtAuthenticationToken) currentActorProvider.current().userId else null

}
