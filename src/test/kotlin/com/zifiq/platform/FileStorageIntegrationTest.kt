package com.zifiq.platform

import com.zifiq.common.errors.ErrorCode
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.files.FileKind
import com.zifiq.platform.files.FilePurpose
import com.zifiq.platform.files.FilePurposeProvider
import com.zifiq.platform.files.FileService
import com.zifiq.platform.files.PlatformFilePurposes
import com.zifiq.platform.files.S3ObjectStorage
import com.zifiq.platform.files.StorageProperties
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.assertj.core.api.AbstractObjectAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

/** File storage (docs/03 §8.4) against S3Mock, an S3-compatible server, and real PostgreSQL with RLS. */
class FileStorageIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private const val BUCKET = "zifiq-test"
        private val tenantId: UUID = UUID.randomUUID()
        private val otherTenantId: UUID = UUID.randomUUID()
        private val uploader: UUID = UUID.randomUUID()
        private val colleague: UUID = UUID.randomUUID()
        private val outsider: UUID = UUID.randomUUID()

        private val s3: GenericContainer<*> = GenericContainer("adobe/s3mock:5.2")
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching { it < 500 })

        private val endpoint get() = "http://${s3.host}:${s3.getMappedPort(9090)}"

        @JvmStatic
        @BeforeAll
        fun startStorage() {
            s3.start()
            // The default credentials chain reads these system properties; S3Mock accepts any keys.
            System.setProperty("aws.accessKeyId", "test")
            System.setProperty("aws.secretAccessKey", "test")
            S3Client.builder().region(Region.EU_NORTH_1).endpointOverride(URI.create(endpoint)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()
                .use { it.createBucket { b -> b.bucket(BUCKET) } }

            transaction(database) {
                listOf(uploader, colleague, outsider).forEach { exec("insert into app_users (id, email, display_name) values ('$it', 'fs-$it@test.com', 'User $it')") }
                exec("insert into tenants (id, name) values ('$tenantId', 'Files Co'), ('$otherTenantId', 'Other Co')")
            }
            transaction(database) {
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                listOf(uploader, colleague).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$it')") }
            }
            transaction(database) {
                exec("select set_config('app.tenant_id', '$otherTenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$otherTenantId', '$outsider')")
            }
        }

        @JvmStatic
        @AfterAll
        fun stopStorage() {
            s3.stop()
        }
    }

    /** A restricted purpose: tiny, PDFs only, readable only with a permission nobody here holds. */
    private val restricted = object : FilePurposeProvider {
        override fun purposes() = listOf(FilePurpose("test.secret", "Secret PDF", setOf(FileKind.PDF), 1024, readPermission = "test.secret.read"))
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)

    private fun service(properties: StorageProperties = StorageProperties(bucket = BUCKET, region = "eu-north-1", endpoint = endpoint, keyPrefix = "test/")) =
        FileService(tx, tenantContext, actors, PermissionAuthorizer(actors, tx), S3ObjectStorage(properties), properties,
            DomainEvents(tenantContext, actors), listOf(PlatformFilePurposes(), restricted))

    private val files by lazy { service() }

    private fun signIn(user: UUID, tenant: UUID = tenantId) {
        tenantContext.set(tenant)
        val jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    private val pdf = "%PDF-1.7\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".toByteArray()

    @Test
    fun `an uploaded file round-trips through storage with its type detected from the bytes`() {
        signIn(uploader)
        val stored = files.store(PlatformFilePurposes.GENERAL_ATTACHMENT, "C:\\Users\\ada\\Bank statement – Sept.pdf", pdf)
        assertThat(stored.contentType).isEqualTo("application/pdf")
        assertThat(stored.originalName).isEqualTo("Bank statement – Sept.pdf")
        assertThat(stored.sizeBytes).isEqualTo(pdf.size.toLong())

        signIn(colleague)
        val download = files.download(stored.id)
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(download.url)).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo(pdf)
        // The storage key is tenant/purpose/date/id; the file name only appears in the download-name parameter.
        assertThat(download.url.substringBefore("?")).contains("tenants/$tenantId/general.attachment/").doesNotContain("Bank")
    }

    @Test
    fun `spoofed types, oversized files and unknown purposes are refused`() {
        signIn(uploader)
        val executable = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00, 0x00, 0x00)
        failsWith { files.store(PlatformFilePurposes.GENERAL_ATTACHMENT, "invoice.pdf", executable) }.isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED)
        failsWith { files.store("test.secret", "notes.csv", "a,b\n1,2\n".toByteArray()) }.isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED)
        failsWith { files.store("test.secret", "big.pdf", pdf + ByteArray(2048) { 0x20 }) }.isEqualTo(ErrorCode.FILE_TOO_LARGE)
        failsWith { files.store("nonsense", "a.pdf", pdf) }.isEqualTo(ErrorCode.UNKNOWN_FILE_PURPOSE)
        failsWith { files.store(PlatformFilePurposes.GENERAL_ATTACHMENT, "empty.pdf", ByteArray(0)) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        assertThat(files.store(PlatformFilePurposes.GENERAL_ATTACHMENT, "prices.csv", "code,price\nITM-1,4500\n".toByteArray()).contentType).isEqualTo("text/csv")
    }

    @Test
    fun `files are private to their tenant and to the purpose's readers`() {
        signIn(uploader)
        val open = files.store(PlatformFilePurposes.GENERAL_ATTACHMENT, "open.pdf", pdf)
        val secret = files.store("test.secret", "secret.pdf", pdf)
        assertThat(files.get(secret.id).id).isEqualTo(secret.id) // the uploader can always read their own

        signIn(colleague)
        failsWith { files.get(secret.id) }.isEqualTo(ErrorCode.FILE_NOT_FOUND)

        signIn(outsider, otherTenantId)
        failsWith { files.get(open.id) }.isEqualTo(ErrorCode.FILE_NOT_FOUND)
    }

    @Test
    fun `deleting marks the file deleted, only for its uploader`() {
        signIn(uploader)
        val stored = files.store(PlatformFilePurposes.GENERAL_ATTACHMENT, "to-delete.pdf", pdf)

        signIn(colleague)
        failsWith { files.delete(stored.id) }.isEqualTo(ErrorCode.FORBIDDEN)

        signIn(uploader)
        assertThat(files.delete(stored.id).status).isEqualTo("DELETED")
        failsWith { files.download(stored.id) }.isEqualTo(ErrorCode.FILE_NOT_FOUND)
    }

    @Test
    fun `without a bucket the server runs and uploads say storage isn't configured`() {
        signIn(uploader)
        failsWith { service(StorageProperties(bucket = "")).store(PlatformFilePurposes.GENERAL_ATTACHMENT, "a.pdf", pdf) }
            .isEqualTo(ErrorCode.STORAGE_NOT_CONFIGURED)
    }
}
