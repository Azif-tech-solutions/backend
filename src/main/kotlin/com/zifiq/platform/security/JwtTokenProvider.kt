package com.zifiq.platform.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.api.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import java.util.UUID

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret:zifiq-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256-signing!}")
    private val secret: String,
) {
    private val keyBytes: ByteArray by lazy {
        secret.toByteArray(Charsets.UTF_8).let { bytes ->
            if (bytes.size < 32) bytes.copyOf(32) else bytes
        }
    }

    val accessTokenValiditySeconds: Long = 3600 // 1 hour
    val refreshTokenValiditySeconds: Long = 86400 * 30 // 30 days

    fun generateAccessToken(userId: UUID, tenantId: UUID, email: String, displayName: String): String {
        val now = Instant.now()
        val claimsSet = JWTClaimsSet.Builder()
            .subject(userId.toString())
            .claim("tenant_id", tenantId.toString())
            .claim("email", email)
            .claim("name", displayName)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(accessTokenValiditySeconds)))
            .build()

        val signedJWT = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claimsSet)
        signedJWT.sign(MACSigner(keyBytes))
        return signedJWT.serialize()
    }

    fun parseAndValidate(token: String): JWTClaimsSet {
        return runCatching {
            val signedJWT = SignedJWT.parse(token)
            val verifier = MACVerifier(keyBytes)
            if (!signedJWT.verify(verifier)) {
                throw ApiErrors.invalidTokenSignature()
            }
            val claims = signedJWT.jwtClaimsSet
            if (claims.expirationTime != null && claims.expirationTime.before(Date())) {
                throw ApiErrors.tokenExpired()
            }
            claims
        }.getOrElse { ex ->
            if (ex is ApiException) throw ex
            throw ApiErrors.invalidToken()
        }
    }
}
