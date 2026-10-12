package com.zifiq.platform.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.api.ApiError
import com.zifiq.platform.api.ApiErrorEnvelope
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableMethodSecurity
class SecurityConfiguration(
    private val objectMapper: ObjectMapper,
    @Value("\${jwt.secret}")
    private val secret: String,
    // Comma-separated allowed-origin patterns from CORS_ALLOWED_ORIGINS (wildcards allowed for local dev,
    // e.g. "http://localhost:*"). Exact origins only for deployed sites: credentials are allowed, so a
    // wildcard like https://*.vercel.app would admit anyone's app. Empty = no cross-origin calls allowed.
    @Value("\${cors.allowed-origins:}")
    private val corsAllowedOrigins: String,
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = corsAllowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
            // Lets the frontend read the file name of PDF/image downloads.
            exposedHeaders = listOf("Content-Disposition")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val keyBytes = secret.toByteArray(Charsets.UTF_8).let { bytes ->
            if (bytes.size < 32) bytes.copyOf(32) else bytes
        }
        val secretKey = SecretKeySpec(keyBytes, "HmacSHA256")
        return NimbusJwtDecoder.withSecretKey(secretKey).build()
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .cors { it.configurationSource(corsConfigurationSource()) }
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        // Without this, Spring Security's default AnonymousAuthenticationFilter assigns an
        // AnonymousAuthenticationToken to requests with no credentials, and that token reports
        // isAuthenticated() == true — so .anyRequest().authenticated() below lets it through to
        // @PreAuthorize, where PermissionAuthorizer.hasPermission() returns false (it's not a
        // JwtAuthenticationToken) and Spring throws AccessDeniedException, surfacing as 403
        // FORBIDDEN instead of 401 UNAUTHORIZED for a simply-missing token. Disabling anonymous
        // auth makes `authentication` null for these requests, so .authenticated() correctly
        // rejects them at the gate and routes to authenticationEntryPoint (401) instead.
        .anonymous { it.disable() }
        .authorizeHttpRequests {
            it.requestMatchers(
                "/api/v1/auth/register-tenant",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/forgot-password",
                "/api/v1/auth/reset-password",
                "/actuator/health",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
            ).permitAll()
                .anyRequest().authenticated()
        }
        .exceptionHandling {
            it.authenticationEntryPoint { _, response, _ ->
                val error = ApiErrors.unauthenticated()
                response.status = error.status.value()
                response.contentType = "application/json"
                objectMapper.writeValue(
                    response.outputStream,
                    ApiErrorEnvelope(error = ApiError(error.code, error.message, error.details)),
                )
            }
            it.accessDeniedHandler { _, response, _ ->
                val error = ApiErrors.accessDenied()
                response.status = error.status.value()
                response.contentType = "application/json"
                objectMapper.writeValue(
                    response.outputStream,
                    ApiErrorEnvelope(error = ApiError(error.code, error.message, error.details)),
                )
            }
        }
        .oauth2ResourceServer { it.jwt { jwtConfigurer -> jwtConfigurer.decoder(jwtDecoder()) } }
        .build()
}
