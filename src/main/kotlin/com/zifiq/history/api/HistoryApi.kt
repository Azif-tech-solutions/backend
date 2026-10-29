package com.zifiq.history.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.StockMovementHistoryRow
import com.zifiq.history.dto.WalletWithdrawalHistoryRow
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.util.MultiValueMap

/**
 * Audit & Compliance reports (docs/10). Waybill History, Expenses and Wallet Withdrawal History
 * (read through the owning modules' SPI) are added as their SPI lands; Stock Movement History reads
 * the single stock ledger directly.
 */
@Tag(name = "Audit & Compliance — reports", description = "Stock movements, waybills, expenses and wallet withdrawals (docs/10 §4)")
@SecurityRequirement(name = "bearerAuth")
interface HistoryApi {
    @Operation(summary = "Stock Movement History: the long-range, all-locations stock ledger, filtered and paged")
    fun stockMovements(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<StockMovementHistoryRow>>

    @Operation(summary = "Filters the stock-movement history accepts")
    fun stockMovementFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Wallet Withdrawal History: withdrawals with the audit-review badge and breach flags")
    fun walletWithdrawals(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<WalletWithdrawalHistoryRow>>

    @Operation(summary = "Filters the wallet-withdrawal history accepts")
    fun walletWithdrawalFilters(): ApiEnvelope<FilterMetadata>
}
