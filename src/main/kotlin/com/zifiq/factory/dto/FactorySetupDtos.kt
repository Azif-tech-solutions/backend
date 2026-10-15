package com.zifiq.factory.dto

import com.zifiq.platform.directory.UserRef
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

// ---- Factory Setup: Factories ----

data class FactoryRequest(
    @field:Size(max = 40) val code: String?,
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:Size(max = 300) val address: String?,
    @field:Size(max = 80) val state: String?,
    @field:Size(max = 40) val phone: String?,
    @field:jakarta.validation.constraints.DecimalMin("0") @field:jakarta.validation.constraints.DecimalMax("100") val vatRate: BigDecimal?,
    val vatEnabled: Boolean?,
    @field:Size(max = 60) val vatRegistrationNumber: String?,
    /**
     * Optional on create (default ACTIVE) and on update (absent = unchanged). Without this field a
     * client saving the whole edit form with `"status": "SUSPENDED"` got a 200 while Jackson
     * silently dropped the unknown property, so the factory stayed ACTIVE.
     */
    @field:Pattern(regexp = "ACTIVE|PENDING|SUSPENDED") val status: String? = null,
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
    val manager: UserRef?,
    val vatRate: BigDecimal,
    val vatEnabled: Boolean,
    val vatRegistrationNumber: String?,
    val status: String,
)

// ---- Factory Setup: Suppliers ----

data class SupplierRegistrationRequest(
    @field:Size(max = 40) val supplierCode: String?,
    @field:NotBlank @field:Size(max = 160) val companyName: String,
    @field:NotBlank @field:Email @field:Size(max = 320) val contactEmail: String,
    @field:Size(max = 40) val phone: String?,
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
    @field:Size(max = 40) val manufacturerCode: String?,
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:Size(max = 80) val country: String?,
    @field:Email @field:Size(max = 320) val contactEmail: String?,
    @field:Size(max = 40) val phone: String?,
    @field:Size(max = 300) val produces: String?,
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
    @field:NotBlank @field:Size(max = 20) val unitCode: String,
    @field:NotBlank @field:Size(max = 80) val unitName: String,
    @field:Size(max = 10) val symbol: String?,
)

data class UnitOfMeasureSummary(
    val id: UUID,
    val unitCode: String,
    val unitName: String,
    val symbol: String?,
)

// ---- Factory Setup: Categories ----

data class CategoryRegistrationRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:Pattern(regexp = "(?i)FINISHED_PRODUCT|RAW_MATERIAL|PACKAGING", message = "must be FINISHED_PRODUCT, RAW_MATERIAL or PACKAGING")
    val type: String = "FINISHED_PRODUCT",
    @field:Size(max = 300) val description: String?,
)

data class CategorySummary(
    val id: UUID,
    val name: String,
    val type: String,
    val description: String?,
)
