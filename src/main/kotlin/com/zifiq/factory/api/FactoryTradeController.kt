package com.zifiq.factory.api

import com.zifiq.factory.dto.FactoryTradeOfferRequest
import com.zifiq.factory.dto.FactoryTradeOfferSummary
import com.zifiq.factory.dto.FactoryTradeRespondRequest
import com.zifiq.factory.service.FactoryTradeService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/factory/trades")
class FactoryTradeController(
    private val factoryTradeService: FactoryTradeService,
) : FactoryTradeApi {
    @PostMapping("/offer")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.trade.manage')")
    override fun offerTrade(@Valid @RequestBody request: FactoryTradeOfferRequest): ApiEnvelope<FactoryTradeOfferSummary> =
        ApiEnvelope(factoryTradeService.offerTrade(request))

    @PostMapping("/{id}/respond")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.trade.manage')")
    override fun respondToTrade(
        @PathVariable id: UUID,
        @Valid @RequestBody request: FactoryTradeRespondRequest,
    ): ApiEnvelope<FactoryTradeOfferSummary> =
        ApiEnvelope(factoryTradeService.respondToTrade(id, request.decision))

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.trade.manage')")
    override fun listTrades(
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) status: String?,
    ): ApiEnvelope<List<FactoryTradeOfferSummary>> =
        ApiEnvelope(factoryTradeService.listTrades(locationId, status))
}
