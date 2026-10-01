package com.zifiq.factory.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.util.UUID

// ---- Factory Setup: Factories ----

data class FactoryRequest(
    val code: String?,
    @field:NotBlank val name: String,
    val address: String?,
    val state: String?,
    val phone: String?,
    val vatRate: BigDecimal?,
    val vatEnabled: Boolean?,
    val vatRegistrationNumber: String?,
)

data class FactoryStatusRequest(
    @field:Pattern(regexp = "ACTIVE|PENDING|SUSPENDED") val status: String,
)

data class FactorySummary(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val state: String?,
    val phone: String?,
    val managerUserId: UUID?,
    val vatRate: BigDecimal,
    val vatEnabled: Boolean,
    val vatRegistrationNumber: String?,
    val status: String,
)

// ---- Factory Setup: Suppliers ----

data class SupplierRegistrationRequest(
    val supplierCode: String?,
    @field:NotBlank val companyName: String,
    @field:NotBlank val contactEmail: String,
    val phone: String?,
)

data class SupplierSummary(
    val id: UUID,
    val supplierCode: String,
    val companyName: String,
    val contactEmail: String,
    val phone: String?,
)

// ---- Factory Setup: Manufacturers ----

data class ManufacturerRequest(
    val manufacturerCode: String?,
    @field:NotBlank val name: String,
    val country: String?,
    val contactEmail: String?,
    val phone: String?,
    val produces: String?,
)

data class ManufacturerSummary(
    val id: UUID,
    val manufacturerCode: String,
    val name: String,
    val country: String?,
    val contactEmail: String?,
    val phone: String?,
    val produces: String?,
    val status: String,
)

// ---- Factory Setup: Units of Measure ----

data class UnitOfMeasureRequest(
    @field:NotBlank val unitCode: String,
    @field:NotBlank val unitName: String,
    val symbol: String?,
)

data class UnitOfMeasureSummary(
    val id: UUID,
    val unitCode: String,
    val unitName: String,
    val symbol: String?,
)

// ---- Factory Setup: Categories ----

data class CategoryRegistrationRequest(
    @field:NotBlank val name: String,
    val type: String = "FINISHED_PRODUCT", // FINISHED_PRODUCT, RAW_MATERIAL, PACKAGING
    val description: String?,
)

data class CategorySummary(
    val id: UUID,
    val name: String,
    val type: String,
    val description: String?,
)
