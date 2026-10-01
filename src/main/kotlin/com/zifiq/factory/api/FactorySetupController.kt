package com.zifiq.factory.api

import com.zifiq.factory.dto.*
import com.zifiq.factory.service.FactorySetupService
import com.zifiq.platform.api.ApiEnvelope
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/factory/setup")
class FactorySetupController(
    private val factorySetupService: FactorySetupService,
) : FactorySetupApi {
    @GetMapping("/factories")
    override fun getFactories(): ApiEnvelope<List<FactorySummary>> =
        ApiEnvelope(factorySetupService.getFactories())

    @PostMapping("/factories")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.setup.manage')")
    override fun createFactory(@Valid @RequestBody request: FactoryRequest): ApiEnvelope<FactorySummary> =
        ApiEnvelope(factorySetupService.createFactory(request))

    @PutMapping("/factories/{id}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.setup.manage')")
    override fun updateFactory(
        @PathVariable id: UUID,
        @Valid @RequestBody request: FactoryRequest,
    ): ApiEnvelope<FactorySummary> =
        ApiEnvelope(factorySetupService.updateFactory(id, request))

    @PutMapping("/factories/{id}/status")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.setup.manage')")
    override fun updateFactoryStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody request: FactoryStatusRequest,
    ): ApiEnvelope<FactorySummary> =
        ApiEnvelope(factorySetupService.updateFactoryStatus(id, request.status))

    @GetMapping("/suppliers")
    override fun getSuppliers(): ApiEnvelope<List<SupplierSummary>> =
        ApiEnvelope(factorySetupService.getSuppliers())

    @PostMapping("/suppliers")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.setup.manage')")
    override fun registerSupplier(@Valid @RequestBody request: SupplierRegistrationRequest): ApiEnvelope<SupplierSummary> =
        ApiEnvelope(factorySetupService.registerSupplier(request))

    @GetMapping("/manufacturers")
    override fun getManufacturers(): ApiEnvelope<List<ManufacturerSummary>> =
        ApiEnvelope(factorySetupService.getManufacturers())

    @PostMapping("/manufacturers")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.setup.manage')")
    override fun registerManufacturer(@Valid @RequestBody request: ManufacturerRequest): ApiEnvelope<ManufacturerSummary> =
        ApiEnvelope(factorySetupService.registerManufacturer(request))

    @GetMapping("/units")
    override fun getUnitsOfMeasure(): ApiEnvelope<List<UnitOfMeasureSummary>> =
        ApiEnvelope(factorySetupService.getUnitsOfMeasure())

    @PostMapping("/units")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.setup.manage')")
    override fun addUnitOfMeasure(@Valid @RequestBody request: UnitOfMeasureRequest): ApiEnvelope<UnitOfMeasureSummary> =
        ApiEnvelope(factorySetupService.addUnitOfMeasure(request))

    @GetMapping("/categories")
    override fun getCategories(): ApiEnvelope<List<CategorySummary>> =
        ApiEnvelope(factorySetupService.getCategories())

    @PostMapping("/categories")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'factory.setup.manage')")
    override fun addCategory(@Valid @RequestBody request: CategoryRegistrationRequest): ApiEnvelope<CategorySummary> =
        ApiEnvelope(factorySetupService.addCategory(request))
}
