package com.zifiq.factory.service

import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.directory.UserRef
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
import org.jetbrains.exposed.sql.lowerCase
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
    manager = UserRef.of(row[LocationsTable.managerUserId]?.value),
    vatRate = row[LocationsTable.vatRate],
    vatEnabled = row[LocationsTable.vatEnabled],
    vatRegistrationNumber = row[LocationsTable.vatRegistrationNumber],
    status = row[LocationsTable.status],
)

/**
 * Factory & Production > Factory Setup (docs/implemented/02-factory.md): Factories, Suppliers, Manufacturers,
 * Units of Measure, Categories — previously (incorrectly) owned by `com.zifiq.settings`; moved
 * here to match the proposed navigation architecture in sketch/index.html, which keeps Factory
 * Setup inside Factory & Production, not under a generic cross-module Settings.
 */
@Service
class FactorySetupService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val documentSequenceService: DocumentSequenceService,
    private val locationAccess: LocationAccessService,
) : com.zifiq.factory.spi.FactoryVocabulary {
    // ---- Product vocabulary SPI for Audit's Error Handler (docs/10 §4.8) ----

    override fun units(): List<com.zifiq.factory.spi.VocabularyTerm> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        UnitsOfMeasureTable.selectAll().where { UnitsOfMeasureTable.tenantId eq tenantId }
            .map { com.zifiq.factory.spi.VocabularyTerm(it[UnitsOfMeasureTable.id].value, it[UnitsOfMeasureTable.code], it[UnitsOfMeasureTable.name]) }
    }

    override fun categories(): List<com.zifiq.factory.spi.VocabularyTerm> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        CategoriesTable.selectAll().where { CategoriesTable.tenantId eq tenantId }
            .map { com.zifiq.factory.spi.VocabularyTerm(it[CategoriesTable.id].value, null, it[CategoriesTable.name]) }
    }

    override fun renameUnit(id: java.util.UUID, name: String) = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        UnitsOfMeasureTable.update({ (UnitsOfMeasureTable.id eq id) and (UnitsOfMeasureTable.tenantId eq tenantId) }) { it[UnitsOfMeasureTable.name] = name.trim() }
        Unit
    }

    override fun renameCategory(id: java.util.UUID, name: String) = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val trimmed = name.trim()
        val clash = CategoriesTable.selectAll()
            .where { (CategoriesTable.tenantId eq tenantId) and (CategoriesTable.name.lowerCase() eq trimmed.lowercase()) and (CategoriesTable.id neq id) }.empty().not()
        if (clash) throw ApiErrors.duplicateName(trimmed)
        CategoriesTable.update({ (CategoriesTable.id eq id) and (CategoriesTable.tenantId eq tenantId) }) { it[CategoriesTable.name] = trimmed }
        Unit
    }
    // ---- Factories ----

    /** A user assigned to one location sees only their own factory (none, at a showroom). */
    fun getFactories(): List<FactorySummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val query = LocationsTable.selectAll()
            .andWhere { LocationsTable.tenantId eq tenantId }
            .andWhere { LocationsTable.type eq "FACTORY" }
        locationAccess.allowedLocationIds()?.let { allowed -> query.andWhere { LocationsTable.id inList allowed } }
        query.orderBy(LocationsTable.name).map(::rowToFactorySummary)
    }

    fun createFactory(request: FactoryRequest): FactorySummary = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        // Location codes are unique across factories, showrooms and areas.
        val code = uniqueCode(request.code, "FAC") { candidate ->
            LocationsTable.selectAll().where { (LocationsTable.tenantId eq actor.tenantId) and (LocationsTable.code eq candidate) }.empty().not()
        }

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
        visibleFactory(id)
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
        visibleFactory(id)
        val updated = LocationsTable.update({
            (LocationsTable.id eq id) and (LocationsTable.tenantId eq tenantId) and (LocationsTable.type eq "FACTORY")
        }) {
            it[LocationsTable.status] = status
        }
        if (updated == 0) throw ApiErrors.factoryNotFound()

        LocationsTable.selectAll().andWhere { LocationsTable.id eq id }.single().let(::rowToFactorySummary)
    }

    /** Another location's factory is "not found" for a user assigned elsewhere: its existence isn't revealed. */
    private fun visibleFactory(id: UUID) {
        locationAccess.allowedLocationIds()?.let { if (id !in it) throw ApiErrors.factoryNotFound() }
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
        val code = uniqueCode(request.supplierCode, "SUP") { candidate ->
            SuppliersTable.selectAll().where { (SuppliersTable.tenantId eq actor.tenantId) and (SuppliersTable.code eq candidate) }.empty().not()
        }
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
        val code = uniqueCode(request.manufacturerCode, "MFR") { candidate ->
            ManufacturersTable.selectAll().where { (ManufacturersTable.tenantId eq actor.tenantId) and (ManufacturersTable.code eq candidate) }.empty().not()
        }
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
        val unitCode = request.unitCode.trim()
        val taken = UnitsOfMeasureTable.selectAll()
            .where { (UnitsOfMeasureTable.tenantId eq tenantId) and (UnitsOfMeasureTable.code.lowerCase() eq unitCode.lowercase()) }.empty().not()
        if (taken) throw ApiErrors.duplicateCode(unitCode)
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
        val name = request.name.trim()
        val type = request.type.trim().uppercase()
        // Names are unique per company regardless of case ("Beverages" and "beverages" are the same group).
        val taken = CategoriesTable.selectAll()
            .where { (CategoriesTable.tenantId eq tenantId) and (CategoriesTable.name.lowerCase() eq name.lowercase()) }.empty().not()
        if (taken) throw ApiErrors.duplicateName(name)
        val description = request.description?.trim()?.takeIf(String::isNotEmpty)
        val id = CategoriesTable.insertAndGetId {
            it[CategoriesTable.tenantId] = tenantId
            it[CategoriesTable.name] = name
            it[CategoriesTable.type] = type
            it[CategoriesTable.description] = description
            it[CategoriesTable.createdAt] = Instant.now()
        }.value
        CategorySummary(id, name, type, description)
    }

    /**
     * A code typed by the user must be free (409 DUPLICATE_CODE); a generated one skips any code a
     * user already typed by hand (e.g. "FAC-3"), so the sequence never collides.
     */
    private fun uniqueCode(requested: String?, prefix: String, taken: (String) -> Boolean): String {
        val typed = requested?.trim()?.takeIf(String::isNotEmpty)
        if (typed != null) {
            if (taken(typed)) throw ApiErrors.duplicateCode(typed)
            return typed
        }
        return generateSequence { documentSequenceService.next(prefix) }.first { !taken(it) }
    }
}
