package com.zifiq.showroom.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val PHONE_PATTERN = "^[+0-9 ()-]{7,20}$"
private const val PHONE_MESSAGE = "must be 7-20 characters of digits, spaces, +, (, ) or -"

// ---- Showroom register (docs/05 §4.1) ----

data class ShowroomRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:NotBlank @field:Size(max = 5) val stateCode: String,
    @field:NotBlank @field:Size(max = 300) val streetAddress: String,
    @field:NotBlank @field:Size(max = 120) val city: String,
    @field:NotBlank @field:Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE) val phone: String,
    @field:Email @field:Size(max = 320) val email: String? = null,
    @field:Size(max = 160) val managerName: String? = null,
    val managerUserId: UUID? = null,
    val dateOpened: LocalDate? = null,
    @field:Size(max = 80) val storageCapacity: String? = null,
    @field:Size(max = 120) val operatingHours: String? = null,
    /** Create only: false = "pending opening" (status PENDING). */
    val active: Boolean = true,
    /** Create only. */
    val createDefaultAreas: Boolean = true,
)

data class ShowroomStatusRequest(
    @field:NotNull @field:Pattern(regexp = "ACTIVE|PENDING|SUSPENDED") val status: String?,
    @field:Size(max = 300) val reason: String? = null,
)

data class AreaRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:NotNull @field:Pattern(regexp = "SHOP_FLOOR|WAREHOUSE|RECEIVING|QUARANTINE|OTHER") val kind: String?,
)

data class AreaStatusRequest(@field:NotNull @field:Pattern(regexp = "ACTIVE|SUSPENDED") val status: String?)

data class ShowroomDefaultsRequest(val defaultReceivingLocationId: UUID?, val defaultSellingLocationId: UUID?)

data class AreaView(val id: UUID, val code: String, val name: String, val kind: String, val status: String, val unitsOnHand: BigDecimal)

data class ShowroomDetail(
    val id: UUID,
    val code: String,
    val name: String,
    val stateCode: String?,
    val stateName: String?,
    val streetAddress: String?,
    val city: String?,
    val phone: String?,
    val email: String?,
    val managerName: String?,
    val managerUserId: UUID?,
    val dateOpened: LocalDate?,
    val storageCapacity: String?,
    val operatingHours: String?,
    val status: String,
    val vatRate: BigDecimal,
    val vatEnabled: Boolean,
    val vatRegistrationNumber: String?,
    val areas: List<AreaView>,
    val defaultReceivingLocationId: UUID?,
    val defaultSellingLocationId: UUID?,
    val createdAt: Instant,
    /** Status changes: e.g. "Showroom holds 1,240 units" when suspending a stocked showroom. */
    val warnings: List<String> = emptyList(),
)

data class ShowroomRow(
    val id: UUID,
    val code: String,
    val name: String,
    val streetAddress: String?,
    val city: String?,
    val stateCode: String?,
    val stateName: String?,
    val managerName: String?,
    val phone: String?,
    val dateOpened: LocalDate?,
    val vatRate: BigDecimal,
    val vatEnabled: Boolean,
    val status: String,
    val areaCount: Int,
)

data class ShowroomList(val items: List<ShowroomRow>, val stats: Map<String, Long>)

data class ShowroomLookupRow(val id: UUID, val code: String, val label: String, val status: String, val areas: List<AreaView>)

// ---- Direct entry and outsource receipts (docs/05 §4.4–§4.5) ----

data class DirectEntryLineInput(
    @field:NotBlank @field:Size(max = 200) val description: String,
    @field:NotNull @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal?,
    @field:NotNull val unitOfMeasureId: UUID?,
    @field:NotNull @field:DecimalMin("0") val unitCost: BigDecimal?,
    @field:DecimalMin("0") @field:DecimalMax("100") val inputVatRate: BigDecimal? = null,
    @field:NotNull @field:DecimalMin("0") val sellingPrice: BigDecimal?,
    @field:DecimalMin("0") val dealerPrice: BigDecimal? = null,
    @field:Size(max = 40) val batchNo: String? = null,
    val expiryDate: LocalDate? = null,
)

data class DirectEntryRequest(
    @field:NotNull val locationId: UUID?,
    val intoLocationId: UUID? = null,
    val supplierId: UUID? = null,
    @field:Size(max = 160) val supplierName: String? = null,
    @field:Size(max = 60) val supplierInvoiceNo: String? = null,
    @field:Size(max = 120) val paymentTerms: String? = null,
    @field:NotNull val receivedDate: LocalDate?,
    val vatCategoryId: UUID? = null,
    @field:Size(min = 1, max = 100) val lines: List<@Valid DirectEntryLineInput>,
)

