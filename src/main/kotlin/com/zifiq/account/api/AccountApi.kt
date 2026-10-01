package com.zifiq.account.api

import com.zifiq.account.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Account Management", description = "Cashflow, Bank Accounts, Petty Cash & Operating Expenses")
@SecurityRequirement(name = "bearerAuth")
interface AccountApi {
    @Operation(summary = "Get company-wide cash inflow and outflow statement")
    fun getCashFlow(): ApiEnvelope<List<CashFlowEntry>>

    @Operation(summary = "Get statement for registered bank account")
    fun getBankStatement(accountNumber: String): ApiEnvelope<BankAccountStatement>

    @Operation(summary = "Get running petty cash balance and statement")
    fun getPettyCashStatement(): ApiEnvelope<PettyCashStatement>

    @Operation(summary = "Record and review operating cost payment")
    fun recordOperatingCost(request: OperatingCostPaymentRequest): ApiEnvelope<PaymentSummary>

    @Operation(summary = "Register a new company bank account")
    fun registerBankAccount(request: BankAccountRegistrationRequest): ApiEnvelope<BankAccountSummary>

    @Operation(summary = "Top up petty cash float")
    fun topUpPettyCash(request: PettyCashTopUpRequest): ApiEnvelope<PaymentSummary>
}
