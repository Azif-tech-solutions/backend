package com.zifiq.inventory.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---- Drivers (docs/05 §4.13) ----

data class DriverRequest(
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:Pattern(regexp = "^[+0-9 ()-]{7,20}$", message = "must be 7-20 characters of digits, spaces, +, (, ) or -") val phone: String? = null,
    @field:Size(max = 30) val vehicleNumber: String? = null,
    @field:Size(max = 40) val licenceNumber: String? = null,
)

data class DriverStatusRequest(@field:NotNull @field:Pattern(regexp = "ACTIVE|INACTIVE") val status: String?)

data class DriverSummary(
    val id: UUID,
    val name: String,
    val phone: String?,
    val vehicleNumber: String?,
    val licenceNumber: String?,
    val status: String,
    val createdAt: Instant,
)

// ---- Price list (docs/05 §4.12) ----

data class PriceEntryInput(
    @field:NotNull val stockItemId: UUID?,
    @field:NotNull @field:DecimalMin("0") @field:DecimalMax("999999999999.99") val retailPrice: BigDecimal?,
    @field:NotNull @field:DecimalMin("0") @field:DecimalMax("999999999999.99") val dealerPrice: BigDecimal?,
)

data class PricePublishRequest(
    @field:NotNull val effectiveFrom: LocalDate?,
    @field:Size(max = 300) val note: String? = null,
    @field:Size(min = 1, max = 1000) val entries: List<@Valid PriceEntryInput>,
)

data class PricePublishResult(
    val batchRef: String,
    val effectiveFrom: LocalDate,
    val count: Int,
    /** [cost] Items priced below their landing cost; 0 when the caller can't see cost. */
    val belowCostCount: Int,
    val costVisible: Boolean,
)

data class PriceEntryUpdateRequest(
    @field:NotNull @field:DecimalMin("0") val retailPrice: BigDecimal?,
    @field:NotNull @field:DecimalMin("0") val dealerPrice: BigDecimal?,
    @field:Size(max = 300) val note: String? = null,
)

data class PriceChange(val priceId: UUID, val effectiveFrom: LocalDate, val retailPrice: BigDecimal, val dealerPrice: BigDecimal)

data class PriceListRow(
    val stockItemId: UUID,
    val code: String,
    val product: String,
    val itemType: String,
    val categoryId: UUID?,
    val group: String,
    val unit: String?,
    val status: String,
    val priceId: UUID?,
    /** Null when the item has no price on the date. */
    val retailPrice: BigDecimal?,
    val dealerPrice: BigDecimal?,
    val effectiveFrom: LocalDate?,
    val batchRef: String?,
    val nextChange: PriceChange?,
    /** [cost] fields: 0 when the caller can't see cost. */
    val landingCost: BigDecimal,
    val margin: BigDecimal,
    val marginPct: BigDecimal,
    val dealerMargin: BigDecimal,
    val belowCost: Boolean,
)

data class PriceHistoryRow(
    val priceId: UUID,
    val retailPrice: BigDecimal,
    val dealerPrice: BigDecimal,
    val effectiveFrom: LocalDate,
    val batchRef: String,
    val note: String?,
    val setByUserId: UUID,
    val createdAt: Instant,
    /** CURRENT, SUPERSEDED or SCHEDULED, relative to today. */
    val state: String,
)

// ---- Group stock/bin cards (docs/05 §4.10) ----

data class GroupCardMember(val stockItemId: UUID, val code: String, val name: String, val unit: String?)

data class GroupCardHeader(
    val groupType: String,
    val groupId: UUID,
    val groupName: String,
    /** The first members by name ([memberCount] in all). */
    val members: List<GroupCardMember>,
    val memberCount: Long,
    val openingBalance: BigDecimal,
    val totalReceived: BigDecimal,
    val totalIssued: BigDecimal,
    val closingBalance: BigDecimal,
)

data class GroupStockCardRow(
    val sn: Int,
    val date: LocalDate,
    val itemCode: String,
    val itemName: String,
    val locationName: String,
    val remark: String,
    val qtyIn: BigDecimal,
    val qtyOut: BigDecimal,
    val qtyRemain: BigDecimal,
)

data class GroupStockCard(val header: GroupCardHeader, val rows: com.zifiq.common.paging.PageResult<GroupStockCardRow>)

data class GroupBinCard(val header: GroupCardHeader, val rows: com.zifiq.common.paging.PageResult<BinCardRow>)