data class OutsourceLineInput(
    @field:NotNull val stockItemId: UUID?,
    @field:NotNull @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal?,
    @field:DecimalMin("0") @field:DecimalMax("100") val inputVatRate: BigDecimal? = null,
    @field:Size(max = 40) val batchNo: String? = null,
    val expiryDate: LocalDate? = null,
)

data class OutsourceReceiptRequest(
    @field:NotNull val locationId: UUID?,
    val intoLocationId: UUID? = null,
    @field:NotNull val manufacturerId: UUID?,
    @field:Size(max = 60) val partnerReference: String? = null,
    @field:Size(max = 120) val checkedBy: String? = null,
    @field:NotNull val receivedDate: LocalDate?,
    @field:Size(min = 1, max = 100) val lines: List<@Valid OutsourceLineInput>,
)

data class StockEntryLineView(
    val lineNo: Int,
    val stockItemId: UUID,
    val code: String,
    val description: String,
    val quantity: BigDecimal,
    val unit: String?,
    /** [cost] 0 when the caller can't see cost. */
    val unitCost: BigDecimal,
    val lineCost: BigDecimal,
    val inputVatRate: BigDecimal,
    val inputVatAmount: BigDecimal,
    val sellingPrice: BigDecimal?,
    val batchNo: String?,
    val expiryDate: LocalDate?,
)

data class StockEntrySummary(
    val id: UUID,
    val ref: String,
    val kind: String,
    val locationId: UUID,
    val locationName: String,
    val intoLocationId: UUID,
    val intoLocationName: String,
    val supplierId: UUID?,
    val supplierName: String?,
    val manufacturerId: UUID?,
    val partnerName: String?,
    val partnerReference: String?,
    val paymentTerms: String?,
    val checkedBy: String?,
    val receivedDate: LocalDate,
    val lines: List<StockEntryLineView>,
    val totalCost: BigDecimal,
    val totalInputVat: BigDecimal,
    val enteredByUserId: UUID,
    val enteredByName: String?,
    val createdAt: Instant,
    val costVisible: Boolean,
    /** E.g. a price not changed because the item already has a price published for that date. */
    val warnings: List<String> = emptyList(),
)

/** One row per entry line, as the intake reports show. */
data class StockEntryLineRow(
    val entryId: UUID,
    val lineId: UUID,
    val date: LocalDate,
    val ref: String,
    val kind: String,
    val locationId: UUID,
    val locationName: String,
    val stockItemId: UUID,
    val productCode: String,
    val product: String,
    val party: String?,
    val partnerReference: String?,
    val batchNo: String?,
    val expiryDate: LocalDate?,
    val quantity: BigDecimal,
    val unit: String?,
    val unitCost: BigDecimal,
    val lineCost: BigDecimal,
    val inputVatAmount: BigDecimal,
    val enteredByName: String?,
)

// ---- Storage-area moves (docs/05 §4.7) ----

data class AreaMoveLineInput(
    @field:NotNull val stockItemId: UUID?,
    @field:NotNull @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal?,
)

data class AreaMoveRequest(
    @field:NotNull val fromLocationId: UUID?,
    @field:NotNull val toLocationId: UUID?,
    val movedDate: LocalDate? = null,
    @field:Size(max = 300) val note: String? = null,
    @field:Size(min = 1, max = 100) val lines: List<@Valid AreaMoveLineInput>,
)

data class AreaMoveLineView(val lineNo: Int, val stockItemId: UUID, val code: String, val name: String, val quantity: BigDecimal, val unit: String?)

data class AreaMoveSummary(
    val id: UUID,
    val ref: String,
    val rootLocationId: UUID,
    val rootLocationName: String,
    val fromLocationId: UUID,
    val fromLocationName: String,
    val toLocationId: UUID,
    val toLocationName: String,
    val movedDate: LocalDate,
    val note: String?,
    val lines: List<AreaMoveLineView>,
    val movedByUserId: UUID,
    val createdAt: Instant,
)

// ---- Customer returns (docs/05 §4.8–§4.9) ----

data class WalkInCustomer(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank @field:Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE) val phone: String,
)

