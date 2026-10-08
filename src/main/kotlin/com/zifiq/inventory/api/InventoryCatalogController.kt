package com.zifiq.inventory.api

import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.inventory.dto.*
import com.zifiq.inventory.service.DriverService
import com.zifiq.inventory.service.GroupCardService
import com.zifiq.inventory.service.PriceListService
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

@Tag(name = "Inventory — drivers", description = "Drivers and vehicles named on waybills (docs/05 §4.13)")
@SecurityRequirement(name = "bearerAuth")
interface DriversApi {
    @Operation(summary = "List drivers (q: name, vehicle, phone, licence; status)")
    fun list(q: String?, status: String?): ApiEnvelope<List<DriverSummary>>

    @Operation(summary = "Driver picker (active drivers)")
    fun lookup(q: String?): ApiEnvelope<List<DriverSummary>>

    @Operation(summary = "Register a driver")
    fun create(request: DriverRequest): ApiEnvelope<DriverSummary>

    @Operation(summary = "Edit a driver")
    fun update(id: UUID, request: DriverRequest): ApiEnvelope<DriverSummary>

    @Operation(summary = "Activate or deactivate a driver")
    fun changeStatus(id: UUID, request: DriverStatusRequest): ApiEnvelope<DriverSummary>
}

@RestController
@RequestMapping("/api/v1/inventory/drivers")
class DriversController(private val driverService: DriverService) : DriversApi {
    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.drivers.read')")
    override fun list(@RequestParam(required = false) q: String?, @RequestParam(required = false) status: String?): ApiEnvelope<List<DriverSummary>> =
        ApiEnvelope(driverService.list(q, status))

    @GetMapping("/lookup")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.drivers.read')")
    override fun lookup(@RequestParam(required = false) q: String?): ApiEnvelope<List<DriverSummary>> =
        ApiEnvelope(driverService.list(q, "ACTIVE", limit = 20))

    @PostMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.drivers.manage')")
    override fun create(@Valid @RequestBody request: DriverRequest): ApiEnvelope<DriverSummary> = ApiEnvelope(driverService.create(request))

    @PutMapping("/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.drivers.manage')")
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: DriverRequest): ApiEnvelope<DriverSummary> =
        ApiEnvelope(driverService.update(id, request))

    @PutMapping("/{id}/status")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.drivers.manage')")
    override fun changeStatus(@PathVariable id: UUID, @Valid @RequestBody request: DriverStatusRequest): ApiEnvelope<DriverSummary> =
        ApiEnvelope(driverService.changeStatus(id, request.status!!))
}

@Tag(name = "Inventory — price list", description = "Effective-dated retail and dealer prices (docs/05 §4.12)")
@SecurityRequirement(name = "bearerAuth")
interface PriceListApi {
    @Operation(summary = "Prices on a date (asAt, default today) with the next scheduled change; margins for cost-authorised users")
    fun list(query: MultiValueMap<String, String>): ApiEnvelope<PageResult<PriceListRow>>

    @Operation(summary = "Price list with an advanced filter tree (asAt/locationId go in params)")
    fun search(request: ListSearchRequest): ApiEnvelope<PageResult<PriceListRow>>

    @Operation(summary = "Filterable fields for the price list")
    fun filters(): ApiEnvelope<FilterMetadata>

    @Operation(summary = "Publish prices effective from a date (today or later) as one PL-n batch")
    fun publish(request: PricePublishRequest): ApiEnvelope<PricePublishResult>

    @Operation(summary = "Change a price that isn't in effect yet")
    fun updateEntry(priceId: UUID, request: PriceEntryUpdateRequest): ApiEnvelope<PriceHistoryRow>

    @Operation(summary = "One product's full price history")
    fun itemHistory(stockItemId: UUID): ApiEnvelope<List<PriceHistoryRow>>
}

@RestController
@RequestMapping("/api/v1/inventory")
class PriceListController(private val priceListService: PriceListService) : PriceListApi {
    @GetMapping("/price-list")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.pricing.read')")
    override fun list(@RequestParam query: MultiValueMap<String, String>): ApiEnvelope<PageResult<PriceListRow>> =
        ApiEnvelope(priceListService.list(ListSearchRequest.fromQuery(query)))

    @PostMapping("/price-list/search")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.pricing.read')")
    override fun search(@RequestBody request: ListSearchRequest): ApiEnvelope<PageResult<PriceListRow>> = ApiEnvelope(priceListService.list(request))

    @GetMapping("/price-list/filters")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.pricing.read')")
    override fun filters(): ApiEnvelope<FilterMetadata> = ApiEnvelope(priceListService.filters())

    @PostMapping("/price-list/publish")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.pricing.manage')")
    override fun publish(@Valid @RequestBody request: PricePublishRequest): ApiEnvelope<PricePublishResult> = ApiEnvelope(priceListService.publish(request))

    @PutMapping("/price-list/entries/{priceId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.pricing.manage')")
    override fun updateEntry(@PathVariable priceId: UUID, @Valid @RequestBody request: PriceEntryUpdateRequest): ApiEnvelope<PriceHistoryRow> =
        ApiEnvelope(priceListService.updateEntry(priceId, request))

    @GetMapping("/stock-items/{stockItemId}/prices")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'inventory.pricing.read')")
    override fun itemHistory(@PathVariable stockItemId: UUID): ApiEnvelope<List<PriceHistoryRow>> = ApiEnvelope(priceListService.itemHistory(stockItemId))
}

@Tag(name = "Inventory — group cards", description = "Stock and bin cards for a whole group of products (docs/05 §4.10)")
@SecurityRequirement(name = "bearerAuth")
interface GroupCardsApi {
    @Operation(summary = "Group Stock Card: every movement of every item in the group, with a running group total")
    fun groupCard(groupBy: String, groupId: UUID, locationId: UUID?, from: LocalDate?, to: LocalDate?): ApiEnvelope<GroupStockCard>

    @Operation(summary = "Group Bin Card: daily totals for the group")
    fun groupBinCard(groupBy: String, groupId: UUID, locationId: UUID?, from: LocalDate?, to: LocalDate?): ApiEnvelope<GroupBinCard>
}

private const val READ_STOCK = "@permissionAuthorizer.hasPermission(authentication, 'inventory.stock.read') or @permissionAuthorizer.hasPermission(authentication, 'showroom.stock.read')"

@RestController
@RequestMapping("/api/v1/inventory/stock")
class GroupCardsController(private val groupCardService: GroupCardService) : GroupCardsApi {
    @GetMapping("/group-card")
    @PreAuthorize(READ_STOCK)
    override fun groupCard(
        @RequestParam(defaultValue = "CATEGORY") groupBy: String,
        @RequestParam groupId: UUID,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ApiEnvelope<GroupStockCard> = ApiEnvelope(groupCardService.stockCard(groupBy, groupId, locationId, from, to))

    @GetMapping("/group-bin-card")
    @PreAuthorize(READ_STOCK)
    override fun groupBinCard(
        @RequestParam(defaultValue = "CATEGORY") groupBy: String,
        @RequestParam groupId: UUID,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ApiEnvelope<GroupBinCard> = ApiEnvelope(groupCardService.binCard(groupBy, groupId, locationId, from, to))
}
