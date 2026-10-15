package com.zifiq.platform.security

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.tenancy.TenantContext
import java.util.UUID
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

data class CurrentActor(
    val userId: UUID,
    val tenantId: UUID,
    val email: String?,
    val displayName: String?,
    /** The factory or showroom this session switched to (`location_id` claim), or null: see LocationAccessService. */
    val activeLocationId: UUID? = null,
)

@Component
class CurrentActorProvider(private val tenantContext: TenantContext) {
    fun current(): CurrentActor {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: throw ApiErrors.invalidAuthentication()
        val userId = runCatching { UUID.fromString(authentication.token.subject) }.getOrElse {
            throw ApiErrors.invalidUserInToken()
        }
        return CurrentActor(
            userId = userId,
            tenantId = tenantContext.requireTenantId(),
            email = authentication.token.getClaimAsString("email"),
            displayName = authentication.token.getClaimAsString("name"),
            activeLocationId = authentication.token.getClaimAsString("location_id")?.let { claim ->
                runCatching { UUID.fromString(claim) }.getOrElse { throw ApiErrors.invalidToken() }
            },
        )
    }
}
