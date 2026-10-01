package com.zifiq.history.api

import com.zifiq.history.dto.*
import com.zifiq.history.service.HistoryService
import com.zifiq.platform.api.ApiEnvelope
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/history")
class HistoryController(
    private val historyService: HistoryService,
) : HistoryApi {
    @GetMapping("/waybills")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')")
    override fun getWaybillHistory(): ApiEnvelope<List<WaybillHistoryItem>> =
        ApiEnvelope(historyService.getWaybillHistory())

    @GetMapping("/expenses")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun getExpenseHistory(): ApiEnvelope<List<ExpenseHistoryItem>> =
        ApiEnvelope(historyService.getExpenseHistory())

    @GetMapping("/stock-movements")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read')")
    override fun getStockMovementHistory(): ApiEnvelope<List<StockMovementHistoryItem>> =
        ApiEnvelope(historyService.getStockMovementHistory())

    @GetMapping("/violations")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'iam.roles.manage')")
    override fun getFraudViolations(): ApiEnvelope<List<FraudViolationItem>> =
        ApiEnvelope(historyService.getFraudViolations())

    @GetMapping("/withdrawals")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun getWalletWithdrawals(): ApiEnvelope<List<WalletWithdrawalHistoryItem>> =
        ApiEnvelope(historyService.getWalletWithdrawals())
}
