package com.zifiq.account.api

import org.springdoc.core.annotations.ParameterObject
import com.zifiq.common.paging.PageParams
import com.zifiq.account.dto.AllocationRejectRequest
import com.zifiq.account.dto.CashLodgementRequest
import com.zifiq.account.dto.CashLodgementRow
import com.zifiq.account.dto.FundConfirmRequest
import com.zifiq.account.dto.FundDisbursementRequest
import com.zifiq.account.dto.FundDisbursementRow
import com.zifiq.account.dto.PettyCashAllocationRequest
import com.zifiq.account.dto.PettyCashAllocationRow
import com.zifiq.account.dto.PettyCashFundingRequest
import com.zifiq.account.dto.PettyCashFundingRow
import com.zifiq.account.dto.PettyCashHistoryRow
import com.zifiq.account.dto.PettyCashPaymentRequest
import com.zifiq.account.dto.PettyCashPaymentView
import com.zifiq.account.dto.PettyCashStatementView
import com.zifiq.account.dto.ReasonRequest
import com.zifiq.account.dto.RunningCostRejectRequest
import com.zifiq.account.dto.RunningCostRequest
import com.zifiq.account.dto.RunningCostView
import com.zifiq.account.service.CashLodgementService
import com.zifiq.account.service.FundDisbursementService
import com.zifiq.account.service.PettyCashService
import com.zifiq.account.service.RunningCostService
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

private const val READ_ACCOUNTS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.read')"
private const val FUND = "@permissionAuthorizer.hasPermission(authentication, 'accounting.petty-cash.fund')"
private const val PAY = "@permissionAuthorizer.hasPermission(authentication, 'accounting.petty-cash.pay')"
private const val RESPOND = "@permissionAuthorizer.hasPermission(authentication, 'accounting.petty-cash.respond')"
private const val SUBMIT_COSTS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.running-costs.submit')"
private const val APPROVE_COSTS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.running-costs.approve')"
private const val SEND_FUNDS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.funds.send')"
private const val CONFIRM_FUNDS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.funds.confirm')"
private const val LODGE = "@permissionAuthorizer.hasPermission(authentication, 'accounting.lodgements.manage')"
private const val CONFIRM_LODGEMENTS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.lodgements.confirm')"

@Tag(name = "Account — petty cash", description = "Fund petty cash, petty cash payments, payment history, statement, and petty cash sent to branches (docs/07 §4.6–4.8)")
@SecurityRequirement(name = "bearerAuth")
interface PettyCashApi {
    @Operation(summary = "Fund a petty cash box from a bank or cash account (float limit enforced)")
    fun fund(request: PettyCashFundingRequest): ApiEnvelope<PettyCashFundingRow>

