package com.zifiq.settings.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.json.Json
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.settings.dto.OnboardingCompleteRequest
import com.zifiq.settings.dto.OnboardingStatus
import com.zifiq.settings.dto.OnboardingStep
import com.zifiq.settings.model.CompanyProfilesTable
import java.time.Instant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * The first-run wizard's progress (docs/06 §4.3): company → branch → factory. Branch and factory are
 * read from platform `locations`, so Settings needs no dependency on Showroom or Factory; the wizard's
 * own forms call those modules' create endpoints directly.
 */
@Service
class OnboardingService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val profiles: CompanyProfileService,
    private val domainEvents: DomainEvents,
) {
    fun status(): OnboardingStatus = tx.execute { status(profiles.row()) }

    /**
     * Every step must be done or explicitly skipped. The company step can't be skipped, since every
     * document prints it. Completing again only updates which steps were skipped.
     */
    fun complete(request: OnboardingCompleteRequest): OnboardingStatus = tx.execute {
        val requested = request.skippedSteps.map { it.trim().uppercase() }.toSet()
        requested.firstOrNull { it !in SKIPPABLE }?.let {
            throw ApiErrors.validationFailed(mapOf("skippedSteps" to "Only BRANCH and FACTORY can be skipped, not '$it'"))
        }
        val steps = status(profiles.row()).steps
        val missing = steps.filter { !it.done && it.key !in requested }.map { it.key }
        if (missing.isNotEmpty()) throw ApiErrors.onboardingIncomplete(missing)

        val firstTime = profiles.row()[CompanyProfilesTable.onboardingCompletedAt] == null
        val skipped = steps.filter { !it.done }.map { it.key }
        CompanyProfilesTable.update({ CompanyProfilesTable.tenantId eq tenantContext.requireTenantId() }) {
            if (firstTime) it[onboardingCompletedAt] = Instant.now()
            it[onboardingSkippedSteps] = Json.write(skipped)
        }
        if (firstTime) {
            domainEvents.record(
                type = "settings.onboarding.completed",
                aggregateType = "company_profile",
                aggregateId = tenantContext.requireTenantId(),
                aggregateRef = null,
                summary = if (skipped.isEmpty()) "Onboarding completed" else "Onboarding completed (skipped ${skipped.joinToString()})",
                payload = mapOf("skippedSteps" to skipped),
            )
        }
        status(profiles.row())
    }

    private fun status(profile: ResultRow): OnboardingStatus {
        val skipped = Json.readTree(profile[CompanyProfilesTable.onboardingSkippedSteps]).map { it.asText() }.toSet()
        val companyDone = listOf(CompanyProfilesTable.tradingName, CompanyProfilesTable.phone, CompanyProfilesTable.streetAddress)
            .all { profile[it].isNotBlank() }
        val steps = listOf(
            OnboardingStep("COMPANY", done = companyDone, skipped = false),
            OnboardingStep("BRANCH", done = hasLocation("SHOWROOM"), skipped = "BRANCH" in skipped),
            OnboardingStep("FACTORY", done = hasLocation("FACTORY"), skipped = "FACTORY" in skipped),
        ).map { it.copy(skipped = it.skipped && !it.done) }
        val completedAt = profile[CompanyProfilesTable.onboardingCompletedAt]
        return OnboardingStatus(completed = completedAt != null, completedAt = completedAt, steps = steps)
    }

    private fun hasLocation(type: String): Boolean =
        !LocationsTable.selectAll().where { (LocationsTable.tenantId eq tenantContext.requireTenantId()) and (LocationsTable.type eq type) }.limit(1).empty()

    companion object {
        private val SKIPPABLE = setOf("BRANCH", "FACTORY")
    }
}
