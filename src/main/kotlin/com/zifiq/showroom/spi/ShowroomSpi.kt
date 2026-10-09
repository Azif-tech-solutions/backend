package com.zifiq.showroom.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/*
 * The Showroom module's published, read-only interface (docs/05 §5): what Audit & Compliance and
 * Reports need. Everything stock-related (balances, ledger, prices, drivers, transfers) other
 * modules read directly from the OPEN inventory module. Runs in the caller's transaction.
 */

/** Name, address and VAT details for documents (invoices, receipts). */
data class ShowroomRef(
    val id: UUID,
    val code: String,
    val name: String,
    val streetAddress: String?,
    val city: String?,
    val stateCode: String?,
    val phone: String?,
    val email: String?,
    val vatRate: BigDecimal,
    val vatEnabled: Boolean,
    val vatRegistrationNumber: String?,
    val status: String,
)

data class ReturnFilter(
    val locationIds: Set<UUID>? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** Null = posted and reversed. */
    val status: String? = "POSTED",
)

data class ReturnLineRef(
    val returnId: UUID,
    val ref: String,
    val returnType: String,
    val locationId: UUID,
    val customerId: UUID,
    val customerType: String,
    val returnDate: LocalDate,
    val stockItemId: UUID,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineValue: BigDecimal,
    val reason: String,
    val condition: String,
    val status: String,
)

data class StockEntryFilter(
    /** DIRECT_ENTRY, OUTSOURCE_RECEIPT, or null for both. */
    val kind: String? = null,
    val locationIds: Set<UUID>? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

data class StockEntryLineRef(
    val entryId: UUID,
    val ref: String,
    val kind: String,
    val locationId: UUID,
    val receivedDate: LocalDate,
    val stockItemId: UUID,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val lineCost: BigDecimal,
    val inputVatRate: BigDecimal,
    val inputVatAmount: BigDecimal,
    val supplierId: UUID?,
    val supplierName: String?,
    val manufacturerId: UUID?,
)

interface ShowroomQueries {
    /** Throws SHOWROOM_NOT_FOUND. */
    fun showroom(id: UUID): ShowroomRef
    fun returns(filter: ReturnFilter): List<ReturnLineRef>
    /** Unmasked costs: callers are report modules that apply their own cost visibility. */
    fun stockEntries(filter: StockEntryFilter): List<StockEntryLineRef>
}
