package com.zifiq.account.api

import org.springdoc.core.annotations.ParameterObject
import com.zifiq.common.paging.PageParams
import com.zifiq.account.dto.AuditStatusRequest
import com.zifiq.account.dto.ConfirmationStats
import com.zifiq.account.dto.DeclineRequest
import com.zifiq.account.dto.LocationVatHistoryRow
import com.zifiq.account.dto.LocationVatList
import com.zifiq.account.dto.LocationVatRequest
import com.zifiq.account.dto.OtherIncomeRequest
import com.zifiq.account.dto.ReasonRequest
import com.zifiq.account.dto.ReceiptRow
import com.zifiq.account.dto.TenderableGeneralReport
import com.zifiq.account.dto.TenderablePayableRow
import com.zifiq.account.dto.TenderableWalletFundRow
import com.zifiq.account.dto.WalletFundingRequest
import com.zifiq.account.dto.WalletFundingRow
import com.zifiq.account.dto.WalletWithdrawalRequest
import com.zifiq.account.dto.WalletWithdrawalRow
import com.zifiq.account.service.LocationVatService
import com.zifiq.account.service.ReceiptService
import com.zifiq.account.service.TenderableService
import com.zifiq.account.service.WalletMoneyService
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
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

private const val READ_RECEIPTS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.receipts.read') or @permissionAuthorizer.hasPermission(authentication, 'accounting.read')"
private const val CONFIRM_RECEIPTS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.receipts.confirm')"
private const val VOID_RECEIPTS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.receipts.void')"
private const val RECORD_INCOME = "@permissionAuthorizer.hasPermission(authentication, 'accounting.receipts.record')"
private const val ACCOUNTS_READ = "@permissionAuthorizer.hasPermission(authentication, 'accounting.read')"
private const val FUND_WALLETS = "@permissionAuthorizer.hasPermission(authentication, 'accounting.wallet.fund')"
private const val WITHDRAW = "@permissionAuthorizer.hasPermission(authentication, 'accounting.wallet.withdraw') or @permissionAuthorizer.hasPermission(authentication, 'accounting.wallet.recover')"
private const val RECOVER = "@permissionAuthorizer.hasPermission(authentication, 'accounting.wallet.recover')"
private const val TENDERABLE = "@permissionAuthorizer.hasPermission(authentication, 'accounting.tenderable.read')"
private const val MANAGE_VAT = "@permissionAuthorizer.hasPermission(authentication, 'accounting.vat.manage')"

@Tag(name = "Account — receipts", description = "Payment receipts, POS / Fund Transfer Confirmation, other income (docs/07 §4.10, §4.18)")
@SecurityRequirement(name = "bearerAuth")
interface ReceiptsApi {
    @Operation(summary = "Receipts (cashiers see only their own); the confirmation queue is confirmationStatus=PENDING&channel=POS,FUND_TRANSFER,CHEQUE")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ReceiptRow>>

    @Operation(summary = "Receipts with an advanced filter body")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<ReceiptRow>>

    @Operation(summary = "Filters the receipts list accepts")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Header of the confirmation screen: pending count and value, approved and declined today")
    fun confirmationStats(locationId: UUID?): ApiEnvelope<ConfirmationStats>

    @Operation(summary = "One receipt")
    fun get(id: UUID): ApiEnvelope<ReceiptRow>

    @Operation(summary = "Approve a POS / transfer / cheque receipt: the bank is credited (and a wallet funding completed)")
    fun approve(id: UUID): ApiEnvelope<ReceiptRow>

    @Operation(summary = "Decline: nothing is posted (a wallet funding is never credited); note required")
    fun decline(id: UUID, request: DeclineRequest): ApiEnvelope<ReceiptRow>

    @Operation(summary = "Void a wallet/deposit funding or other-income receipt (sales and invoice payments are voided in their own modules)")
    fun void(id: UUID, request: ReasonRequest): ApiEnvelope<ReceiptRow>

    @Operation(summary = "Printable receipt (PDF)")
    fun pdf(id: UUID): ResponseEntity<ByteArray>
}

