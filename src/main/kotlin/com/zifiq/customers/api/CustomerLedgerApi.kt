package com.zifiq.customers.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.customers.dto.LedgerPage
import com.zifiq.customers.dto.ReclassificationRequest
import com.zifiq.customers.dto.ReclassificationSummary
import com.zifiq.customers.dto.ReversalRequest
import com.zifiq.customers.dto.WalletAdjustmentRequest
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.LedgerEntryRef
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.util.MultiValueMap

@Tag(name = "Customers — wallet ledger", description = "Customer History, admin adjustments and reversals (docs/04 §4.5, §4.6)")
@SecurityRequirement(name = "bearerAuth")
interface CustomerLedgerApi {
    @Operation(summary = "Wallet or deposit ledger, oldest first, with opening/closing balances and totals")
    fun ledger(id: UUID, balanceType: BalanceType, query: MultiValueMap<String, String>): ApiEnvelope<LedgerPage>

    @Operation(summary = "Filterable fields for the ledger")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Self-service: the caller's own ledger")
    fun myLedger(balanceType: BalanceType, query: MultiValueMap<String, String>): ApiEnvelope<LedgerPage>

    @Operation(summary = "Post an Administrator's wallet/deposit correction")
    fun adjust(id: UUID, request: WalletAdjustmentRequest): ApiEnvelope<LedgerEntryRef>

    @Operation(summary = "Reverse an adjustment made in this module (other entries are reversed by voiding their source document)")
    fun reverse(entryId: UUID, request: ReversalRequest): ApiEnvelope<LedgerEntryRef>
}

@Tag(name = "Customers — reclassification", description = "Promotion / demotion between customer types (docs/04 §4.4)")
@SecurityRequirement(name = "bearerAuth")
interface ReclassificationApi {
    @Operation(summary = "Promote or demote a customer, now or from a future date")
    fun reclassify(id: UUID, request: ReclassificationRequest): ApiEnvelope<ReclassificationSummary>

    @Operation(summary = "Cancel a scheduled reclassification")
    fun cancel(id: UUID, reclassificationId: UUID): ApiEnvelope<ReclassificationSummary>

    @Operation(summary = "Reclassification history")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<ReclassificationSummary>>

    @Operation(summary = "Reclassification history with an advanced filter tree")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<ReclassificationSummary>>

    @Operation(summary = "Filterable fields for the reclassification history")
    fun filters(): ApiEnvelope<FilterMetadata>
}
