package com.zifiq.factory.service

import com.zifiq.factory.dto.*
import com.zifiq.factory.model.CategoriesTable
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.factory.model.ManufacturersTable
import com.zifiq.factory.model.SuppliersTable
import com.zifiq.factory.model.UnitsOfMeasureTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

private fun rowToFactorySummary(row: ResultRow) = FactorySummary(
    id = row[LocationsTable.id].value,
    code = row[LocationsTable.code],
    name = row[LocationsTable.name],
    address = row[LocationsTable.address],
    state = row[LocationsTable.state],
    phone = row[LocationsTable.phone],
    managerUserId = row[LocationsTable.managerUserId]?.value,
    vatRate = row[LocationsTable.vatRate],
    vatEnabled = row[LocationsTable.vatEnabled],
    vatRegistrationNumber = row[LocationsTable.vatRegistrationNumber],
    status = row[LocationsTable.status],
)

/**
 * Factory & Production > Factory Setup (docs/02-factory.md): Factories, Suppliers, Manufacturers,
 * Units of Measure, Categories — previously (incorrectly) owned by `com.zifiq.settings`; moved
 * here to match the proposed navigation architecture in sketch/index.html, which keeps Factory
 * Setup inside Factory & Production, not under a generic cross-module Settings.
 */
@Service
class FactorySetupService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val documentSequenceService: DocumentSequenceService,
) {
    // ---- Factories ----

    fun getFactories(): List<FactorySummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        LocationsTable.selectAll()
            .andWhere { LocationsTable.tenantId eq tenantId }
            .andWhere { LocationsTable.type eq "FACTORY" }
            .orderBy(LocationsTable.name)
            .map(::rowToFactorySummary)
    }

    fun createFactory(request: FactoryRequest): FactorySummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val code = request.code?.trim()?.takeIf(String::isNotEmpty) ?: documentSequenceService.next("FAC")

        val id = LocationsTable.insertAndGetId {
            it[LocationsTable.tenantId] = actor.tenantId
            it[LocationsTable.type] = "FACTORY"
            it[LocationsTable.code] = code
            it[LocationsTable.name] = request.name.trim()
            it[LocationsTable.address] = request.address?.trim()?.takeIf(String::isNotEmpty)
            it[LocationsTable.state] = request.state?.trim()?.takeIf(String::isNotEmpty)
            it[LocationsTable.phone] = request.phone?.trim()?.takeIf(String::isNotEmpty)
            it[LocationsTable.vatRate] = request.vatRate ?: java.math.BigDecimal("7.50")
            it[LocationsTable.vatEnabled] = request.vatEnabled ?: true
            it[LocationsTable.vatRegistrationNumber] = request.vatRegistrationNumber?.trim()?.takeIf(String::isNotEmpty)
            it[LocationsTable.status] = request.status ?: "ACTIVE"
            it[LocationsTable.createdByUserId] = actor.userId
            it[LocationsTable.createdAt] = Instant.now()
        }.value

        LocationsTable.selectAll().andWhere { LocationsTable.id eq id }.single().let(::rowToFactorySummary)
    }

    fun updateFactory(id: UUID, request: FactoryRequest): FactorySummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val updated = LocationsTable.update({
            (LocationsTable.id eq id) and (LocationsTable.tenantId eq tenantId) and (LocationsTable.type eq "FACTORY")
        }) {
            it[LocationsTable.name] = request.name.trim()
            it[LocationsTable.address] = request.address?.trim()?.takeIf(String::isNotEmpty)
            it[LocationsTable.state] = request.state?.trim()?.takeIf(String::isNotEmpty)
            it[LocationsTable.phone] = request.phone?.trim()?.takeIf(String::isNotEmpty)
            request.vatRate?.let { rate -> it[LocationsTable.vatRate] = rate }
            request.vatEnabled?.let { enabled -> it[LocationsTable.vatEnabled] = enabled }
            it[LocationsTable.vatRegistrationNumber] = request.vatRegistrationNumber?.trim()?.takeIf(String::isNotEmpty)
            request.status?.let { status -> it[LocationsTable.status] = status }
        }
        if (updated == 0) throw ApiErrors.factoryNotFound()

        LocationsTable.selectAll().andWhere { LocationsTable.id eq id }.single().let(::rowToFactorySummary)
    }

    fun updateFactoryStatus(id: UUID, status: String): FactorySummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val updated = LocationsTable.update({
            (LocationsTable.id eq id) and (LocationsTable.tenantId eq tenantId) and (LocationsTable.type eq "FACTORY")
        }) {
            it[LocationsTable.status] = status
        }
        if (updated == 0) throw ApiErrors.factoryNotFound()

        LocationsTable.selectAll().andWhere { LocationsTable.id eq id }.single().let(::rowToFactorySummary)
    }

    // ---- Suppliers ----

    fun getSuppliers(): List<SupplierSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        SuppliersTable.selectAll().andWhere { SuppliersTable.tenantId eq tenantId }
            .orderBy(SuppliersTable.companyName)
            .map {
                SupplierSummary(
                    id = it[SuppliersTable.id].value,
                    supplierCode = it[SuppliersTable.code],
                    companyName = it[SuppliersTable.companyName],
                    contactEmail = it[SuppliersTable.contactEmail] ?: "",
                    phone = it[SuppliersTable.phone],
                )
            }
    }

    fun registerSupplier(request: SupplierRegistrationRequest): SupplierSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val code = request.supplierCode?.trim()?.takeIf(String::isNotEmpty) ?: documentSequenceService.next("SUP")
        val id = SuppliersTable.insertAndGetId {
            it[SuppliersTable.tenantId] = actor.tenantId
            it[SuppliersTable.code] = code
            it[SuppliersTable.companyName] = request.companyName.trim()
            it[SuppliersTable.contactEmail] = request.contactEmail.trim()
            it[SuppliersTable.phone] = request.phone?.trim()?.takeIf(String::isNotEmpty)
            it[SuppliersTable.status] = "ACTIVE"
            it[SuppliersTable.createdAt] = Instant.now()
        }.value
        SupplierSummary(id, code, request.companyName.trim(), request.contactEmail.trim(), request.phone)
    }

    // ---- Manufacturers ----

    fun getManufacturers(): List<ManufacturerSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        ManufacturersTable.selectAll().andWhere { ManufacturersTable.tenantId eq tenantId }
            .orderBy(ManufacturersTable.name)
            .map {
                ManufacturerSummary(
                    id = it[ManufacturersTable.id].value,
                    manufacturerCode = it[ManufacturersTable.code],
                    name = it[ManufacturersTable.name],
                    country = it[ManufacturersTable.country],
                    contactEmail = it[ManufacturersTable.contactEmail],
                    phone = it[ManufacturersTable.phone],
                    produces = it[ManufacturersTable.produces],
                    status = it[ManufacturersTable.status],
                )
            }
    }

    fun registerManufacturer(request: ManufacturerRequest): ManufacturerSummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val code = request.manufacturerCode?.trim()?.takeIf(String::isNotEmpty) ?: documentSequenceService.next("MFR")
        val id = ManufacturersTable.insertAndGetId {
            it[ManufacturersTable.tenantId] = actor.tenantId
            it[ManufacturersTable.code] = code
            it[ManufacturersTable.name] = request.name.trim()
            it[ManufacturersTable.country] = request.country?.trim()?.takeIf(String::isNotEmpty)
            it[ManufacturersTable.contactEmail] = request.contactEmail?.trim()?.takeIf(String::isNotEmpty)
            it[ManufacturersTable.phone] = request.phone?.trim()?.takeIf(String::isNotEmpty)
            it[ManufacturersTable.produces] = request.produces?.trim()?.takeIf(String::isNotEmpty)
            it[ManufacturersTable.status] = "ACTIVE"
            it[ManufacturersTable.createdAt] = Instant.now()
        }.value
        ManufacturerSummary(id, code, request.name.trim(), request.country, request.contactEmail, request.phone, request.produces, "ACTIVE")
    }

    // ---- Units of Measure ----

    fun getUnitsOfMeasure(): List<UnitOfMeasureSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        UnitsOfMeasureTable.selectAll().andWhere { UnitsOfMeasureTable.tenantId eq tenantId }
            .orderBy(UnitsOfMeasureTable.name)
            .map {
                UnitOfMeasureSummary(
                    id = it[UnitsOfMeasureTable.id].value,
                    unitCode = it[UnitsOfMeasureTable.code],
                    unitName = it[UnitsOfMeasureTable.name],
                    symbol = it[UnitsOfMeasureTable.symbol],
                )
            }
    }

    fun addUnitOfMeasure(request: UnitOfMeasureRequest): UnitOfMeasureSummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val id = UnitsOfMeasureTable.insertAndGetId {
            it[UnitsOfMeasureTable.tenantId] = tenantId
            it[UnitsOfMeasureTable.code] = request.unitCode.trim()
            it[UnitsOfMeasureTable.name] = request.unitName.trim()
            it[UnitsOfMeasureTable.symbol] = request.symbol?.trim()?.takeIf(String::isNotEmpty)
            it[UnitsOfMeasureTable.createdAt] = Instant.now()
        }.value
        UnitOfMeasureSummary(id, request.unitCode.trim(), request.unitName.trim(), request.symbol)
    }

    // ---- Categories ----

    fun getCategories(): List<CategorySummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        CategoriesTable.selectAll().andWhere { CategoriesTable.tenantId eq tenantId }
            .orderBy(CategoriesTable.name)
            .map {
                CategorySummary(
                    id = it[CategoriesTable.id].value,
                    name = it[CategoriesTable.name],
                    type = it[CategoriesTable.type],
                    description = it[CategoriesTable.description],
                )
            }
    }

    fun addCategory(request: CategoryRegistrationRequest): CategorySummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val id = CategoriesTable.insertAndGetId {
            it[CategoriesTable.tenantId] = tenantId
            it[CategoriesTable.name] = request.name.trim()
            it[CategoriesTable.type] = request.type
            it[CategoriesTable.description] = request.description?.trim()?.takeIf(String::isNotEmpty)
            it[CategoriesTable.createdAt] = Instant.now()
        }.value
        CategorySummary(id, request.name.trim(), request.type, request.description)
    }
}