@RestController
@RequestMapping("/api/v1/account/receipts")
class ReceiptsController(private val receipts: ReceiptService) : ReceiptsApi {
    @GetMapping
    @PreAuthorize(READ_RECEIPTS)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(receipts.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/search")
    @PreAuthorize(READ_RECEIPTS)
    override fun search(@RequestBody request: ListSearchRequest) = ApiEnvelope(receipts.list(request))

    @GetMapping("/filters")
    @PreAuthorize(READ_RECEIPTS)
    override fun filters() = ApiEnvelope(receipts.filterMetadata())

    @GetMapping("/confirmation-stats")
    @PreAuthorize(READ_RECEIPTS)
    override fun confirmationStats(@RequestParam(required = false) locationId: UUID?) = ApiEnvelope(receipts.confirmationStats(locationId))

    @GetMapping("/{id}")
    @PreAuthorize(READ_RECEIPTS)
    override fun get(@PathVariable id: UUID) = ApiEnvelope(receipts.getRow(id))

    @PostMapping("/{id}/approve")
    @PreAuthorize(CONFIRM_RECEIPTS)
    override fun approve(@PathVariable id: UUID) = ApiEnvelope(receipts.approve(id))

    @PostMapping("/{id}/decline")
    @PreAuthorize(CONFIRM_RECEIPTS)
    override fun decline(@PathVariable id: UUID, @Valid @RequestBody request: DeclineRequest) = ApiEnvelope(receipts.decline(id, request.note))

    @PostMapping("/{id}/void")
    @PreAuthorize(VOID_RECEIPTS)
    override fun void(@PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest) = ApiEnvelope(receipts.void(id, request.reason))

    @GetMapping("/{id}/receipt.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize(READ_RECEIPTS)
    override fun pdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val printed = receipts.pdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("${printed.ref}.pdf").build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(printed.pdf)
    }
}

@Tag(name = "Account — other income", description = "By-product and scrap sales, rent, interest and other money received (docs/07 §4.18)")
@SecurityRequirement(name = "bearerAuth")
interface OtherIncomeApi {
    @Operation(summary = "Record other income (a receipt; POS / transfer / cheque wait for confirmation as usual)")
    fun record(request: OtherIncomeRequest): ApiEnvelope<ReceiptRow>

    @Operation(summary = "Other income received")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ReceiptRow>>
}

@RestController
@RequestMapping("/api/v1/account/other-income")
class OtherIncomeController(private val receipts: ReceiptService) : OtherIncomeApi {
    @PostMapping
    @PreAuthorize(RECORD_INCOME)
    override fun record(@Valid @RequestBody request: OtherIncomeRequest) = ApiEnvelope(receipts.recordOtherIncome(request))

    @GetMapping
    @PreAuthorize(ACCOUNTS_READ)
    override fun list(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(receipts.otherIncome(ListSearchRequest.fromQuery(query)))
}

@Tag(name = "Account — wallet money", description = "Wallet / deposit funding and withdrawals (docs/07 §4.12–4.13)")
@SecurityRequirement(name = "bearerAuth")
interface WalletMoneyApi {
    @Operation(summary = "Fund a dealer's wallet or a special customer's deposit; credited at once for cash, on confirmation otherwise")
    fun fund(request: WalletFundingRequest): ApiEnvelope<WalletFundingRow>

    @Operation(summary = "Wallet and deposit fundings")
    fun fundings(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<WalletFundingRow>>

    @Operation(summary = "Filters the fundings list accepts")
    fun fundingFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Withdraw from a wallet or refund a deposit (a dealer request needs a verified OTP for this dealer and amount)")
    fun withdraw(request: WalletWithdrawalRequest): ApiEnvelope<WalletWithdrawalRow>

    @Operation(summary = "Withdrawals and deposit refunds (Audit's Wallet Withdrawal History reads the same data)")
    fun withdrawals(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<WalletWithdrawalRow>>

    @Operation(summary = "Filters the withdrawals list accepts")
    fun withdrawalFilters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "One withdrawal")
    fun getWithdrawal(id: UUID): ApiEnvelope<WalletWithdrawalRow>

    @Operation(summary = "Flag a withdrawal for review (UNDER_REVIEW) or clear the flag (COMPLETED)")
    fun setAuditStatus(id: UUID, request: AuditStatusRequest): ApiEnvelope<WalletWithdrawalRow>

    @Operation(summary = "Reverse a withdrawal: the balance is credited back and the payout returned")
    fun reverse(id: UUID, request: ReasonRequest): ApiEnvelope<WalletWithdrawalRow>
}

@RestController
@RequestMapping("/api/v1/account")
class WalletMoneyController(private val wallets: WalletMoneyService) : WalletMoneyApi {
    @PostMapping("/wallet-fundings")
    @PreAuthorize(FUND_WALLETS)
    override fun fund(@Valid @RequestBody request: WalletFundingRequest) = ApiEnvelope(wallets.fund(request))

