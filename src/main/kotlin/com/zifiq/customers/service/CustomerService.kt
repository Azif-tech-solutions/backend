package com.zifiq.customers.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.json.Json
import com.zifiq.common.phone.PhoneNumbers
import com.zifiq.customers.dto.CustomerDetail
import com.zifiq.customers.dto.CustomerRequest
import com.zifiq.customers.dto.CustomerStatusRequest
import com.zifiq.customers.dto.CustomerUpdateRequest
import com.zifiq.customers.model.CorrespondentsTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.platform.customfields.CustomFieldEntity
import com.zifiq.platform.customfields.CustomFieldValidator
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/** Register, edit, status and self-service for customers and dealers — docs/04 §4.1, §4.3, §4.7, §4.11. */
@Service
class CustomerService(
    private val support: CustomerSupport,
    private val tx: TenantScopedTransaction,
    private val locationAccess: LocationAccessService,
    private val customFields: CustomFieldValidator,
    private val sequences: DocumentSequenceService,
    private val domainEvents: DomainEvents,
    private val notify: Notify,
) {
    /** The validated, normalised field set shared by register, convert and update. */
    private data class Fields(
        val type: CustomerType,
        val name: String,
        val contactPerson: String?,
        val phone: String?,
        val phoneE164: String?,
        val alternatePhone: String?,
        val email: String?,
        val tin: String?,
        val businessDescription: String?,
        val streetAddress: String?,
        val city: String?,
        val stateCode: String?,
        val lgaCode: String?,
        val locationId: UUID?,
        val correspondentId: UUID?,
        val dealerCategoryId: UUID?,
        val creditLimit: BigDecimal,
        val userId: UUID?,
        val customAttributes: Map<String, Any?>,
    )

    fun register(request: CustomerRequest): CustomerDetail = tx.execute {
        val type = request.type!!
        val fields = validate(
            type = type,
            raw = request.asUpdate(),
            dealerCategoryId = request.dealerCategoryId,
            creditLimit = request.creditLimit,
            existingId = null,
            previousAttributes = emptyMap(),
        )
        val since = request.customerSince ?: LocalDate.now()
        if (since.isAfter(LocalDate.now())) throw ApiErrors.validationFailed(mapOf("customerSince" to "cannot be in the future"))

        val code = sequences.next(if (type == CustomerType.AUTHORISED_DEALER) "DLR" else "CU")
        val now = Instant.now()
        val id = CustomersTable.insertAndGetId {
            it[tenantId] = support.tenantId()
            it[CustomersTable.code] = code
            it[isTemporary] = false
            it[customerSince] = since
            it[status] = CustomerStatus.ACTIVE.name
            it[countryCode] = "NG"
            it[createdByUserId] = support.userId()
            it[createdAt] = now
            it[updatedAt] = now
            write(it, fields)
        }.value

        domainEvents.record(
            type = "customers.customer.registered",
            aggregateType = "customer",
            aggregateId = id,
            aggregateRef = code,
            summary = "${type.label} $code registered — ${fields.name}",
            payload = mapOf("type" to type.name),
            locationId = fields.locationId,
        )
        support.toDetail(support.row(id))
    }

    fun get(id: UUID): CustomerDetail = tx.execute {
        val row = support.row(id)
        assertVisible(row)
        support.toDetail(row)
    }

    fun update(id: UUID, request: CustomerUpdateRequest): CustomerDetail = tx.execute {
        val row = support.row(id, forUpdate = true)
        assertVisible(row)
        val current = support.toDetail(row)
        if (current.status == CustomerStatus.REMOVED) throw ApiErrors.customerNotActive(current.status.name)

        val creditLimitChanged = request.creditLimit != null && request.creditLimit.compareTo(current.creditLimit) != 0
        if (creditLimitChanged) support.require("customers.dealers.manage")
        val fields = validate(
            type = current.type,
            raw = request,
            dealerCategoryId = current.dealerCategory?.id,
            creditLimit = request.creditLimit ?: current.creditLimit,
            existingId = id,
            previousAttributes = current.customAttributes,
            requirePhone = !current.isTemporary,
            grantsCredit = false,
        )
        val changed = changedFields(current, fields)

        CustomersTable.update({ CustomersTable.id eq id }) {
            write(it, fields)
            it[updatedAt] = Instant.now()
        }

        if (changed.isNotEmpty()) {
            domainEvents.record(
                type = "customers.customer.updated",
                aggregateType = "customer",
                aggregateId = id,
                aggregateRef = current.code,
                summary = "Customer ${current.code} updated — ${changed.joinToString(", ")}",
                payload = mapOf("changed" to changed),
                locationId = fields.locationId,
            )
        }
        if (creditLimitChanged) {
            val from = current.creditLimit
            val to = fields.creditLimit
            domainEvents.record(
                type = "customers.customer.credit-limit-changed",
                aggregateType = "customer",
                aggregateId = id,
                aggregateRef = current.code,
                summary = "Credit limit for ${current.code} changed ₦${from.toPlainString()} → ₦${to.toPlainString()}",
                payload = mapOf("from" to from, "to" to to),
                locationId = fields.locationId,
            )
            notify.holdersOf(
                permission = "customers.dealers.manage",
                locationId = null,
                category = NotificationCategory.INFO,
                title = "Credit limit changed — ${current.code}",
                body = "Credit limit for ${current.name} (${current.code}) changed from ₦${from.toPlainString()} to ₦${to.toPlainString()}",
                linkType = "customer",
                linkRef = id.toString(),
                exclude = support.userId(),
            )
        }
        support.toDetail(support.row(id))
    }

    fun changeStatus(id: UUID, request: CustomerStatusRequest): CustomerDetail = tx.execute {
        val row = support.row(id, forUpdate = true)
        assertVisible(row)
        val from = CustomerStatus.valueOf(row[CustomersTable.status])
        val to = request.status!!
        // REMOVED is set only by the temporary-wallet rule, and is final.
        if (to == CustomerStatus.REMOVED || from == CustomerStatus.REMOVED || from == to) {
            throw ApiErrors.invalidStatusTransition(from.name, to.name)
        }
        support.require(if (from == CustomerStatus.SUSPENDED || to == CustomerStatus.SUSPENDED) "customers.dealers.manage" else "customers.manage")

        CustomersTable.update({ CustomersTable.id eq id }) {
            it[status] = to.name
            it[statusReason] = request.reason.trim()
            it[updatedAt] = Instant.now()
        }
        val code = row[CustomersTable.code]
        domainEvents.record(
            type = "customers.customer.status-changed",
            aggregateType = "customer",
            aggregateId = id,
            aggregateRef = code,
            summary = "Customer $code ${from.name.lowercase()} → ${to.name.lowercase()}: ${request.reason.trim()}",
            payload = mapOf("from" to from.name, "to" to to.name, "reason" to request.reason.trim()),
            locationId = row[CustomersTable.locationId],
        )
        support.toDetail(support.row(id))
    }

    /** §4.7: a temporary walk-in becomes a registered customer of any type; code and wallet are kept. */
    fun convertTemporary(id: UUID, request: CustomerRequest): CustomerDetail = tx.execute {
        val row = support.row(id, forUpdate = true)
        if (!row[CustomersTable.isTemporary] || row[CustomersTable.status] != CustomerStatus.ACTIVE.name) {
            throw ApiErrors.notConvertible(if (row[CustomersTable.isTemporary]) row[CustomersTable.status] else "already registered")
        }
        val type = request.type!!
        if (type == CustomerType.AUTHORISED_DEALER) support.require("customers.dealers.manage")
        // Deposits belong to Special Customers only; a temporary record never holds one, so only the wallet carries over.
        val fields = validate(
            type = type,
            raw = request.asUpdate(),
            dealerCategoryId = request.dealerCategoryId,
            creditLimit = request.creditLimit,
            existingId = id,
            previousAttributes = CustomersTable.attributes(row[CustomersTable.customAttributes]),
        )
        CustomersTable.update({ CustomersTable.id eq id }) {
            it[isTemporary] = false
            request.customerSince?.let { since ->
                if (since.isAfter(LocalDate.now())) throw ApiErrors.validationFailed(mapOf("customerSince" to "cannot be in the future"))
                it[customerSince] = since
            }
            it[updatedAt] = Instant.now()
            write(it, fields)
        }
        val code = row[CustomersTable.code]
        domainEvents.record(
            type = "customers.customer.registered",
            aggregateType = "customer",
            aggregateId = id,
            aggregateRef = code,
            summary = "Temporary customer $code registered as ${type.label} — ${fields.name}",
            payload = mapOf("type" to type.name, "convertedFromTemporary" to true),
            locationId = fields.locationId,
        )
        support.toDetail(support.row(id))
    }

    fun me(): CustomerDetail = tx.execute { support.toDetail(myRow()) }

    /** The customer record linked to the caller's login (customers.self.read). */
    fun myId(): UUID = tx.execute { myRow()[CustomersTable.id].value }

    private fun myRow(): ResultRow =
        support.query(
            (CustomersTable.tenantId eq support.tenantId()) and (CustomersTable.userId eq support.userId()) and
                (CustomersTable.status neq CustomerStatus.REMOVED.name),
        ).firstOrNull() ?: throw ApiErrors.customerNotFound()

    /**
     * Read scope for one record: `customers.read` sees every customer homed at a location the caller
     * may access; `customers.read.own` only customers of the caller's correspondent record. Out of
     * scope looks like not found, so ids can't be probed.
     */
    fun assertVisible(row: ResultRow) {
        if (support.can("customers.read")) {
            val home = row[CustomersTable.locationId]
            if (home != null && locationAccess.resolveFilter(null)?.let { it != home } == true) throw ApiErrors.customerNotFound()
            return
        }
        val ownCorrespondent = row[CustomersTable.correspondentId]?.let { correspondentId ->
            CorrespondentsTable.selectAll()
                .where { (CorrespondentsTable.id eq correspondentId) and (CorrespondentsTable.userId eq support.userId()) }
                .empty().not()
        } ?: false
        if (!support.can("customers.read.own") || !ownCorrespondent) throw ApiErrors.customerNotFound()
    }

    // ---- Validation ----

    private fun CustomerRequest.asUpdate() = CustomerUpdateRequest(
        name = name, contactPerson = contactPerson, phone = phone, alternatePhone = alternatePhone, email = email,
        tin = tin, businessDescription = businessDescription, streetAddress = streetAddress, city = city,
        stateCode = stateCode, lgaCode = lgaCode, locationId = locationId, correspondentId = correspondentId,
        creditLimit = creditLimit, userId = userId, customAttributes = customAttributes,
    )

    private fun validate(
        type: CustomerType,
        raw: CustomerUpdateRequest,
        dealerCategoryId: UUID?,
        creditLimit: BigDecimal?,
        existingId: UUID?,
        previousAttributes: Map<String, Any?>,
        requirePhone: Boolean = true,
        grantsCredit: Boolean = true,
    ): Fields {
        val dealer = type == CustomerType.AUTHORISED_DEALER
        // Registering or editing a dealer, or granting credit, is the dealer manager's call.
        if (dealer || (grantsCredit && creditLimit != null && creditLimit.signum() > 0)) support.require("customers.dealers.manage")

        val name = raw.name.trim()
        val missing = buildMap {
            if (dealer && raw.contactPerson.isNullOrBlank()) put("contactPerson", "is required for an Authorised Dealer")
            if (dealer && raw.streetAddress.isNullOrBlank()) put("streetAddress", "is required for an Authorised Dealer")
            if (dealer && raw.stateCode.isNullOrBlank()) put("stateCode", "is required for an Authorised Dealer")
        }
        if (missing.isNotEmpty()) throw ApiErrors.validationFailed(missing)
        if (raw.phone.isNullOrBlank() && type != CustomerType.NO_CREDIT_WALKIN && requirePhone) throw ApiErrors.phoneRequired()

        val categoryId = if (dealer) {
            dealerCategoryId ?: throw ApiErrors.categoryRequiredForDealer()
        } else {
            null
        }
        val category = categoryId?.let { support.activeCategory(it) }
        val limit = when {
            type == CustomerType.NO_CREDIT_WALKIN -> {
                if (creditLimit != null && creditLimit.signum() != 0) throw ApiErrors.creditLimitNotAllowed()
                BigDecimal.ZERO
            }
            creditLimit != null -> creditLimit
            category != null -> category[com.zifiq.customers.model.DealerCategoriesTable.suggestedCreditLimit]
            else -> BigDecimal.ZERO
        }

        support.validateRegion(raw.stateCode?.trim()?.ifEmpty { null }, raw.lgaCode?.trim()?.ifEmpty { null })
        raw.locationId?.let {
            support.validateLocation(it)
            locationAccess.assertAccess(it)
        }
        raw.correspondentId?.let { support.requireAssignedCorrespondent(it) }
        raw.userId?.let { userId ->
            support.validateMember(userId)
            val taken = CustomersTable.selectAll().where {
                (CustomersTable.tenantId eq support.tenantId()) and (CustomersTable.userId eq userId) and
                    (CustomersTable.status neq CustomerStatus.REMOVED.name) and
                    (if (existingId != null) CustomersTable.id neq existingId else CustomersTable.id.isNotNull())
            }.empty().not()
            if (taken) throw ApiErrors.userAlreadyLinked()
        }
        if (dealer) ensureDealerNameFree(name, existingId)

        return Fields(
            type = type,
            name = name,
            contactPerson = raw.contactPerson?.trim()?.ifEmpty { null },
            phone = raw.phone?.trim()?.ifEmpty { null },
            phoneE164 = PhoneNumbers.normalise(raw.phone),
            alternatePhone = raw.alternatePhone?.trim()?.ifEmpty { null },
            email = raw.email?.trim()?.lowercase()?.ifEmpty { null },
            tin = raw.tin?.trim()?.ifEmpty { null },
            businessDescription = raw.businessDescription?.trim()?.ifEmpty { null },
            streetAddress = raw.streetAddress?.trim()?.ifEmpty { null },
            city = raw.city?.trim()?.ifEmpty { null },
            stateCode = raw.stateCode?.trim()?.ifEmpty { null },
            lgaCode = raw.lgaCode?.trim()?.ifEmpty { null },
            locationId = raw.locationId,
            correspondentId = raw.correspondentId,
            dealerCategoryId = categoryId,
            creditLimit = limit,
            userId = raw.userId,
            customAttributes = customFields.validate(CustomFieldEntity.CUSTOMER, raw.customAttributes, previousAttributes),
        )
    }

    /** Pre-checked so the caller gets DUPLICATE_NAME rather than a unique-index violation (customers_dealer_name_uq backs it). */
    private fun ensureDealerNameFree(name: String, existingId: UUID?) {
        val clash = CustomersTable.selectAll().where {
            (CustomersTable.tenantId eq support.tenantId()) and (CustomersTable.type eq CustomerType.AUTHORISED_DEALER.name) and
                (CustomersTable.status neq CustomerStatus.REMOVED.name) and (CustomersTable.name.lowerCase() eq name.lowercase()) and
                (if (existingId != null) CustomersTable.id neq existingId else CustomersTable.id.isNotNull())
        }.empty().not()
        if (clash) throw ApiErrors.duplicateName(name)
    }

    private fun write(statement: UpdateBuilder<*>, fields: Fields) {
        statement[CustomersTable.type] = fields.type.name
        statement[CustomersTable.name] = fields.name
        statement[CustomersTable.contactPerson] = fields.contactPerson
        statement[CustomersTable.phone] = fields.phone
        statement[CustomersTable.phoneE164] = fields.phoneE164
        statement[CustomersTable.alternatePhone] = fields.alternatePhone
        statement[CustomersTable.email] = fields.email
        statement[CustomersTable.tin] = fields.tin
        statement[CustomersTable.businessDescription] = fields.businessDescription
        statement[CustomersTable.streetAddress] = fields.streetAddress
        statement[CustomersTable.city] = fields.city
        statement[CustomersTable.stateCode] = fields.stateCode
        statement[CustomersTable.lgaCode] = fields.lgaCode
        statement[CustomersTable.locationId] = fields.locationId
        statement[CustomersTable.correspondentId] = fields.correspondentId
        statement[CustomersTable.dealerCategoryId] = fields.dealerCategoryId
        statement[CustomersTable.creditLimit] = fields.creditLimit
        statement[CustomersTable.userId] = fields.userId
        statement[CustomersTable.customAttributes] = Json.write(fields.customAttributes)
    }

    /** Field names (never values) for the audit event. */
    private fun changedFields(current: CustomerDetail, next: Fields): List<String> = buildList {
        if (current.name != next.name) add("name")
        if (current.contactPerson != next.contactPerson) add("contactPerson")
        if (current.phone != next.phone) add("phone")
        if (current.alternatePhone != next.alternatePhone) add("alternatePhone")
        if (current.email != next.email) add("email")
        if (current.tin != next.tin) add("tin")
        if (current.businessDescription != next.businessDescription) add("businessDescription")
        if (current.streetAddress != next.streetAddress) add("streetAddress")
        if (current.city != next.city) add("city")
        if (current.stateCode != next.stateCode) add("stateCode")
        if (current.lgaCode != next.lgaCode) add("lgaCode")
        if (current.location?.id != next.locationId) add("locationId")
        if (current.correspondent?.id != next.correspondentId) add("correspondentId")
        if (current.creditLimit.compareTo(next.creditLimit) != 0) add("creditLimit")
        if (current.userId != next.userId) add("userId")
        if (current.customAttributes != next.customAttributes) add("customAttributes")
    }
}
