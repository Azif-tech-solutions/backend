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
    @Value("\${jwt.secret}")
    private val secret: String,
) {
    // HMAC-SHA256 needs at least 256 bits of key; a short secret is refused at startup rather than padded.
    private val keyBytes: ByteArray = secret.toByteArray(Charsets.UTF_8).also {
        require(it.size >= 32) { "JWT_SECRET must be at least 32 bytes (e.g. `openssl rand -base64 48`)" }
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
