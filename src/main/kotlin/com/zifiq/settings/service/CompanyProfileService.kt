package com.zifiq.settings.service

import com.zifiq.platform.directory.UserRef
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.phone.PhoneNumbers
import com.zifiq.common.tax.NigeriaLgasTable
import com.zifiq.common.tax.NigeriaStatesTable
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.files.FileService
import com.zifiq.platform.files.StoredFilesTable
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantProvisioner
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import com.zifiq.settings.dto.CompanyProfileRequest
import com.zifiq.settings.dto.CompanyProfileView
import com.zifiq.settings.dto.NrsReadiness
import com.zifiq.settings.model.CompanyProfilesTable
import com.zifiq.settings.spi.CompanyProfile
import com.zifiq.settings.spi.CompanyProfileProvider
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * The company profile (docs/06 §4.1–4.2): the issuer every document prints. Every tenant has exactly
 * one (rule ST1): V10 backfilled existing tenants, and [provision] creates it for new ones.
 */
@Service
class CompanyProfileService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val domainEvents: DomainEvents,
    private val files: FileService,
    private val logos: CompanyLogos,
) : CompanyProfileProvider, TenantProvisioner {

    fun view(): CompanyProfileView = tx.execute { toView(row()) }

    fun update(request: CompanyProfileRequest): CompanyProfileView = tx.execute {
        val before = row()
        val stateCode = request.stateCode.clean()?.uppercase()
        val lgaCode = request.lgaCode.clean()?.uppercase()
        validateRegion(stateCode, lgaCode)

        CompanyProfilesTable.update({ CompanyProfilesTable.tenantId eq tenantId() }) {
            it[tradingName] = request.tradingName.trim()
            it[legalName] = request.legalName.clean()
            it[registrationNumber] = request.registrationNumber.clean()
            it[tin] = request.tin.clean()?.uppercase()
            it[vatRegistrationNumber] = request.vatRegistrationNumber.clean()
            it[nrsBusinessId] = request.nrsBusinessId
            it[businessDescription] = request.businessDescription.clean()
            it[email] = request.email.clean()?.lowercase()
            it[phone] = request.phone.trim()
            it[phoneE164] = PhoneNumbers.normalise(request.phone)
            it[website] = request.website.clean()
            it[streetAddress] = request.streetAddress.trim()
            it[city] = request.city.clean()
            it[postalCode] = request.postalCode.clean()
            it[CompanyProfilesTable.stateCode] = stateCode
            it[CompanyProfilesTable.lgaCode] = lgaCode
            it[countryCode] = request.countryCode.trim().uppercase()
            it[documentFooter] = request.documentFooter.clean()
            it[financialYearStartMonth] = request.financialYearStartMonth
            it[updatedByUserId] = userId()
            it[updatedAt] = Instant.now()
        }

        val after = row()
        val changed = PROFILE_FIELDS.filter { (_, column) -> before[column] != after[column] }.map { it.first }
        if (changed.isNotEmpty()) {
            // Field names only: the values are on the profile itself.
            record("settings.company.updated", "Company profile updated (${changed.joinToString()})", mapOf("fields" to changed))
        }
        toView(after)
    }

    /**
     * The logo goes to file storage (S3) under the `settings.company-logo` purpose: PNG, JPEG, WebP,
     * AVIF or SVG up to 1 MB, recognised by its bytes; an SVG with script, event handlers or outside
     * references is refused (FileService). The logo it replaces is marked deleted.
     */
    fun setLogo(bytes: ByteArray, fileName: String): CompanyProfileView {
        val file = files.store(CompanyLogos.PURPOSE, fileName, bytes, linkedType = "company_profile", linkedId = tenantId())
        return tx.execute {
            val previous = row()[CompanyProfilesTable.logoFileId]
            CompanyProfilesTable.update({ CompanyProfilesTable.tenantId eq tenantId() }) {
                it[logoFileId] = file.id
                it[logoUpdatedAt] = Instant.now()
                it[updatedByUserId] = userId()
                it[updatedAt] = Instant.now()
            }
            previous?.let(files::retire)
            record("settings.company.logo-updated", "Company logo updated", mapOf("contentType" to file.contentType, "bytes" to file.sizeBytes, "fileId" to file.id))
            toView(row())
        }
    }

    fun removeLogo(): CompanyProfileView = tx.execute {
        val previous = row()[CompanyProfilesTable.logoFileId]
        if (previous != null) {
            CompanyProfilesTable.update({ CompanyProfilesTable.tenantId eq tenantId() }) {
                it[logoFileId] = null
                it[logoUpdatedAt] = Instant.now()
                it[updatedByUserId] = userId()
                it[updatedAt] = Instant.now()
            }
            files.retire(previous)
            record("settings.company.logo-updated", "Company logo removed", mapOf("removed" to true))
        }
        toView(row())
    }

    // ---- CompanyProfileProvider (spi) ----

    override fun get(): CompanyProfile {
        val row = row()
        return CompanyProfile(
            tradingName = row[CompanyProfilesTable.tradingName],
            legalName = row[CompanyProfilesTable.legalName],
            registrationNumber = row[CompanyProfilesTable.registrationNumber],
            tin = row[CompanyProfilesTable.tin],
            vatRegistrationNumber = row[CompanyProfilesTable.vatRegistrationNumber],
            nrsBusinessId = row[CompanyProfilesTable.nrsBusinessId],
            businessDescription = row[CompanyProfilesTable.businessDescription],
            email = row[CompanyProfilesTable.email],
            phone = row[CompanyProfilesTable.phone],
            website = row[CompanyProfilesTable.website],
            streetAddress = row[CompanyProfilesTable.streetAddress],
            city = row[CompanyProfilesTable.city],
            postalCode = row[CompanyProfilesTable.postalCode],
            stateCode = row[CompanyProfilesTable.stateCode],
            stateName = row[CompanyProfilesTable.stateCode]?.let(::stateName),
            lgaCode = row[CompanyProfilesTable.lgaCode],
            countryCode = row[CompanyProfilesTable.countryCode],
            hasLogo = row[CompanyProfilesTable.logoFileId] != null,
            documentFooter = row[CompanyProfilesTable.documentFooter],
            documentLogo = documentLogo(row[CompanyProfilesTable.logoFileId]),
        )
    }

    // ---- TenantProvisioner: the new tenant's profile, named after the company it registered with ----

    override fun provision(tenantId: UUID, createdByUserId: UUID) {
        val name = TenantsTable.select(TenantsTable.name).where { TenantsTable.id eq tenantId }.single()[TenantsTable.name]
        CompanyProfilesTable.insertIgnore {
            it[CompanyProfilesTable.tenantId] = EntityID(tenantId, TenantsTable)
            it[tradingName] = name
            it[phone] = ""
            it[streetAddress] = ""
            it[countryCode] = "NG"
            it[financialYearStartMonth] = 1
            it[onboardingSkippedSteps] = "[]"
            it[updatedAt] = Instant.now()
        }
    }

    // ---- Shared with OnboardingService (runs in the caller's transaction) ----

    internal fun row(): ResultRow =
        CompanyProfilesTable.selectAll().where { CompanyProfilesTable.tenantId eq tenantId() }.single()

    private fun validateRegion(stateCode: String?, lgaCode: String?) {
        if (lgaCode != null && stateCode == null) throw ApiErrors.invalidLgaCode()
        if (stateCode != null && NigeriaStatesTable.selectAll().where { NigeriaStatesTable.code eq stateCode }.empty()) throw ApiErrors.invalidStateCode()
        if (lgaCode != null) {
            val lga = NigeriaLgasTable.selectAll().where { NigeriaLgasTable.code eq lgaCode }.firstOrNull()
            if (lga == null || lga[NigeriaLgasTable.stateCode] != stateCode) throw ApiErrors.invalidLgaCode()
        }
    }

    private fun toView(row: ResultRow): CompanyProfileView {
        val stateCode = row[CompanyProfilesTable.stateCode]
        val lgaCode = row[CompanyProfilesTable.lgaCode]
        val missing = NRS_FIELDS.filter { (_, column) -> row[column]?.toString().isNullOrBlank() }.map { it.first }
        val logo = row[CompanyProfilesTable.logoFileId]?.let(logos::link)
        return CompanyProfileView(
            tradingName = row[CompanyProfilesTable.tradingName],
            legalName = row[CompanyProfilesTable.legalName],
            registrationNumber = row[CompanyProfilesTable.registrationNumber],
            tin = row[CompanyProfilesTable.tin],
            vatRegistrationNumber = row[CompanyProfilesTable.vatRegistrationNumber],
            nrsBusinessId = row[CompanyProfilesTable.nrsBusinessId],
            businessDescription = row[CompanyProfilesTable.businessDescription],
            email = row[CompanyProfilesTable.email],
            phone = row[CompanyProfilesTable.phone],
            website = row[CompanyProfilesTable.website],
            streetAddress = row[CompanyProfilesTable.streetAddress],
            city = row[CompanyProfilesTable.city],
            postalCode = row[CompanyProfilesTable.postalCode],
            stateCode = stateCode,
            stateName = stateCode?.let(::stateName),
            lgaCode = lgaCode,
            lgaName = lgaCode?.let { code -> NigeriaLgasTable.select(NigeriaLgasTable.name).where { NigeriaLgasTable.code eq code }.firstOrNull()?.get(NigeriaLgasTable.name) },
            countryCode = row[CompanyProfilesTable.countryCode],
            hasLogo = logo != null,
            logoUrl = logo?.url,
            logoUrlExpiresAt = logo?.expiresAt,
            logoContentType = logo?.contentType,
            logoInDocuments = logo != null && CompanyLogos.inDocuments(logo.contentType),
            logoUpdatedAt = row[CompanyProfilesTable.logoUpdatedAt],
            documentFooter = row[CompanyProfilesTable.documentFooter],
            financialYearStartMonth = row[CompanyProfilesTable.financialYearStartMonth],
            nrsReadiness = NrsReadiness(ready = missing.isEmpty(), missing = missing),
            updatedBy = UserRef.of(row[CompanyProfilesTable.updatedByUserId]),
            updatedAt = row[CompanyProfilesTable.updatedAt],
        )
    }

    /** Loads the logo only when a document asks for it; the tenant is captured now, while the caller's transaction has it. */
    private fun documentLogo(fileId: UUID?): () -> String? {
        if (fileId == null) return { null }
        val tenantId = tenantId()
        return {
            StoredFilesTable.select(StoredFilesTable.contentType).where { StoredFilesTable.id eq fileId }.firstOrNull()
                ?.let { logos.dataUri(tenantId, fileId, it[StoredFilesTable.contentType]) }
        }
    }

    private fun stateName(code: String): String? =
        NigeriaStatesTable.select(NigeriaStatesTable.name).where { NigeriaStatesTable.code eq code }.firstOrNull()?.get(NigeriaStatesTable.name)

    private fun record(type: String, summary: String, payload: Map<String, Any?>) {
        domainEvents.record(type = type, aggregateType = "company_profile", aggregateId = tenantId(), aggregateRef = null, summary = summary, payload = payload)
    }

    private fun tenantId(): UUID = tenantContext.requireTenantId()
    private fun userId(): UUID = currentActorProvider.current().userId
    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

    companion object {
        /** What a PUT can change, by API field name (for the change event). */
        private val PROFILE_FIELDS = listOf(
            "tradingName" to CompanyProfilesTable.tradingName, "legalName" to CompanyProfilesTable.legalName,
            "registrationNumber" to CompanyProfilesTable.registrationNumber, "tin" to CompanyProfilesTable.tin,
            "vatRegistrationNumber" to CompanyProfilesTable.vatRegistrationNumber, "nrsBusinessId" to CompanyProfilesTable.nrsBusinessId,
            "businessDescription" to CompanyProfilesTable.businessDescription, "email" to CompanyProfilesTable.email,
            "phone" to CompanyProfilesTable.phone, "website" to CompanyProfilesTable.website,
            "streetAddress" to CompanyProfilesTable.streetAddress, "city" to CompanyProfilesTable.city,
            "postalCode" to CompanyProfilesTable.postalCode, "stateCode" to CompanyProfilesTable.stateCode,
            "lgaCode" to CompanyProfilesTable.lgaCode, "countryCode" to CompanyProfilesTable.countryCode,
            "documentFooter" to CompanyProfilesTable.documentFooter, "financialYearStartMonth" to CompanyProfilesTable.financialYearStartMonth,
        )

        /** The NRS issuer fields (accounting_supplier_party) still empty: informational only this phase (rule ST3). */
        private val NRS_FIELDS = listOf(
            "tin" to CompanyProfilesTable.tin, "email" to CompanyProfilesTable.email,
            "streetAddress" to CompanyProfilesTable.streetAddress, "stateCode" to CompanyProfilesTable.stateCode,
            "lgaCode" to CompanyProfilesTable.lgaCode, "nrsBusinessId" to CompanyProfilesTable.nrsBusinessId,
            "businessDescription" to CompanyProfilesTable.businessDescription,
        )
    }
}
