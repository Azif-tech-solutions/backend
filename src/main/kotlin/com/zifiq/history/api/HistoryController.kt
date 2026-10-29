package com.zifiq.history.api

import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.history.service.ExpensesHistoryService
import com.zifiq.history.service.StockMovementHistoryService
import com.zifiq.history.service.WalletWithdrawalHistoryService
import com.zifiq.platform.api.ApiEnvelope
import java.time.LocalDate
import java.util.UUID
import org.springdoc.core.annotations.ParameterObject
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/history")
class HistoryController(
    private val stockMovements: StockMovementHistoryService,
    private val walletWithdrawals: WalletWithdrawalHistoryService,
    private val expenses: ExpensesHistoryService,
) : HistoryApi {
    @GetMapping("/stock-movements")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'history.stock-movements.read')")
    override fun stockMovements(@ParameterObject @RequestParam query: MultiValueMap<String, String>) =
        ApiEnvelope(stockMovements.list(ListSearchRequest.fromQuery(query)))

    @GetMapping("/stock-movements/filters")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'history.stock-movements.read')")
    override fun stockMovementFilters() = ApiEnvelope(stockMovements.filterMetadata())

    @GetMapping("/wallet-withdrawals")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'history.wallet-withdrawals.read')")
    override fun walletWithdrawals(@ParameterObject @RequestParam query: MultiValueMap<String, String>) =
        ApiEnvelope(walletWithdrawals.list(ListSearchRequest.fromQuery(query)))

    @GetMapping("/wallet-withdrawals/filters")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'history.wallet-withdrawals.read')")
    override fun walletWithdrawalFilters() = ApiEnvelope(walletWithdrawals.filterMetadata())

    @GetMapping("/expenses")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'history.expenses.read')")
    override fun expenses(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) method: String?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ) = ApiEnvelope(expenses.list(from, to, categoryId, method, locationId, status, q, page, size))
}
