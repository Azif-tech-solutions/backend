package com.zifiq.platform.files

import com.zifiq.common.errors.ApiErrors
import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

/** `zifiq.storage.*` in application.yml; every value comes from an environment variable. */
@ConfigurationProperties("zifiq.storage")
data class StorageProperties(
    val bucket: String = "",
    val region: String = "eu-north-1",
    /** S3-compatible endpoint for local development (S3Mock); blank = AWS S3. */
    val endpoint: String = "",
    val keyPrefix: String = "",
    val downloadUrlTtl: Duration = Duration.ofMinutes(5),
) {
    /** Both are needed: the AWS SDK can't build a client without a region (S3Mock ignores it, but it must be set). */
    val configured: Boolean get() = bucket.isNotBlank() && region.isNotBlank()
}

/** Where file bytes live. One implementation (S3); tests run it against S3Mock. */
interface ObjectStorage {
    val configured: Boolean
    fun put(key: String, bytes: ByteArray, contentType: String)

    /** A short-lived link the browser downloads from directly; the API never streams the bytes. */
    fun downloadUrl(key: String, fileName: String, contentType: String): String
}

/**
 * S3 via the AWS SDK v2. Credentials come from the default provider chain: the EC2 instance's IAM
 * role in deployed environments, `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` locally (any value for S3Mock).
 * Every object is written with S3-managed server-side encryption. Clients are created on first use,
 * so the app starts without storage configured.
 */
@Component
class S3ObjectStorage(private val properties: StorageProperties) : ObjectStorage {
    override val configured: Boolean get() = properties.configured

    private val client: S3Client by lazy {
        S3Client.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            // Checksums only where S3 requires them, so S3-compatible stores used locally accept uploads too.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .apply { endpointOverride()?.let { endpointOverride(it).forcePathStyle(true) } }
            .build()
    }

    private val presigner: S3Presigner by lazy {
        S3Presigner.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .apply {
                endpointOverride()?.let {
                    endpointOverride(it)
                    serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                }
            }
            .build()
    }

    private fun endpointOverride(): URI? = properties.endpoint.takeIf { it.isNotBlank() }?.let(URI::create)

    private fun requireConfigured() {
        if (!configured) throw ApiErrors.storageNotConfigured()
    }

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        requireConfigured()
        client.putObject(
            PutObjectRequest.builder()
                .bucket(properties.bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(bytes.size.toLong())
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun downloadUrl(key: String, fileName: String, contentType: String): String {
        requireConfigured()
        val request = GetObjectRequest.builder()
            .bucket(properties.bucket)
            .key(key)
            .responseContentType(contentType)
            .responseContentDisposition(contentDisposition(fileName))
            .build()
        return presigner.presignGetObject(
            GetObjectPresignRequest.builder().signatureDuration(properties.downloadUrlTtl).getObjectRequest(request).build(),
        ).url().toExternalForm()
    }

    /** RFC 6266: an ASCII fallback plus the exact UTF-8 name. */
    private fun contentDisposition(fileName: String): String {
        val ascii = fileName.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = java.net.URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }
}
