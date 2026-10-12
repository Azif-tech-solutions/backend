package com.zifiq.account.api

import com.zifiq.account.dto.AccountLookupRow
import com.zifiq.account.dto.BankAccountRequest
import com.zifiq.account.dto.BankAccountStatusRequest
import com.zifiq.account.dto.BankAccountUpdateRequest
import com.zifiq.account.dto.BankAccountView
import com.zifiq.account.dto.BankEntryView
import com.zifiq.account.dto.BankStatementRow
import com.zifiq.account.dto.CashAccountRequest
import com.zifiq.account.dto.CashAccountStatusRequest
import com.zifiq.account.dto.CashAccountUpdateRequest
import com.zifiq.account.dto.CashAccountView
import com.zifiq.account.dto.CashCountRequest
import com.zifiq.account.dto.CashCountResult
import com.zifiq.account.dto.CashFlowRow
import com.zifiq.account.dto.ExchangeRateHistoryRow
import com.zifiq.account.dto.ExchangeRateRow
import com.zifiq.account.dto.ExpenseCategoryRequest
import com.zifiq.account.dto.ExpenseCategoryStatusRequest
import com.zifiq.account.dto.ExpenseCategoryView
import com.zifiq.account.dto.ManualBankEntryRequest
import com.zifiq.account.dto.PublishRatesRequest
import com.zifiq.account.dto.RatePublicationView
import com.zifiq.account.service.BankAccountService
import com.zifiq.account.service.CashAccountService
import com.zifiq.account.service.ExchangeRateService
import com.zifiq.account.service.ExpenseCategoryService
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

private const val READ = "@permissionAuthorizer.hasPermission(authentication, 'accounting.read')"
private const val MANAGE_BANK = "@permissionAuthorizer.hasPermission(authentication, 'accounting.bank.manage')"
private const val MANAGE_CASH = "@permissionAuthorizer.hasPermission(authentication, 'accounting.cash-accounts.manage')"
private const val MANAGE_CATEGORIES = "@permissionAuthorizer.hasPermission(authentication, 'accounting.expense-categories.manage')"
private const val MANAGE_FX = "@permissionAuthorizer.hasPermission(authentication, 'accounting.fx.manage')"

// Invoicing and Sales convert foreign amounts with the published rates, so their readers can see them too.
private const val READ_RATES = "@permissionAuthorizer.hasPermission(authentication, 'accounting.read') or " +
    "@permissionAuthorizer.hasPermission(authentication, 'invoicing.invoice.read') or @permissionAuthorizer.hasPermission(authentication, 'cashier.sales.read')"

private fun List<String>?.flattened() = orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

@RestController
@RequestMapping("/api/v1/account/bank-accounts")
class BankAccountsController(private val bankAccounts: BankAccountService) : BankAccountsApi {
    @PostMapping
    @PreAuthorize(MANAGE_BANK)
    override fun register(@Valid @RequestBody request: BankAccountRequest): ApiEnvelope<BankAccountView> = ApiEnvelope(bankAccounts.register(request))