    @Operation(summary = "Fundings; with pettyCashAccountId the summary is that box's aside (opening, funded, utilised, current)")
    fun fundings(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<PettyCashFundingRow>>

    @Operation(summary = "Fundings with an advanced filter body")
    fun searchFundings(request: ListSearchRequest): ApiEnvelope<PageResult<PettyCashFundingRow>>

    @Operation(summary = "Filters the fundings list accepts")
    fun fundingFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Reverse a funding while the petty cash still holds it")
    fun reverseFunding(id: UUID, request: ReasonRequest): ApiEnvelope<PettyCashFundingRow>

    @Operation(summary = "Pay from petty cash (category payable from petty cash, cash available, monthly cap)")
    fun pay(request: PettyCashPaymentRequest): ApiEnvelope<PettyCashPaymentView>

    @Operation(summary = "One petty cash payment")
    fun getPayment(id: UUID): ApiEnvelope<PettyCashPaymentView>

    @Operation(summary = "Reverse a petty cash payment (the cash goes back into the box)")
    fun reversePayment(id: UUID, request: ReasonRequest): ApiEnvelope<PettyCashPaymentView>

    @Operation(summary = "Petty Cash Payment History: petty cash payments and running costs paid from petty cash")
    fun paymentHistory(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<PettyCashHistoryRow>>

    @Operation(summary = "Payment history with an advanced filter body")
    fun searchPaymentHistory(request: ListSearchRequest): ApiEnvelope<PageResult<PettyCashHistoryRow>>

    @Operation(summary = "Filters the payment history accepts")
    fun paymentHistoryFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Petty Cash Statement for one box (or every box at a location): ledger (paged), funding, utilisation by category")
    fun statement(pettyCashAccountId: UUID?, locationId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageParams): ApiEnvelope<PettyCashStatementView>

    @Operation(summary = "Send petty cash to a branch; the source pays now and the branch accepts or rejects")
    fun sendAllocation(request: PettyCashAllocationRequest): ApiEnvelope<PettyCashAllocationRow>

    @Operation(summary = "Petty cash sent to branches (Pending / Accepted / Rejected)")
    fun allocations(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<PettyCashAllocationRow>>

    @Operation(summary = "Allocations with an advanced filter body")
    fun searchAllocations(request: ListSearchRequest): ApiEnvelope<PageResult<PettyCashAllocationRow>>

    @Operation(summary = "Filters the allocations list accepts")
    fun allocationFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Accept petty cash sent to your location (the whole amount)")
    fun acceptAllocation(id: UUID): ApiEnvelope<PettyCashAllocationRow>

    @Operation(summary = "Reject petty cash sent to your location (returned to the source; note required)")
    fun rejectAllocation(id: UUID, request: AllocationRejectRequest): ApiEnvelope<PettyCashAllocationRow>
}

@RestController
@RequestMapping("/api/v1/account/petty-cash")
class PettyCashController(private val pettyCash: PettyCashService) : PettyCashApi {
    @PostMapping("/fundings")
    @PreAuthorize(FUND)
    override fun fund(@Valid @RequestBody request: PettyCashFundingRequest) = ApiEnvelope(pettyCash.fund(request))

    @GetMapping("/fundings")
    @PreAuthorize(READ_ACCOUNTS)
    override fun fundings(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(pettyCash.fundings(ListSearchRequest.fromQuery(query)))

    @PostMapping("/fundings/search")
    @PreAuthorize(READ_ACCOUNTS)
    override fun searchFundings(@RequestBody request: ListSearchRequest) = ApiEnvelope(pettyCash.fundings(request))

    @GetMapping("/fundings/filters")
    @PreAuthorize(READ_ACCOUNTS)
    override fun fundingFilters() = ApiEnvelope(pettyCash.fundingFilterMetadata())

    @PostMapping("/fundings/{id}/reverse")
    @PreAuthorize(FUND)
    override fun reverseFunding(@PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest) = ApiEnvelope(pettyCash.reverseFunding(id, request.reason))

    @PostMapping("/payments")
    @PreAuthorize(PAY)
    override fun pay(@Valid @RequestBody request: PettyCashPaymentRequest) = ApiEnvelope(pettyCash.pay(request))

    @GetMapping("/payments/history")
    @PreAuthorize(READ_ACCOUNTS)
    override fun paymentHistory(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(pettyCash.paymentHistory(ListSearchRequest.fromQuery(query)))

    @PostMapping("/payments/history/search")
    @PreAuthorize(READ_ACCOUNTS)
    override fun searchPaymentHistory(@RequestBody request: ListSearchRequest) = ApiEnvelope(pettyCash.paymentHistory(request))

    @GetMapping("/payments/history/filters")
    @PreAuthorize(READ_ACCOUNTS)
    override fun paymentHistoryFilters() = ApiEnvelope(pettyCash.historyFilterMetadata())

    @GetMapping("/payments/{id}")
    @PreAuthorize(READ_ACCOUNTS)
    override fun getPayment(@PathVariable id: UUID) = ApiEnvelope(pettyCash.getPayment(id))

    @PostMapping("/payments/{id}/reverse")
    @PreAuthorize(PAY)
    override fun reversePayment(@PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest) = ApiEnvelope(pettyCash.reversePayment(id, request.reason))

    @GetMapping("/statement")
    @PreAuthorize(READ_ACCOUNTS)
    override fun statement(
        @RequestParam(required = false) pettyCashAccountId: UUID?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(pettyCash.statement(pettyCashAccountId, locationId, from, to, paging.toRequest()))

    @PostMapping("/allocations")
    @PreAuthorize(FUND)
    override fun sendAllocation(@Valid @RequestBody request: PettyCashAllocationRequest) = ApiEnvelope(pettyCash.sendAllocation(request))

    @GetMapping("/allocations")
    @PreAuthorize(READ_ACCOUNTS)
    override fun allocations(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(pettyCash.allocations(ListSearchRequest.fromQuery(query)))

    @PostMapping("/allocations/search")
    @PreAuthorize(READ_ACCOUNTS)
    override fun searchAllocations(@RequestBody request: ListSearchRequest) = ApiEnvelope(pettyCash.allocations(request))

    @GetMapping("/allocations/filters")
    @PreAuthorize(READ_ACCOUNTS)
    override fun allocationFilters() = ApiEnvelope(pettyCash.allocationFilterMetadata())

    @PostMapping("/allocations/{id}/accept")
    @PreAuthorize(RESPOND)
    override fun acceptAllocation(@PathVariable id: UUID) = ApiEnvelope(pettyCash.acceptAllocation(id))

    @PostMapping("/allocations/{id}/reject")
    @PreAuthorize(RESPOND)
    override fun rejectAllocation(@PathVariable id: UUID, @Valid @RequestBody request: AllocationRejectRequest) = ApiEnvelope(pettyCash.rejectAllocation(id, request.note))
}

@Tag(name = "Account — running costs", description = "Payment / Running Cost with drafts and four-eyes approval (docs/07 §4.9)")
@SecurityRequirement(name = "bearerAuth")
interface RunningCostsApi {
    @Operation(summary = "Save a draft (submit=false) or submit for approval (submit=true, gets an RC-n reference)")
    fun create(request: RunningCostRequest): ApiEnvelope<RunningCostView>

    @Operation(summary = "Running costs; awaitingMyApproval=true lists the ones waiting for you")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<RunningCostView>>

    @Operation(summary = "Running costs with an advanced filter body")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<RunningCostView>>

    @Operation(summary = "Filters the running-cost list accepts")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "One running cost")
    fun get(id: UUID): ApiEnvelope<RunningCostView>

    @Operation(summary = "Edit a draft or a rejected cost (submit=true resubmits it, keeping its reference)")
    fun update(id: UUID, request: RunningCostRequest): ApiEnvelope<RunningCostView>

    @Operation(summary = "Submit a draft or a rejected cost for approval")
    fun submit(id: UUID): ApiEnvelope<RunningCostView>

    @Operation(summary = "Approve and pay (not by the submitter, unless an Administrator and the setting allows)")
    fun approve(id: UUID): ApiEnvelope<RunningCostView>

    @Operation(summary = "Reject (reason required); nothing is paid")
    fun reject(id: UUID, request: RunningCostRejectRequest): ApiEnvelope<RunningCostView>

    @Operation(summary = "Reverse an approved cost; the money goes back to the paying account")
    fun reverse(id: UUID, request: ReasonRequest): ApiEnvelope<RunningCostView>

    @Operation(summary = "Delete a draft (only drafts: they aren't documents yet)")
    fun delete(id: UUID): ApiEnvelope<Map<String, Any>>
}

@RestController
@RequestMapping("/api/v1/account/running-costs")
class RunningCostsController(private val runningCosts: RunningCostService) : RunningCostsApi {
    @PostMapping
    @PreAuthorize(SUBMIT_COSTS)
    override fun create(@Valid @RequestBody request: RunningCostRequest) = ApiEnvelope(runningCosts.create(request))

    @GetMapping
    @PreAuthorize(READ_ACCOUNTS)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(runningCosts.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ_ACCOUNTS)
    override fun search(@RequestBody request: ListSearchRequest) = ApiEnvelope(runningCosts.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ_ACCOUNTS)
    override fun filters() = ApiEnvelope(runningCosts.filterMetadata())

    @GetMapping("/{id}")
    @PreAuthorize(READ_ACCOUNTS)
    override fun get(@PathVariable id: UUID) = ApiEnvelope(runningCosts.get(id))

    @PutMapping("/{id}")
    @PreAuthorize(SUBMIT_COSTS)
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: RunningCostRequest) = ApiEnvelope(runningCosts.update(id, request))

    @PostMapping("/{id}/submit")
    @PreAuthorize(SUBMIT_COSTS)
    override fun submit(@PathVariable id: UUID) = ApiEnvelope(runningCosts.submit(id))

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE_COSTS)
    override fun approve(@PathVariable id: UUID) = ApiEnvelope(runningCosts.approve(id))

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVE_COSTS)
    override fun reject(@PathVariable id: UUID, @Valid @RequestBody request: RunningCostRejectRequest) = ApiEnvelope(runningCosts.reject(id, request.rejectionReason))

    @PostMapping("/{id}/reverse")
    @PreAuthorize(APPROVE_COSTS)
    override fun reverse(@PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest) = ApiEnvelope(runningCosts.reverse(id, request.reason))

    @DeleteMapping("/{id}")
    @PreAuthorize(SUBMIT_COSTS)
    override fun delete(@PathVariable id: UUID): ApiEnvelope<Map<String, Any>> {
        runningCosts.deleteDraft(id)
        return ApiEnvelope(mapOf("id" to id, "deleted" to true))
    }
}

@Tag(name = "Account — funds awaiting", description = "Funds sent to a branch or factory and confirmed on arrival (docs/07 §4.14)")
@SecurityRequirement(name = "bearerAuth")
interface FundDisbursementsApi {
    @Operation(summary = "Send funds (BANK_TRANSFER or CASH) to a showroom/factory; the source pays now")
    fun send(request: FundDisbursementRequest): ApiEnvelope<FundDisbursementRow>

    @Operation(summary = "Disbursements; the destination's Awaiting cards are status=AWAITING")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<FundDisbursementRow>>

    @Operation(summary = "Disbursements with an advanced filter body")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<FundDisbursementRow>>

    @Operation(summary = "Filters the disbursement list accepts")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "One disbursement")
    fun get(id: UUID): ApiEnvelope<FundDisbursementRow>

    @Operation(summary = "Confirm what arrived (less than sent needs a reason and raises a shortfall)")
    fun confirm(id: UUID, request: FundConfirmRequest): ApiEnvelope<FundDisbursementRow>

    @Operation(summary = "Reject: nothing arrived; the full amount returns to the source")
    fun reject(id: UUID, request: ReasonRequest): ApiEnvelope<FundDisbursementRow>
}

@RestController
@RequestMapping("/api/v1/account/fund-disbursements")
class FundDisbursementsController(private val disbursements: FundDisbursementService) : FundDisbursementsApi {
    @PostMapping
    @PreAuthorize(SEND_FUNDS)
    override fun send(@Valid @RequestBody request: FundDisbursementRequest) = ApiEnvelope(disbursements.send(request))

    @GetMapping
    @PreAuthorize(READ_ACCOUNTS)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(disbursements.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ_ACCOUNTS)
    override fun search(@RequestBody request: ListSearchRequest) = ApiEnvelope(disbursements.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ_ACCOUNTS)
    override fun filters() = ApiEnvelope(disbursements.filterMetadata())

    @GetMapping("/{id}")
    @PreAuthorize(READ_ACCOUNTS)
    override fun get(@PathVariable id: UUID) = ApiEnvelope(disbursements.get(id))

    @PostMapping("/{id}/confirm")
    @PreAuthorize(CONFIRM_FUNDS)
    override fun confirm(@PathVariable id: UUID, @Valid @RequestBody request: FundConfirmRequest) = ApiEnvelope(disbursements.confirm(id, request))

    @PostMapping("/{id}/reject")
    @PreAuthorize(CONFIRM_FUNDS)
    override fun reject(@PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest) = ApiEnvelope(disbursements.reject(id, request.reason))
}

@Tag(name = "Account — cash lodgements", description = "Cash Transfer History: takings lodged to the bank (docs/07 §4.15)")
@SecurityRequirement(name = "bearerAuth")
interface CashLodgementsApi {
    @Operation(summary = "Record a lodgement: the cash leaves the till now (banked + retained = takings)")
    fun record(request: CashLodgementRequest): ApiEnvelope<CashLodgementRow>

    @Operation(summary = "Lodgements")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<CashLodgementRow>>

    @Operation(summary = "Lodgements with an advanced filter body")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<CashLodgementRow>>

    @Operation(summary = "Filters the lodgement list accepts")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "One lodgement")
    fun get(id: UUID): ApiEnvelope<CashLodgementRow>

    @Operation(summary = "Confirm the bank received it: credits the bank account")
    fun confirm(id: UUID): ApiEnvelope<CashLodgementRow>

    @Operation(summary = "The bank never received it: flagged to Management (the cash is not put back)")
    fun reject(id: UUID, request: ReasonRequest): ApiEnvelope<CashLodgementRow>
}

@RestController
@RequestMapping("/api/v1/account/cash-lodgements")
class CashLodgementsController(private val lodgements: CashLodgementService) : CashLodgementsApi {
    @PostMapping
    @PreAuthorize(LODGE)
    override fun record(@Valid @RequestBody request: CashLodgementRequest) = ApiEnvelope(lodgements.record(request))

    @GetMapping
    @PreAuthorize(READ_ACCOUNTS)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(lodgements.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ_ACCOUNTS)
    override fun search(@RequestBody request: ListSearchRequest) = ApiEnvelope(lodgements.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ_ACCOUNTS)
    override fun filters() = ApiEnvelope(lodgements.filterMetadata())

    @GetMapping("/{id}")
    @PreAuthorize(READ_ACCOUNTS)
    override fun get(@PathVariable id: UUID) = ApiEnvelope(lodgements.get(id))

    @PostMapping("/{id}/confirm")
    @PreAuthorize(CONFIRM_LODGEMENTS)
    override fun confirm(@PathVariable id: UUID) = ApiEnvelope(lodgements.confirm(id))

    @PostMapping("/{id}/reject")
    @PreAuthorize(CONFIRM_LODGEMENTS)
    override fun reject(@PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest) = ApiEnvelope(lodgements.reject(id, request.reason))
}
