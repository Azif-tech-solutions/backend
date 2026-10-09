package com.zifiq.settings.spi

import java.util.UUID

/** The tenant's company profile, as the issuer printed on documents (docs/06 §5). */
interface CompanyProfileProvider {
    /** Runs in the caller's tenant-scoped transaction (docs/03 §2.4). */
    fun get(): CompanyProfile
}

data class CompanyProfile(
    val tradingName: String,
    val legalName: String?,
    val registrationNumber: String?,
    val tin: String?,
    val vatRegistrationNumber: String?,
    val nrsBusinessId: UUID?,
    val businessDescription: String?,
    val email: String?,
    val phone: String,
    val website: String?,
    val streetAddress: String,
    val city: String?,
    val postalCode: String?,
    val stateCode: String?,
    val stateName: String?,
    val lgaCode: String?,
    val countryCode: String,
    /** PNG or JPEG bytes ([logoContentType] says which); null when no logo is set. */
    val logo: ByteArray?,
    val logoContentType: String?,
    val documentFooter: String?,
)