    @GetMapping("/wallet-fundings")
    @PreAuthorize(ACCOUNTS_READ)
    override fun fundings(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(wallets.fundings(ListSearchRequest.fromQuery(query)))

    @GetMapping("/wallet-fundings/filters")
    @PreAuthorize(ACCOUNTS_READ)
    override fun fundingFilters() = ApiEnvelope(wallets.fundingFilterMetadata())

    @PostMapping("/wallet-withdrawals")
    @PreAuthorize(WITHDRAW)
    override fun withdraw(@Valid @RequestBody request: WalletWithdrawalRequest) = ApiEnvelope(wallets.withdraw(request))

    @GetMapping("/wallet-withdrawals")
    @PreAuthorize(ACCOUNTS_READ)
    override fun withdrawals(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(wallets.withdrawals(ListSearchRequest.fromQuery(query)))

    @GetMapping("/wallet-withdrawals/filters")
    @PreAuthorize(ACCOUNTS_READ)
    override fun withdrawalFilters() = ApiEnvelope(wallets.withdrawalFilterMetadata())

    @GetMapping("/wallet-withdrawals/{id}")
    @PreAuthorize(ACCOUNTS_READ)
    override fun getWithdrawal(@PathVariable id: UUID) = ApiEnvelope(wallets.getWithdrawal(id))

    @PutMapping("/wallet-withdrawals/{id}/audit-status")
    @PreAuthorize(RECOVER)
    override fun setAuditStatus(@PathVariable id: UUID, @Valid @RequestBody request: AuditStatusRequest) = ApiEnvelope(wallets.setAuditStatus(id, request.auditStatus!!))

    @PostMapping("/wallet-withdrawals/{id}/reverse")
    @PreAuthorize(RECOVER)
    override fun reverse(@PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest) = ApiEnvelope(wallets.reverse(id, request.reason))
}

@Tag(name = "Account — tenderable", description = "What each cashier took, by channel (docs/07 §4.11)")
@SecurityRequirement(name = "bearerAuth")
interface TenderableApi {
    @Operation(summary = "Payable: one row per cashier per day, newest day first, paged (pending and declined receipts shown apart, never in the total)")
    fun payable(locationId: UUID?, cashierUserId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageParams): ApiEnvelope<PageResult<TenderablePayableRow>>

    @Operation(summary = "Receipt Track: the receipts list, for the Tenderable screen")
    fun receipts(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ReceiptRow>>

    @Operation(summary = "Wallet Funds: dealers' fundings and return credits")
    fun walletFunds(customerId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageParams): ApiEnvelope<PageResult<TenderableWalletFundRow>>

    @Operation(summary = "General report: per cashier for the period, with totals")
    fun generalReport(locationId: UUID?, from: LocalDate?, to: LocalDate?): ApiEnvelope<TenderableGeneralReport>
}

@RestController
@RequestMapping("/api/v1/account/tenderable")
class TenderableController(private val tenderable: TenderableService, private val receipts: ReceiptService) : TenderableApi {
    @GetMapping("/payable")
    @PreAuthorize(TENDERABLE)
    override fun payable(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) cashierUserId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(tenderable.payable(locationId, cashierUserId, from, to, paging.toRequest()))

    @GetMapping("/receipts")
    @PreAuthorize(TENDERABLE)
    override fun receipts(@RequestParam query: MultiValueMap<String, String>) = ApiEnvelope(receipts.list(ListSearchRequest.fromQuery(query)))

    @GetMapping("/wallet-funds")
    @PreAuthorize(TENDERABLE)
    override fun walletFunds(
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @ParameterObject paging: PageParams,
    ) = ApiEnvelope(tenderable.walletFunds(customerId, from, to, paging.toRequest()))

    @GetMapping("/general-report")
    @PreAuthorize(TENDERABLE)
    override fun generalReport(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ) = ApiEnvelope(tenderable.generalReport(locationId, from, to))
}

@Tag(name = "VAT Control", description = "VAT rate, collection and registration number per showroom/factory, effective-dated (docs/07 §4.17)")
@SecurityRequirement(name = "bearerAuth")
interface LocationVatApi {
    @Operation(summary = "Each showroom/factory's VAT in force today, and any scheduled change; disabledCount drives the warning")
    fun list(): ApiEnvelope<LocationVatList>

    @Operation(summary = "Set a location's VAT from a date (today or later); today's takes effect at once")
    fun set(locationId: UUID, request: LocationVatRequest): ApiEnvelope<LocationVatList>

    @Operation(summary = "A location's VAT history")
    fun history(locationId: UUID): ApiEnvelope<List<LocationVatHistoryRow>>
}

@RestController
@RequestMapping("/api/v1/account/vat-control/locations")
class LocationVatController(private val vat: LocationVatService) : LocationVatApi {
    @GetMapping
    @PreAuthorize(ACCOUNTS_READ)
    override fun list() = ApiEnvelope(vat.list())

    @PostMapping("/{locationId}")
    @PreAuthorize(MANAGE_VAT)
    override fun set(@PathVariable locationId: UUID, @Valid @RequestBody request: LocationVatRequest) = ApiEnvelope(vat.set(locationId, request))

    @GetMapping("/{locationId}/history")
    @PreAuthorize(ACCOUNTS_READ)
    override fun history(@PathVariable locationId: UUID) = ApiEnvelope(vat.history(locationId))
}
