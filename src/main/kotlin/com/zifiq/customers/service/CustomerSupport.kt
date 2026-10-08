package com.zifiq.customers.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.search.RawOp
import com.zifiq.common.tax.NigeriaLgasTable
import com.zifiq.common.tax.NigeriaStatesTable
import com.zifiq.customers.dto.CorrespondentBrief
import com.zifiq.customers.dto.CustomerDetail
import com.zifiq.customers.dto.CustomerSummary
import com.zifiq.customers.dto.DealerCategoryBrief
import com.zifiq.customers.dto.LocationRef
import com.zifiq.customers.model.CorrespondentsTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.model.DealerCategoriesTable
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import java.util.UUID
import org.jetbrains.exposed.sql.Alias
import org.jetbrains.exposed.sql.ColumnSet
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/**
 * Shared reads, mappers and validations for the Customers services. Everything runs in the
 * caller's transaction.
 */
@Component
class CustomerSupport(
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val tenantContext: TenantContext,
) {
    private val lgas: Alias<NigeriaLgasTable> = NigeriaLgasTable.alias("lga")

    /** customers ⟕ locations ⟕ correspondents ⟕ dealer_categories ⟕ states ⟕ LGAs. */
    fun joined(): ColumnSet = CustomersTable
        .join(LocationsTable, JoinType.LEFT, additionalConstraint = { RawOp { +CustomersTable.locationId; +" = "; +LocationsTable.id } })
        .join(CorrespondentsTable, JoinType.LEFT, additionalConstraint = { RawOp { +CustomersTable.correspondentId; +" = "; +CorrespondentsTable.id } })
        .join(DealerCategoriesTable, JoinType.LEFT, additionalConstraint = { RawOp { +CustomersTable.dealerCategoryId; +" = "; +DealerCategoriesTable.id } })
        .join(NigeriaStatesTable, JoinType.LEFT, additionalConstraint = { RawOp { +CustomersTable.stateCode; +" = "; +NigeriaStatesTable.code } })
        .join(lgas, JoinType.LEFT, additionalConstraint = { RawOp { +CustomersTable.lgaCode; +" = "; +lgas[NigeriaLgasTable.code] } })

    fun query(condition: Op<Boolean>): Query = joined().selectAll().where(condition)

    fun tenantId(): UUID = tenantContext.requireTenantId()
    fun userId(): UUID = currentActorProvider.current().userId
    fun can(permission: String): Boolean = permissionAuthorizer.hasPermission(userId(), permission)
    fun require(permission: String) {
        if (!can(permission)) throw ApiErrors.accessDenied()
    }

    fun row(id: UUID, forUpdate: Boolean = false): ResultRow {
        val query = query((CustomersTable.id eq id) and (CustomersTable.tenantId eq tenantId()))
        // Lock only the customer row: Postgres refuses FOR UPDATE on the nullable side of the outer joins.
        if (forUpdate) query.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(null, CustomersTable))
        return query.firstOrNull() ?: throw ApiErrors.customerNotFound()
    }

    // ---- Validations shared by register / update / correspondents ----

    fun validateRegion(stateCode: String?, lgaCode: String?) {
        if (stateCode != null && NigeriaStatesTable.selectAll().where { NigeriaStatesTable.code eq stateCode }.empty()) {
            throw ApiErrors.invalidStateCode()
        }
        if (lgaCode != null) {
            val lga = NigeriaLgasTable.selectAll().where { NigeriaLgasTable.code eq lgaCode }.firstOrNull()
                ?: throw ApiErrors.invalidLgaCode()
            if (stateCode == null || lga[NigeriaLgasTable.stateCode] != stateCode) throw ApiErrors.invalidLgaCode()
        }
    }

    /** A customer's home (or a correspondent's reporting) location: an ACTIVE showroom or factory of this tenant. */
    fun validateLocation(locationId: UUID) {
        val location = LocationsTable.selectAll()
            .where { (LocationsTable.id eq locationId) and (LocationsTable.tenantId eq tenantId()) }
            .firstOrNull() ?: throw ApiErrors.invalidLocation("Location not found")
        if (location[LocationsTable.type] !in setOf("SHOWROOM", "FACTORY")) throw ApiErrors.invalidLocation("Customers belong to a showroom or factory")
        if (location[LocationsTable.status] != "ACTIVE") throw ApiErrors.invalidLocation("This location is not active")
    }

    fun requireAssignedCorrespondent(correspondentId: UUID) {
        val row = CorrespondentsTable.selectAll()
            .where { (CorrespondentsTable.id eq correspondentId) and (CorrespondentsTable.tenantId eq tenantId()) }
            .firstOrNull() ?: throw ApiErrors.correspondentNotFound()
        if (row[CorrespondentsTable.status] != "ASSIGNED") throw ApiErrors.correspondentNotAssigned()
    }

    /** A self-service login: an ACTIVE member of this tenant. */
    fun validateMember(userId: UUID) {
        val member = TenantMembershipsTable.selectAll()
            .where { (TenantMembershipsTable.tenantId eq tenantId()) and (TenantMembershipsTable.userId eq userId) }
            .firstOrNull()
        if (member == null || member[TenantMembershipsTable.status] != "ACTIVE") throw ApiErrors.invalidUser("The user is not an active member of this company")
    }

    fun activeCategory(categoryId: UUID): ResultRow =
        DealerCategoriesTable.selectAll()
            .where {
                (DealerCategoriesTable.id eq categoryId) and (DealerCategoriesTable.tenantId eq tenantId()) and
                    (DealerCategoriesTable.status eq "ACTIVE")
            }
            .firstOrNull() ?: throw ApiErrors.dealerCategoryNotFound()

    // ---- Mappers ----

    fun toRef(row: ResultRow) = CustomerRef(
        id = row[CustomersTable.id].value,
        code = row[CustomersTable.code],
        type = CustomerType.valueOf(row[CustomersTable.type]),
        isTemporary = row[CustomersTable.isTemporary],
        name = row[CustomersTable.name],
        contactPerson = row[CustomersTable.contactPerson],
        phone = row[CustomersTable.phone],
        email = row[CustomersTable.email],
        tin = row[CustomersTable.tin],
        businessDescription = row[CustomersTable.businessDescription],
        streetAddress = row[CustomersTable.streetAddress],
        city = row[CustomersTable.city],
        stateCode = row[CustomersTable.stateCode],
        lgaCode = row[CustomersTable.lgaCode],
        countryCode = row[CustomersTable.countryCode],
        locationId = row[CustomersTable.locationId],
        correspondentId = row[CustomersTable.correspondentId],
        correspondentAssigned = row.getOrNull(CorrespondentsTable.status) == "ASSIGNED",
        dealerCategoryId = row[CustomersTable.dealerCategoryId],
        dealerCategoryName = row.getOrNull(DealerCategoriesTable.name),
        dealerDiscountPct = row.getOrNull(DealerCategoriesTable.discountPct),
        creditLimit = row[CustomersTable.creditLimit],
        walletBalance = row[CustomersTable.walletBalance],
        depositBalance = row[CustomersTable.depositBalance],
        status = CustomerStatus.valueOf(row[CustomersTable.status]),
    )

    fun toDetail(row: ResultRow) = CustomerDetail(
        id = row[CustomersTable.id].value,
        code = row[CustomersTable.code],
        type = CustomerType.valueOf(row[CustomersTable.type]),
        isTemporary = row[CustomersTable.isTemporary],
        name = row[CustomersTable.name],
        contactPerson = row[CustomersTable.contactPerson],
        phone = row[CustomersTable.phone],
        alternatePhone = row[CustomersTable.alternatePhone],
        email = row[CustomersTable.email],
        tin = row[CustomersTable.tin],
        businessDescription = row[CustomersTable.businessDescription],
        streetAddress = row[CustomersTable.streetAddress],
        city = row[CustomersTable.city],
        stateCode = row[CustomersTable.stateCode],
        stateName = row.getOrNull(NigeriaStatesTable.name),
        lgaCode = row[CustomersTable.lgaCode],
        lgaName = row.getOrNull(lgas[NigeriaLgasTable.name]),
        countryCode = row[CustomersTable.countryCode],
        location = row[CustomersTable.locationId]?.let { LocationRef(it, row[LocationsTable.code], row[LocationsTable.name]) },
        correspondent = row[CustomersTable.correspondentId]?.let {
            CorrespondentBrief(it, row[CorrespondentsTable.code], row[CorrespondentsTable.name], row[CorrespondentsTable.status])
        },
        dealerCategory = row[CustomersTable.dealerCategoryId]?.let {
            DealerCategoryBrief(it, row[DealerCategoriesTable.name], row[DealerCategoriesTable.discountPct])
        },
        creditLimit = row[CustomersTable.creditLimit],
        walletBalance = row[CustomersTable.walletBalance],
        depositBalance = row[CustomersTable.depositBalance],
        lifetimeValue = row[CustomersTable.lifetimeValue],
        lastPurchaseAt = row[CustomersTable.lastPurchaseAt],
        customerSince = row[CustomersTable.customerSince],
        status = CustomerStatus.valueOf(row[CustomersTable.status]),
        statusReason = row[CustomersTable.statusReason],
        userId = row[CustomersTable.userId],
        customAttributes = CustomersTable.attributes(row[CustomersTable.customAttributes]),
        createdAt = row[CustomersTable.createdAt],
    )

    fun toSummary(row: ResultRow) = CustomerSummary(
        id = row[CustomersTable.id].value,
        code = row[CustomersTable.code],
        type = CustomerType.valueOf(row[CustomersTable.type]),
        isTemporary = row[CustomersTable.isTemporary],
        name = row[CustomersTable.name],
        contactPerson = row[CustomersTable.contactPerson],
        phone = row[CustomersTable.phone],
        stateCode = row[CustomersTable.stateCode],
        stateName = row.getOrNull(NigeriaStatesTable.name),
        locationId = row[CustomersTable.locationId],
        locationName = row.getOrNull(LocationsTable.name),
        correspondentId = row[CustomersTable.correspondentId],
        correspondentName = row.getOrNull(CorrespondentsTable.name),
        dealerCategoryName = row.getOrNull(DealerCategoriesTable.name),
        creditLimit = row[CustomersTable.creditLimit],
        walletBalance = row[CustomersTable.walletBalance],
        depositBalance = row[CustomersTable.depositBalance],
        lifetimeValue = row[CustomersTable.lifetimeValue],
        lastPurchaseAt = row[CustomersTable.lastPurchaseAt],
        customerSince = row[CustomersTable.customerSince],
        status = CustomerStatus.valueOf(row[CustomersTable.status]),
    )
}
