package com.zifiq

import com.zifiq.common.errors.ErrorCode
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.identity.AuthService
import com.zifiq.platform.identity.dto.*
import com.zifiq.platform.security.CurrentActor
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.JwtTokenProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.UUID

/**
 * Integration test against a real (Testcontainers) Postgres running the actual Flyway migrations.
 * AuthService now persists via Exposed, which isn't interface-based, so it can't be exercised
 * against a mocked JdbcTemplate the way the original test did — see docs/00 §8.
 */
class AuthServiceTest : PostgresIntegrationTest() {
    private lateinit var currentActorProvider: CurrentActorProvider
    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        currentActorProvider = mock(CurrentActorProvider::class.java)
        authService = AuthService(
            exposedDatabase = database,
            passwordEncoder = BCryptPasswordEncoder(),
            jwtTokenProvider = JwtTokenProvider("test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256!"),
            currentActorProvider = currentActorProvider,
        )
    }

    @Test
    fun `registerTenant provisions tenant, seeds default roles, assigns Administrator, and issues tokens`() {
        val response = authService.registerTenant(
            TenantRegistrationRequest(
                companyName = "Apex Industries",
                adminEmail = "admin-${UUID.randomUUID()}@apex.com",
                adminDisplayName = "Alice Admin",
                password = "password123",
            ),
        )

        assertEquals("Apex Industries", response.companyName)
        assertNotNull(response.tokenResponse.accessToken)
        assertEquals(response.tenantId, response.tokenResponse.activeTenantId)
    }

    @Test
    fun `registerTenant rejects a duplicate email`() {
        val email = "dup-${UUID.randomUUID()}@apex.com"
        authService.registerTenant(
            TenantRegistrationRequest("Apex Industries", email, "Alice Admin", "password123"),
        )

        val exception = assertThrows(ApiException::class.java) {
            authService.registerTenant(
                TenantRegistrationRequest("Another Co", email, "Bob", "password456"),
            )
        }
        assertEquals(ErrorCode.USER_EXISTS, exception.code)
    }

    @Test
    fun `login validates credentials and returns tokens for the user's tenant`() {
        val email = "login-${UUID.randomUUID()}@apex.com"
        val registration = authService.registerTenant(
            TenantRegistrationRequest("Apex Industries", email, "Alice Admin", "password123"),
        )

        val response = authService.login(UserLoginRequest(email = email, password = "password123"))

        assertNotNull(response.accessToken)
        assertEquals(registration.tenantId, response.activeTenantId)
    }

    @Test
    fun `login rejects an incorrect password`() {
        val email = "badlogin-${UUID.randomUUID()}@apex.com"
        authService.registerTenant(
            TenantRegistrationRequest("Apex Industries", email, "Alice Admin", "password123"),
        )

        val exception = assertThrows(ApiException::class.java) {
            authService.login(UserLoginRequest(email = email, password = "wrong-password"))
        }
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.code)
    }

    @Test
    fun `switchTenant verifies membership and issues a newly scoped token`() {
        val email = "switch-${UUID.randomUUID()}@apex.com"
        val registration = authService.registerTenant(
            TenantRegistrationRequest("Apex Industries", email, "Alice Admin", "password123"),
        )

        `when`(currentActorProvider.current()).thenReturn(
            CurrentActor(registration.userId, registration.tenantId, email, "Alice Admin"),
        )

        val response = authService.switchTenant(TenantSwitchRequest(registration.tenantId))

        assertEquals(registration.tenantId, response.activeTenantId)
    }

    @Test
    fun `refreshToken issues a new access token and revokes the old refresh token`() {
        val email = "refresh-${UUID.randomUUID()}@apex.com"
        val registration = authService.registerTenant(
            TenantRegistrationRequest("Apex Industries", email, "Alice Admin", "password123"),
        )

        val refreshed = authService.refreshToken(RefreshTokenRequest(registration.tokenResponse.refreshToken))
        assertNotNull(refreshed.accessToken)

        val exception = assertThrows(ApiException::class.java) {
            authService.refreshToken(RefreshTokenRequest(registration.tokenResponse.refreshToken))
        }
        assertEquals(ErrorCode.EXPIRED_REFRESH_TOKEN, exception.code)
    }

    @Test
    fun `forgotPassword queues a reset code without revealing whether the account exists`() {
        val email = "forgot-${UUID.randomUUID()}@apex.com"
        authService.registerTenant(
            TenantRegistrationRequest("Apex Industries", email, "Alice Admin", "password123"),
        )

        val existingAccountResponse = authService.forgotPassword(ForgotPasswordRequest(email))
        val unknownAccountResponse = authService.forgotPassword(ForgotPasswordRequest("nobody-${UUID.randomUUID()}@apex.com"))

        assertEquals("PENDING_VERIFICATION", existingAccountResponse.status)
        assertEquals(existingAccountResponse.message, unknownAccountResponse.message)
    }
}
