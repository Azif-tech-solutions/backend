package com.zifiq.history.api

import com.zifiq.history.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Audit & History Reports", description = "Waybills, Expenses, Stock Movements, Violations & Wallet Withdrawals")
@SecurityRequirement(name = "bearerAuth")
interface HistoryApi {
    @Operation(summary = "Searchable waybill history report")
    fun getWaybillHistory(): ApiEnvelope<List<WaybillHistoryItem>>

    @Operation(summary = "Historical expense and payment records")
    fun getExpenseHistory(): ApiEnvelope<List<ExpenseHistoryItem>>

    @Operation(summary = "Long-range stock movement audit trail")
    fun getStockMovementHistory(): ApiEnvelope<List<StockMovementHistoryItem>>

    @Operation(summary = "Flagged violations and fraud audit history")
    fun getFraudViolations(): ApiEnvelope<List<FraudViolationItem>>

    @Operation(summary = "Dealer and customer wallet withdrawal history")
    fun getWalletWithdrawals(): ApiEnvelope<List<WalletWithdrawalHistoryItem>>
}
