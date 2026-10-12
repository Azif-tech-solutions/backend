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
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.util.MultiValueMap

@Tag(name = "Account — bank accounts", description = "Bank Register, manual entries and Bank Statement (docs/07 §4.1–4.2)")
@SecurityRequirement(name = "bearerAuth")
interface BankAccountsApi {
    @Operation(summary = "Register a bank account; a non-zero opening balance becomes its first ledger entry")
    fun register(request: BankAccountRequest): ApiEnvelope<BankAccountView>

    @Operation(summary = "Bank accounts (unpaged); account numbers are masked unless you hold accounting.bank.manage")
    fun list(q: String?, accountType: List<String>?, status: String?, locationId: UUID?, currencyCode: String?, sort: String?): ApiEnvelope<List<BankAccountView>>

    @Operation(summary = "Bank account picker")
    fun lookup(q: String?, active: Boolean?): ApiEnvelope<List<AccountLookupRow>>

    @Operation(summary = "One bank account")
    fun get(id: UUID): ApiEnvelope<BankAccountView>

    @Operation(summary = "Edit a bank account (type, currency and opening balance are fixed)")
    fun update(id: UUID, request: BankAccountUpdateRequest): ApiEnvelope<BankAccountView>

    @Operation(summary = "ACTIVE or DORMANT (dormant accounts stay in history but take no new entries)")
    fun changeStatus(id: UUID, request: BankAccountStatusRequest): ApiEnvelope<BankAccountView>

    @Operation(summary = "Post a bank charge, interest or reconciling item (a debit needs an expense category)")
    fun postManualEntry(id: UUID, request: ManualBankEntryRequest): ApiEnvelope<BankEntryView>

    @Operation(summary = "Bank Statement: entries in date order with a running balance; summary has opening/closing balances and totals")
    fun statement(id: UUID, query: MultiValueMap<String, String>): ApiEnvelope<PageResult<BankStatementRow>>

    @Operation(summary = "Bank Statement with an advanced filter body")
    fun searchStatement(id: UUID, request: ListSearchRequest): ApiEnvelope<PageResult<BankStatementRow>>

    @Operation(summary = "Filters, operators and sorts the Bank Statement accepts")
    fun statementFilters(): ApiEnvelope<FilterMetadata>
}

@Tag(name = "Account — cash", description = "Tills, petty-cash boxes and vaults, cash counts, and the Cash Inflow/Outflow Statement (docs/07 §4.3–4.4)")
@SecurityRequirement(name = "bearerAuth")
interface CashAccountsApi {
    @Operation(summary = "Register a till, petty-cash box or vault at a showroom/factory (or head office)")
    fun register(request: CashAccountRequest): ApiEnvelope<CashAccountView>

    @Operation(summary = "Cash accounts (unpaged)")
    fun list(q: String?, kind: List<String>?, locationId: UUID?, status: String?, belowFloat: Boolean?): ApiEnvelope<List<CashAccountView>>

    @Operation(summary = "Cash account picker")
    fun lookup(q: String?, kind: List<String>?, locationId: UUID?, active: Boolean?): ApiEnvelope<List<AccountLookupRow>>

    @Operation(summary = "One cash account")
    fun get(id: UUID): ApiEnvelope<CashAccountView>

    @Operation(summary = "Edit name, float limit, terminal and settlement account (code, kind and location are fixed)")
    fun update(id: UUID, request: CashAccountUpdateRequest): ApiEnvelope<CashAccountView>

    @Operation(summary = "ACTIVE or INACTIVE (only an empty account can be deactivated)")
    fun changeStatus(id: UUID, request: CashAccountStatusRequest): ApiEnvelope<CashAccountView>

    @Operation(summary = "Record a physical count; any difference is posted as an adjustment and alerts Management")
    fun recordCount(id: UUID, request: CashCountRequest): ApiEnvelope<CashCountResult>

    @Operation(summary = "Cash Inflow/Outflow Statement for a location (or company-wide), in date order with a running balance")
    fun cashFlow(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<CashFlowRow>>

    @Operation(summary = "Cash Inflow/Outflow Statement with an advanced filter body")
    fun searchCashFlow(request: ListSearchRequest): ApiEnvelope<PageResult<CashFlowRow>>

    @Operation(summary = "Filters, operators and sorts the Cash Inflow/Outflow Statement accepts")
    fun cashFlowFilters(): ApiEnvelope<FilterMetadata>
}

@Tag(name = "Account — expense categories", description = "Expenses / Payment List Guide (docs/07 §4.5)")
@SecurityRequirement(name = "bearerAuth")
interface ExpenseCategoriesApi {
    @Operation(summary = "Add an expense category (code EX-n unless given)")
    fun create(request: ExpenseCategoryRequest): ApiEnvelope<ExpenseCategoryView>

    @Operation(summary = "Expense categories with this month's spend (unpaged)")
    fun list(q: String?, accountHead: List<String>?, status: String?, pettyCashAllowed: Boolean?, overCap: Boolean?, sort: String?): ApiEnvelope<List<ExpenseCategoryView>>

    @Operation(summary = "Expense category picker (sublabel: spend this month against the cap)")
    fun lookup(q: String?, pettyCashAllowed: Boolean?, active: Boolean?): ApiEnvelope<List<AccountLookupRow>>

    @Operation(summary = "One expense category")
    fun get(id: UUID): ApiEnvelope<ExpenseCategoryView>

    @Operation(summary = "Edit an expense category (the code is fixed)")
    fun update(id: UUID, request: ExpenseCategoryRequest): ApiEnvelope<ExpenseCategoryView>

    @Operation(summary = "ACTIVE or INACTIVE (inactive categories stay in reports but can't be used on new entries)")
    fun changeStatus(id: UUID, request: ExpenseCategoryStatusRequest): ApiEnvelope<ExpenseCategoryView>
}

@Tag(name = "Account — exchange rates", description = "Exchange Rate screen (docs/07 §4.16)")
@SecurityRequirement(name = "bearerAuth")
interface ExchangeRatesApi {
    @Operation(summary = "Latest buy/sell rate per tracked currency, with the move since the previous rate")
    fun current(): ApiEnvelope<List<ExchangeRateRow>>

    @Operation(summary = "Publish rates for some or all tracked currencies (one FX-n publication)")
    fun publish(request: PublishRatesRequest): ApiEnvelope<RatePublicationView>

    @Operation(summary = "Rate history")
    fun history(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ExchangeRateHistoryRow>>

    @Operation(summary = "Rate history with an advanced filter body")
    fun searchHistory(request: ListSearchRequest): ApiEnvelope<PageResult<ExchangeRateHistoryRow>>

    @Operation(summary = "Filters, operators and sorts the rate history accepts")
    fun historyFilters(): ApiEnvelope<FilterMetadata>
}
