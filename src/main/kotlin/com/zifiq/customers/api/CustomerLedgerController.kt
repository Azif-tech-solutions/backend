package com.zifiq.customers.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.customers.dto.LedgerPage
import com.zifiq.customers.dto.ReclassificationRequest
import com.zifiq.customers.dto.ReclassificationSummary
import com.zifiq.customers.dto.ReversalRequest
import com.zifiq.customers.dto.WalletAdjustmentRequest
import com.zifiq.customers.service.CustomerLedgerService
import com.zifiq.customers.service.ReclassificationService
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.LedgerEntryRef
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/customers")
class CustomerLedgerController(
    private val ledgerService: CustomerLedgerService,
) : CustomerLedgerApi {
    @GetMapping("/{id}/ledger")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.ledger.read')")
    override fun ledger(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "WALLET") balanceType: BalanceType,
        @RequestParam query: MultiValueMap<String, String>,
    ): ApiEnvelope<LedgerPage> = ApiEnvelope(ledgerService.ledger(id, balanceType, ListSearchRequest.fromQuery(query)))

    @GetMapping("/ledger/filters")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.ledger.read')")
    override fun filters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(ledgerService.filters())

    @GetMapping("/me/ledger")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.self.read')")
    override fun myLedger(
        @RequestParam(defaultValue = "WALLET") balanceType: BalanceType,
        @RequestParam query: MultiValueMap<String, String>,
    ): ApiEnvelope<LedgerPage> = ApiEnvelope(ledgerService.myLedger(balanceType, ListSearchRequest.fromQuery(query)))

    @PostMapping("/{id}/wallet-adjustments")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.wallet.adjust')")
    override fun adjust(@PathVariable id: UUID, @Valid @RequestBody request: WalletAdjustmentRequest): ApiEnvelope<LedgerEntryRef> =
        ApiEnvelope(ledgerService.adjust(id, request))

    @PostMapping("/ledger-entries/{entryId}/reverse")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.wallet.adjust')")
    override fun reverse(@PathVariable entryId: UUID, @Valid @RequestBody request: ReversalRequest): ApiEnvelope<LedgerEntryRef> =
        ApiEnvelope(ledgerService.reverse(entryId, request))
}

@RestController
@RequestMapping("/api/v1/customers")
class ReclassificationController(
    private val reclassificationService: ReclassificationService,
) : ReclassificationApi {
    @PostMapping("/{id}/reclassifications")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.reclassify')")
    override fun reclassify(@PathVariable id: UUID, @Valid @RequestBody request: ReclassificationRequest): ApiEnvelope<ReclassificationSummary> =
        ApiEnvelope(reclassificationService.reclassify(id, request))

    @DeleteMapping("/{id}/reclassifications/{reclassificationId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.reclassify')")
    override fun cancel(@PathVariable id: UUID, @PathVariable reclassificationId: UUID): ApiEnvelope<ReclassificationSummary> =
        ApiEnvelope(reclassificationService.cancel(id, reclassificationId))

    @GetMapping("/reclassifications")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun list(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ReclassificationSummary>> =
        ApiEnvelope(reclassificationService.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/reclassifications/search")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun search(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<ReclassificationSummary>> =
        ApiEnvelope(reclassificationService.list(request))

    @GetMapping("/reclassifications/filters")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'customers.read')")
    override fun filters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(reclassificationService.filters())
}
