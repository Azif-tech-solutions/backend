package com.zifiq.factory.api

import com.zifiq.factory.dto.FactoryTradeOfferRequest
import com.zifiq.factory.dto.FactoryTradeOfferSummary
import com.zifiq.factory.dto.FactoryTradeRespondRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import com.zifiq.platform.api.ApiEnvelope
import java.util.UUID

@Tag(name = "Factory Trading", description = "Offer Trade, Pending Trade, Trade Report")
@SecurityRequirement(name = "bearerAuth")
interface FactoryTradeApi {
    @Operation(summary = "Offer a stock trade to another factory")
    fun offerTrade(request: FactoryTradeOfferRequest): ApiEnvelope<FactoryTradeOfferSummary>

    @Operation(summary = "Accept or reject a factory trade offer")
    fun respondToTrade(id: UUID, request: FactoryTradeRespondRequest): ApiEnvelope<FactoryTradeOfferSummary>

    @Operation(summary = "List factory-to-factory trade offers")
    fun listTrades(locationId: UUID?, status: String?): ApiEnvelope<List<FactoryTradeOfferSummary>>
}
