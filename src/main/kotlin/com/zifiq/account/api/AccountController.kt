package com.zifiq.account.api

import com.zifiq.account.dto.*
import com.zifiq.account.service.AccountService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/account")
class AccountController(
    private val accountService: AccountService,
) : AccountApi {
    @GetMapping("/cashflow")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun getCashFlow(): ApiEnvelope<List<CashFlowEntry>> =
        ApiEnvelope(accountService.getCashFlow())

    @GetMapping("/bank-statement/{accountNumber}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun getBankStatement(@PathVariable accountNumber: String): ApiEnvelope<BankAccountStatement> =
        ApiEnvelope(accountService.getBankStatement(accountNumber))

    @GetMapping("/petty-cash/statement")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.read')")
    override fun getPettyCashStatement(): ApiEnvelope<PettyCashStatement> =
        ApiEnvelope(accountService.getPettyCashStatement())

    @PostMapping("/operating-costs")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.manage')")
    override fun recordOperatingCost(
        @Valid @RequestBody request: OperatingCostPaymentRequest,
    ): ApiEnvelope<PaymentSummary> =
        ApiEnvelope(accountService.recordOperatingCost(request))

    @PostMapping("/bank-accounts")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.manage')")
    override fun registerBankAccount(
        @Valid @RequestBody request: BankAccountRegistrationRequest,
    ): ApiEnvelope<BankAccountSummary> =
        ApiEnvelope(accountService.registerBankAccount(request))

    @PostMapping("/petty-cash/top-up")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'accounting.manage')")
    override fun topUpPettyCash(
        @Valid @RequestBody request: PettyCashTopUpRequest,
    ): ApiEnvelope<PaymentSummary> =
        ApiEnvelope(accountService.topUpPettyCash(request))
}