    @GetMapping
    @PreAuthorize(READ)
    override fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) accountType: List<String>?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) currencyCode: String?,
        @RequestParam(required = false) sort: String?,
    ): ApiEnvelope<List<BankAccountView>> = ApiEnvelope(bankAccounts.list(q, accountType.flattened(), status, locationId, currencyCode, sort))

    @GetMapping("/lookup")
    @PreAuthorize(READ)
    override fun lookup(@RequestParam(required = false) q: String?, @RequestParam(required = false) active: Boolean?): ApiEnvelope<List<AccountLookupRow>> =
        ApiEnvelope(bankAccounts.lookup(q, active ?: true))

    @GetMapping("/statement/filters")
    @PreAuthorize(READ)
    override fun statementFilters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(bankAccounts.statementFilterMetadata())

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    override fun get(@PathVariable id: UUID): ApiEnvelope<BankAccountView> = ApiEnvelope(bankAccounts.get(id))

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE_BANK)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: BankAccountUpdateRequest): ApiEnvelope<BankAccountView> =
        ApiEnvelope(bankAccounts.update(id, request))

    @PutMapping("/{id}/status")
    @PreAuthorize(MANAGE_BANK)
    override fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: BankAccountStatusRequest): ApiEnvelope<BankAccountView> =
        ApiEnvelope(bankAccounts.changeStatus(id, request.status!!))

    @PostMapping("/{id}/manual-entries")
    @PreAuthorize(MANAGE_BANK)
    override fun postManualEntry(@PathVariable id: UUID, @Valid @RequestBody request: ManualBankEntryRequest): ApiEnvelope<BankEntryView> =
        ApiEnvelope(bankAccounts.postManualEntry(id, request))

    @GetMapping("/{id}/statement")
    @PreAuthorize(READ)
    override fun statement(@PathVariable id: UUID, @RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<BankStatementRow>> =
        ApiEnvelope(bankAccounts.statement(id, ListSearchRequest.fromQuery(query)))

    @PostMapping("/{id}/statement/search")
    @PreAuthorize(READ)
    override fun searchStatement(@PathVariable id: UUID, @RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<BankStatementRow>> =
        ApiEnvelope(bankAccounts.statement(id, request))
}

@RestController
@RequestMapping("/api/v1/account")
class CashAccountsController(private val cashAccounts: CashAccountService) : CashAccountsApi {
    @PostMapping("/cash-accounts")
    @PreAuthorize(MANAGE_CASH)
    override fun register(@Valid @RequestBody request: CashAccountRequest): ApiEnvelope<CashAccountView> = ApiEnvelope(cashAccounts.register(request))

    @GetMapping("/cash-accounts")
    @PreAuthorize(READ)
    override fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) kind: List<String>?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) belowFloat: Boolean?,
    ): ApiEnvelope<List<CashAccountView>> = ApiEnvelope(cashAccounts.list(q, kind.flattened(), locationId, status, belowFloat))

    @GetMapping("/cash-accounts/lookup")
    @PreAuthorize(READ)
    override fun lookup(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) kind: List<String>?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) active: Boolean?,
    ): ApiEnvelope<List<AccountLookupRow>> = ApiEnvelope(cashAccounts.lookup(q, kind.flattened(), locationId, active ?: true))

    @GetMapping("/cash-accounts/{id}")
    @PreAuthorize(READ)
    override fun get(@PathVariable id: UUID): ApiEnvelope<CashAccountView> = ApiEnvelope(cashAccounts.get(id))

    @PutMapping("/cash-accounts/{id}")
    @PreAuthorize(MANAGE_CASH)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: CashAccountUpdateRequest): ApiEnvelope<CashAccountView> =
        ApiEnvelope(cashAccounts.update(id, request))

    @PutMapping("/cash-accounts/{id}/status")
    @PreAuthorize(MANAGE_CASH)
    override fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: CashAccountStatusRequest): ApiEnvelope<CashAccountView> =
        ApiEnvelope(cashAccounts.changeStatus(id, request.status!!))

    @PostMapping("/cash-accounts/{id}/cash-counts")
    @PreAuthorize(MANAGE_CASH)
    override fun recordCount(@PathVariable id: UUID, @Valid @RequestBody request: CashCountRequest): ApiEnvelope<CashCountResult> =
        ApiEnvelope(cashAccounts.recordCount(id, request))

    @GetMapping("/cash-flow")
    @PreAuthorize(READ)
    override fun cashFlow(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<CashFlowRow>> =
        ApiEnvelope(cashAccounts.cashFlow(ListSearchRequest.fromQuery(query)))

    @PostMapping("/cash-flow/search")
    @PreAuthorize(READ)
    override fun searchCashFlow(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<CashFlowRow>> = ApiEnvelope(cashAccounts.cashFlow(request))

    @GetMapping("/cash-flow/filters")
    @PreAuthorize(READ)
    override fun cashFlowFilters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(cashAccounts.cashFlowFilterMetadata())
}

@RestController
@RequestMapping("/api/v1/account/expense-categories")
class ExpenseCategoriesController(private val categories: ExpenseCategoryService) : ExpenseCategoriesApi {
    @PostMapping
    @PreAuthorize(MANAGE_CATEGORIES)
    override fun create(@Valid @RequestBody request: ExpenseCategoryRequest): ApiEnvelope<ExpenseCategoryView> = ApiEnvelope(categories.create(request))

    @GetMapping
    @PreAuthorize(READ)
    override fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) accountHead: List<String>?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) pettyCashAllowed: Boolean?,
        @RequestParam(required = false) overCap: Boolean?,
        @RequestParam(required = false) sort: String?,
    ): ApiEnvelope<List<ExpenseCategoryView>> = ApiEnvelope(categories.list(q, accountHead.flattened(), status, pettyCashAllowed, overCap, sort))

    @GetMapping("/lookup")
    @PreAuthorize(READ)
    override fun lookup(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) pettyCashAllowed: Boolean?,
        @RequestParam(required = false) active: Boolean?,
    ): ApiEnvelope<List<AccountLookupRow>> = ApiEnvelope(categories.lookup(q, pettyCashAllowed, active ?: true))

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    override fun get(@PathVariable id: UUID): ApiEnvelope<ExpenseCategoryView> = ApiEnvelope(categories.get(id))

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE_CATEGORIES)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: ExpenseCategoryRequest): ApiEnvelope<ExpenseCategoryView> =
        ApiEnvelope(categories.update(id, request))

    @PutMapping("/{id}/status")
    @PreAuthorize(MANAGE_CATEGORIES)
    override fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: ExpenseCategoryStatusRequest): ApiEnvelope<ExpenseCategoryView> =
        ApiEnvelope(categories.changeStatus(id, request.status!!))
}

@RestController
@RequestMapping("/api/v1/account/exchange-rates")
class ExchangeRatesController(private val rates: ExchangeRateService) : ExchangeRatesApi {
    @GetMapping
    @PreAuthorize(READ_RATES)
    override fun current(): ApiEnvelope<List<ExchangeRateRow>> = ApiEnvelope(rates.current())

    @PostMapping("/publish")
    @PreAuthorize(MANAGE_FX)
    override fun publish(@Valid @RequestBody request: PublishRatesRequest): ApiEnvelope<RatePublicationView> = ApiEnvelope(rates.publish(request))

    @GetMapping("/history")
    @PreAuthorize(READ_RATES)
    override fun history(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ExchangeRateHistoryRow>> =
        ApiEnvelope(rates.history(ListSearchRequest.fromQuery(query)))

    @PostMapping("/history/search")
    @PreAuthorize(READ_RATES)
    override fun searchHistory(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<ExchangeRateHistoryRow>> = ApiEnvelope(rates.history(request))

    @GetMapping("/history/filters")
    @PreAuthorize(READ_RATES)
    override fun historyFilters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(rates.historyFilterMetadata())
}
