package com.zifiq.platform.events

import com.zifiq.platform.events.model.DomainEventsTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantContext
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.insertAndGetId
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/** Who caused an event: a signed-in user, or a named system job. */
sealed interface EventActor {
    data class User(val userId: UUID) : EventActor
    data class System(val label: String) : EventActor
}

/**
 * Records a business event — docs/implemented/03-cross-module-conventions.md §7.3. Runs in the caller's
 * transaction (it never opens its own), so an event exists if and only if the change it describes
 * committed. Payloads must stay small and never carry cost values, OTP codes, secrets or full
 * account numbers.
 */
@Component
class DomainEvents(
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
) {
    fun record(
        type: String,
        aggregateType: String,
        aggregateId: UUID,
        aggregateRef: String?,
        summary: String,
        payload: Map<String, Any?> = emptyMap(),
        locationId: UUID? = null,
        actor: EventActor? = null,
        tenantId: UUID? = null,
    ): UUID {
        val resolvedActor = actor ?: currentActor()
        return DomainEventsTable.insertAndGetId {
            it[DomainEventsTable.tenantId] = tenantId ?: tenantContext.requireTenantId()
            it[DomainEventsTable.eventType] = type
            it[DomainEventsTable.aggregateType] = aggregateType
            it[DomainEventsTable.aggregateId] = aggregateId
            it[DomainEventsTable.aggregateRef] = aggregateRef
            it[DomainEventsTable.locationId] = locationId
            it[DomainEventsTable.actorUserId] = (resolvedActor as? EventActor.User)?.userId
            it[DomainEventsTable.actorLabel] = (resolvedActor as? EventActor.System)?.label
            it[DomainEventsTable.occurredAt] = Instant.now()
            it[DomainEventsTable.payload] = payload
            it[DomainEventsTable.summary] = summary.take(300)
        }.value
    }

    /** The signed-in user, or "System" for work running outside a request (scheduled jobs). */
    private fun currentActor(): EventActor =
        if (SecurityContextHolder.getContext().authentication is JwtAuthenticationToken) {
            EventActor.User(currentActorProvider.current().userId)
        } else {
            EventActor.System("System")
        }
}