data class ReturnLineInput(
    @field:NotNull val stockItemId: UUID?,
    @field:NotNull @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal?,
    @field:NotNull @field:Pattern(regexp = "DAMAGED_IN_TRANSIT|EXPIRED_OR_NEAR_EXPIRY|WRONG_ITEM_SUPPLIED|PRODUCT_DEFECT|UNSOLD_AGREED_RETURN|CUSTOMER_DISSATISFACTION")
    val reason: String?,
    @field:NotNull @field:Pattern(regexp = "RESALEABLE|DAMAGED_WRITE_OFF|QUARANTINE") val condition: String?,
    /** RESALEABLE: the showroom or one of its areas (default its receiving area). Ignored otherwise. */
    val intoLocationId: UUID? = null,
    @field:Size(max = 300) val note: String? = null,
)

data class ReturnRequest(
    @field:NotNull @field:Pattern(regexp = "DEALER|NON_DEALER") val returnType: String?,
    @field:NotNull val locationId: UUID?,
    /** DEALER: required (an Authorised Dealer). NON_DEALER: a registered non-dealer, or leave empty and give walkIn. */
    val customerId: UUID? = null,
    @field:Valid val walkIn: WalkInCustomer? = null,
    @field:Size(max = 40) val originalDocumentRef: String? = null,
    val returnDate: LocalDate? = null,
    @field:Size(max = 300) val note: String? = null,
    @field:Size(min = 1, max = 100) val lines: List<@Valid ReturnLineInput>,
)

data class ReversalRequest(@field:NotBlank @field:Size(max = 300) val reason: String)

data class ReturnCustomerView(val id: UUID, val code: String, val name: String, val type: String, val temporary: Boolean)

data class ReturnLineView(
    val lineNo: Int,
    val stockItemId: UUID,
    val code: String,
    val name: String,
    val quantity: BigDecimal,
    val unit: String?,
    val unitPrice: BigDecimal,
    val lineValue: BigDecimal,
    val reason: String,
    val condition: String,
    val intoLocationId: UUID?,
    val intoLocationName: String?,
    val note: String?,
)

data class ReturnSummary(
    val id: UUID,
    val ref: String,
    val returnType: String,
    val locationId: UUID,
    val locationName: String,
    val customer: ReturnCustomerView,
    val originalDocumentRef: String?,
    val returnDate: LocalDate,
    val note: String?,
    val lines: List<ReturnLineView>,
    val totalValue: BigDecimal,
    val writeOffValue: BigDecimal,
    val walletBalanceBefore: BigDecimal,
    val walletCredit: BigDecimal,
    val walletBalanceAfter: BigDecimal,
    val status: String,
    val reversedReason: String?,
    val reversedAt: Instant?,
    val processedByUserId: UUID,
    val processedByName: String?,
    val createdAt: Instant,
)

/** Return Reports: one row per line, as the screen shows. */
data class ReturnLineRow(
    val returnId: UUID,
    val lineId: UUID,
    val date: LocalDate,
    val ref: String,
    val returnType: String,
    val originalDocumentRef: String?,
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val customerType: String,
    val productCode: String,
    val productName: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineValue: BigDecimal,
    val reason: String,
    val condition: String,
    val locationId: UUID,
    val locationName: String,
    /** 'Dealer wallet' | 'Temporary wallet' | 'Customer wallet'. */
    val creditedTo: String,
    val status: String,
)

data class ReasonTotal(val reason: String, val lines: Long, val units: BigDecimal, val value: BigDecimal)

// ---- Reports (docs/05 §4.11, §4.14) ----

data class ComprehensiveStockRow(
    val ln: Int,
    val stockItemId: UUID,
    val code: String,
    val detail: String,
    val group: String,
    /** 'Finished Product' | 'Outsource' | 'Direct entry' (or the item type for others). */
    val source: String,
    val locationId: UUID,
    /** Area name, or "Unallocated" for stock on the showroom itself. */
    val area: String,
    val qty: BigDecimal,
    val unit: String?,
    /** [cost] 0 when the caller can't see cost. */
    val unitCost: BigDecimal,
    val totalCost: BigDecimal,
    val sellingPrice: BigDecimal?,
    val retailValue: BigDecimal,
)

data class TransferReportRow(
    val transferId: UUID,
    val lineId: UUID,
    val date: LocalDate,
    val waybillRef: String,
    val source: String,
    val sourceType: String,
    val destination: String,
    val destinationType: String,
    val route: String,
    val driver: String?,
    val vehicle: String?,
    val productCode: String,
    val product: String,
    val qtySent: BigDecimal,
    val qtyReceived: BigDecimal?,
    val qtyRejected: BigDecimal?,
    val unit: String?,
    val receivedDate: LocalDate?,
    /** 'In transit' | 'Partially received' | 'Received'. */
    val status: String,
)
