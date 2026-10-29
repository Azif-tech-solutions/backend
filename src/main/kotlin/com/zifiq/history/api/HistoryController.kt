package com.zifiq.history.api

import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.history.service.StockMovementHistoryService
import com.zifiq.platform.api.ApiEnvelope
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/history")
class HistoryController(
    private val stockMovements: StockMovementHistoryService,
) : HistoryApi {
    @GetMapping("/stock-movements")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'history.stock-movements.read')")
    override fun stockMovements(@ParameterObject @RequestParam query: MultiValueMap<String, String>) =
        ApiEnvelope(stockMovements.list(ListSearchRequest.fromQuery(query)))

    @GetMapping("/stock-movements/filters")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'history.stock-movements.read')")
    override fun stockMovementFilters() = ApiEnvelope(stockMovements.filterMetadata())
}
