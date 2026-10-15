package com.zifiq.customers.spi

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.Table

/*
 * The Customers module's published interface (docs/04 §5) — the only part of `customers` other
 * modules may use (Spring Modulith named interface "spi"). Every method runs inside the caller's
 * transaction and never opens its own (docs/03 §2.4): a module that debits a wallet and records a
 * sale does both in one transaction, so a failure anywhere rolls both back.
 */

enum class CustomerType(val rank: Int, val label: String) {
    NO_CREDIT_WALKIN(0, "No Credit / Walk-in"),
    ORDINARY_CUSTOMER(1, "Ordinary Customer"),
    SPECIAL_CUSTOMER(2, "Special Customer"),
    AUTHORISED_DEALER(3, "Authorised Dealer"),
}

enum class CustomerStatus { ACTIVE, WATCH, SUSPENDED, REMOVED }

enum class BalanceType { WALLET, DEPOSIT }

enum class LedgerDirection { CREDIT, DEBIT }

enum class LedgerEntryType(val direction: LedgerDirection, val displayType: String) {
    FUNDING(LedgerDirection.CREDIT, "Wallet funding"),
    RETURN_CREDIT(LedgerDirection.CREDIT, "Return"),
    COMMISSION_CREDIT(LedgerDirection.CREDIT, "Commission"),
    CREDIT_NOTE(LedgerDirection.CREDIT, "Credit note"),
    PURCHASE_DEBIT(LedgerDirection.DEBIT, "Purchase"),
    WITHDRAWAL_DEBIT(LedgerDirection.DEBIT, "Withdrawal"),
    ADJUSTMENT_CREDIT(LedgerDirection.CREDIT, "Adjustment"),
    ADJUSTMENT_DEBIT(LedgerDirection.DEBIT, "Adjustment"),
    REVERSAL_CREDIT(LedgerDirection.CREDIT, "Reversal"),
    REVERSAL_DEBIT(LedgerDirection.DEBIT, "Reversal"),
}

data class CustomerRef(
    val id: UUID,
    val code: String,
    val type: CustomerType,
    val isTemporary: Boolean,
    val name: String,
    val contactPerson: String?,
    val phone: String?,
    val email: String?,
    val tin: String?,
    val businessDescription: String?,
    val streetAddress: String?,
    val city: String?,
    val stateCode: String?,
    val lgaCode: String?,
    val countryCode: String,
    val locationId: UUID?,
    val correspondentId: UUID?,
    val correspondentAssigned: Boolean,
    val dealerCategoryId: UUID?,
    val dealerCategoryName: String?,
    val dealerDiscountPct: BigDecimal?,
    val creditLimit: BigDecimal,
    val walletBalance: BigDecimal,
    val depositBalance: BigDecimal,
    val status: CustomerStatus,
)

data class CorrespondentRef(
    val id: UUID,
    val code: String,
    val name: String,
    val phone: String,
    val email: String?,
    val region: String?,
    val locationId: UUID?,
    val status: String,
    val userId: UUID?,
)

/** A wallet or deposit movement requested by the module that owns the business document. */
data class LedgerPosting(
    val customerId: UUID,
    val balanceType: BalanceType,
    val entryType: LedgerEntryType,
    val amount: BigDecimal,
    /** The owning document's reference, e.g. `RT-457`, `WW-31`, `TXN-4481`. */
    val reference: String,
    val description: String,
    val paymentMethod: String?,
    val sourceModule: String,
    val sourceType: String,
    val sourceId: UUID?,
    val locationId: UUID?,
    val reason: String? = null,
)

data class LedgerEntryRef(
    val id: UUID,
    val customerId: UUID,
    val balanceType: BalanceType,
    val entryType: LedgerEntryType,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val reference: String,
)

data class LedgerEntryView(
    val id: UUID,
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val balanceType: BalanceType,
    val entryType: LedgerEntryType,
    val direction: LedgerDirection,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val reference: String,
    val description: String,
    val paymentMethod: String?,
    val sourceModule: String,
    val sourceType: String,
    val sourceId: UUID?,
    val locationId: UUID?,
    val performedByUserId: UUID?,
    val entryDate: LocalDate,
    val createdAt: Instant,
)

