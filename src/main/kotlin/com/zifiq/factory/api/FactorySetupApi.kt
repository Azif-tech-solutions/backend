package com.zifiq.factory.api

import com.zifiq.factory.dto.*
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID

@Tag(name = "Factory Setup", description = "Factories, Suppliers, Manufacturers, Units of Measure, Categories")
@SecurityRequirement(name = "bearerAuth")
interface FactorySetupApi {
    @Operation(summary = "List factories")
    fun getFactories(): ApiEnvelope<List<FactorySummary>>

    @Operation(summary = "Register a new factory")
    fun createFactory(request: FactoryRequest): ApiEnvelope<FactorySummary>

    @Operation(summary = "Update a factory")
    fun updateFactory(id: UUID, request: FactoryRequest): ApiEnvelope<FactorySummary>

    @Operation(summary = "Activate, suspend, or mark a factory pending")
    fun updateFactoryStatus(id: UUID, request: FactoryStatusRequest): ApiEnvelope<FactorySummary>

    @Operation(summary = "List registered raw material suppliers")
    fun getSuppliers(): ApiEnvelope<List<SupplierSummary>>

    @Operation(summary = "Register a raw material supplier")
    fun registerSupplier(request: SupplierRegistrationRequest): ApiEnvelope<SupplierSummary>

    @Operation(summary = "List registered manufacturers")
    fun getManufacturers(): ApiEnvelope<List<ManufacturerSummary>>

    @Operation(summary = "Register a manufacturer")
    fun registerManufacturer(request: ManufacturerRequest): ApiEnvelope<ManufacturerSummary>

    @Operation(summary = "List units of measure (UOM)")
    fun getUnitsOfMeasure(): ApiEnvelope<List<UnitOfMeasureSummary>>

    @Operation(summary = "Define unit of measure")
    fun addUnitOfMeasure(request: UnitOfMeasureRequest): ApiEnvelope<UnitOfMeasureSummary>

    @Operation(summary = "List product and material categories")
    fun getCategories(): ApiEnvelope<List<CategorySummary>>

    @Operation(summary = "Create product or material category")
    fun addCategory(request: CategoryRegistrationRequest): ApiEnvelope<CategorySummary>
}
