package com.zifiq.platform.tenancy

import com.zifiq.common.errors.ApiErrors
import java.util.UUID
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class TenantContextInterceptor(private val tenantContext: TenantContext) : HandlerInterceptor {
    override fun preHandle(request: jakarta.servlet.http.HttpServletRequest, response: jakarta.servlet.http.HttpServletResponse, handler: Any): Boolean {
        // Spring Security's filter chain runs before MVC dispatch, so a request for a permitAll
        // endpoint (auth/register-tenant, login, actuator, swagger, ...) reaches here with no
        // JwtAuthenticationToken; a genuinely protected endpoint would already have been rejected
        // by the filter chain. Passing through (leaving TenantContext unset) rather than throwing
        // avoids duplicating SecurityConfiguration's permitAll list here.
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: return true
        val rawTenantId = authentication.token.getClaimAsString("tenant_id")
            ?: throw ApiErrors.tenantRequired()
        val parsedTenantId = runCatching { UUID.fromString(rawTenantId) }.getOrElse {
            throw ApiErrors.malformedTenantClaim()
        }
        tenantContext.set(parsedTenantId)
        return true
    }

    override fun afterCompletion(
        request: jakarta.servlet.http.HttpServletRequest,
        response: jakarta.servlet.http.HttpServletResponse,
        handler: Any,
        exception: Exception?,
    ) {
        tenantContext.clear()
    }
}