data class LedgerEntriesFilter(
    val customerIds: Set<UUID>? = null,
    val entryTypes: Set<LedgerEntryType>? = null,
    val sourceModule: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val customerTypes: Set<CustomerType>? = null,
    /** Newest first (entry date, then posting time) instead of posting order. */
    val newestFirst: Boolean = false,
    /** At most this many entries (a caller merging pages from several sources). */
    val limit: Int? = null,
)

data class LedgerTotal(val count: Long, val amount: BigDecimal)

interface CustomerDirectory {
    /** Throws CUSTOMER_NOT_FOUND. */
    fun get(id: UUID): CustomerRef

    /** Same as [get], locking the customer row FOR UPDATE until the caller's transaction ends. */
    fun getForUpdate(id: UUID): CustomerRef

    /** Normalised phone match, excluding REMOVED customers. */
    fun findActiveByPhone(phone: String): CustomerRef?

    /** Picker search (code, name, phone). Not location-restricted: a customer can buy anywhere. */
    fun search(q: String, limit: Int = com.zifiq.common.paging.PagingPolicy.LOOKUP_DEFAULT, types: Set<CustomerType>? = null): List<CustomerRef>
}

interface WalletLedger {
    fun credit(posting: LedgerPosting): LedgerEntryRef

    /** Throws INSUFFICIENT_WALLET_BALANCE / INSUFFICIENT_DEPOSIT_BALANCE; a balance never goes negative. */
    fun debit(posting: LedgerPosting): LedgerEntryRef

    /** Reverses an entry the calling module created (when its source document is voided). */
    fun reverse(entryId: UUID, reason: String, sourceModule: String): LedgerEntryRef
}

interface TemporaryCustomers {
    /**
     * The customer to credit for a walk-in return: an existing registered non-dealer or ACTIVE
     * temporary customer with this phone, or a new temporary NO_CREDIT_WALKIN record.
     */
    fun findOrCreate(name: String, phone: String, locationId: UUID): CustomerRef
}

interface Correspondents {
    fun get(id: UUID): CorrespondentRef

    /** Names of [ids] in one query (a page of rows that each show their rep). */
    fun names(ids: Collection<UUID>): Map<UUID, String>

    /** The customer's correspondent if currently ASSIGNED, else null (only assigned reps earn credit). */
    fun creditableFor(customerId: UUID): CorrespondentRef?

    fun listAssigned(locationId: UUID?): List<CorrespondentRef>
}

interface CustomerActivity {
    /** lifetime_value += amount; last_purchase_at = max(existing, at). */
    fun recordSale(customerId: UUID, amount: BigDecimal, at: Instant)

    /** Called when a sale or invoice is voided. */
    fun reverseSale(customerId: UUID, amount: BigDecimal)
}

interface CustomerQueries {
    fun byIds(ids: Collection<UUID>): List<CustomerRef>
    fun ledgerEntries(filter: LedgerEntriesFilter): List<LedgerEntryView>

    /** How many entries match [filter] and their total amount, in one aggregate query. */
    fun ledgerTotal(filter: LedgerEntriesFilter): LedgerTotal
}

/**
 * Read-only columns of the customers table that other modules may join to in SQL, for lists that
 * filter, search or sort their own rows by customer attributes (the Debtors List) without loading
 * customers into memory. The Customers module owns the table and every write to it; these columns
 * are a published contract, changed only together with their readers.
 */
object CustomerColumns : Table("customers") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val code = varchar("code", 20)
    val type = varchar("type", 30)
    val name = varchar("name", 200)
    val phone = varchar("phone", 40).nullable()
    val phoneE164 = varchar("phone_e164", 20).nullable()
    val locationId = uuid("location_id").nullable()
    val correspondentId = uuid("correspondent_id").nullable()
    val creditLimit = decimal("credit_limit", 14, 2)
    override val primaryKey = PrimaryKey(id)
}
