package com.zifiq.platform.files

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Mirrors V9_1. */
object StoredFilesTable : UUIDTable("stored_files") {
    val tenantId = reference("tenant_id", TenantsTable)
    val purpose = varchar("purpose", 60)
    val storageKey = varchar("storage_key", 400)
    val originalName = varchar("original_name", 255)
    val contentType = varchar("content_type", 100)
    val sizeBytes = long("size_bytes")
    val sha256 = varchar("sha256", 64)
    val status = varchar("status", 20)
    val linkedType = varchar("linked_type", 60).nullable()
    val linkedId = uuid("linked_id").nullable()
    val uploadedByUserId = reference("uploaded_by_user_id", AppUsersTable)
    val uploadedAt = timestamp("uploaded_at")
    val deletedByUserId = uuid("deleted_by_user_id").nullable()
    val deletedAt = timestamp("deleted_at").nullable()
}

data class StoredFileView(
    val id: UUID,
    val purpose: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val status: String,
    val linkedType: String?,
    val linkedId: UUID?,
    val uploadedByUserId: UUID,
    val uploadedAt: Instant,
)

data class FileDownload(val file: StoredFileView, val url: String, val expiresAt: Instant)

data class FileLink(val url: String, val contentType: String, val expiresAt: Instant)

class FileContent(val bytes: ByteArray, val contentType: String)

data class FilePurposeView(val code: String, val label: String, val contentTypes: List<String>, val maxBytes: Long)

/**
 * Uploaded files (docs/03 §8.4). Modules either call [store] from their own endpoints or let the
 * client use the generic `/api/v1/files` endpoints with a registered purpose. The bytes go to S3
 * first and the metadata row second, in the caller's tenant: a failed upload leaves no row, and a
 * failed insert leaves only an unreferenced object, never a row pointing at nothing.
 */
@Service
class FileService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
    private val storage: ObjectStorage,
    private val properties: StorageProperties,
    private val domainEvents: DomainEvents,
    providers: List<FilePurposeProvider>,
) {
    private val purposes: Map<String, FilePurpose> = providers.flatMap { it.purposes() }.let { all ->
        val duplicates = all.groupBy { it.code }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "File purposes registered twice: $duplicates" }
        all.associateBy { it.code }
    }

    fun purposes(): List<FilePurposeView> = purposes.values.sortedBy { it.code }.map { purpose ->
        FilePurposeView(purpose.code, purpose.label, purpose.kinds.map { it.contentType }.sorted(), purpose.maxBytes)
    }

    fun store(purposeCode: String, originalName: String, bytes: ByteArray, linkedType: String? = null, linkedId: UUID? = null): StoredFileView {
        if (!storage.configured) throw ApiErrors.storageNotConfigured()
        val purpose = purposes[purposeCode] ?: throw ApiErrors.unknownFilePurpose(purposeCode, purposes.keys.sorted())
        purpose.uploadPermission?.let(::require)
        if (bytes.isEmpty()) throw ApiErrors.validationFailed(mapOf("file" to "The file is empty"))
        if (bytes.size > purpose.maxBytes) throw ApiErrors.fileTooLarge(purpose.maxBytes)
        val name = cleanName(originalName)
        val kind = FileTypeDetector.detect(bytes, name)
        if (kind == null || kind !in purpose.kinds) {
            throw ApiErrors.fileTypeNotAllowed(kind?.contentType ?: "unrecognised", purpose.kinds.map { it.contentType }.sorted())
        }
        if (kind == FileKind.SVG) SvgSafety.problem(bytes)?.let { throw ApiErrors.fileUnsafe(it) }

        val id = UUID.randomUUID()
        val now = Instant.now()
        val tenantId = tenantContext.requireTenantId()
        val month = now.atZone(ZoneOffset.UTC)
        val key = "${properties.keyPrefix}tenants/$tenantId/${purpose.code}/${month.year}/%02d/$id".format(month.monthValue)
        storage.put(key, bytes, kind.contentType)

        return tx.execute {
            StoredFilesTable.insert {
                it[StoredFilesTable.id] = id
                it[StoredFilesTable.tenantId] = tenantId
                it[StoredFilesTable.purpose] = purpose.code
                it[storageKey] = key
                it[StoredFilesTable.originalName] = name
                it[contentType] = kind.contentType
                it[sizeBytes] = bytes.size.toLong()
                it[sha256] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }
                it[status] = "ACTIVE"
                it[StoredFilesTable.linkedType] = linkedType
                it[StoredFilesTable.linkedId] = linkedId
                it[uploadedByUserId] = currentActorProvider.current().userId
                it[uploadedAt] = now
            }
            domainEvents.record(
                type = "platform.file.uploaded",
                aggregateType = "stored-file",
                aggregateId = id,
                aggregateRef = null,
                summary = "File uploaded: $name (${purpose.label.substringBefore(" (")})",
                payload = mapOf("purpose" to purpose.code, "contentType" to kind.contentType, "sizeBytes" to bytes.size),
            )
            view(row(id))
        }
    }

    fun get(id: UUID): StoredFileView = tx.execute { view(readable(id)) }

    fun download(id: UUID): FileDownload = tx.execute {
        val row = readable(id)
        if (row[StoredFilesTable.status] != "ACTIVE") throw ApiErrors.fileNotFound()
        val url = storage.downloadUrl(row[StoredFilesTable.storageKey], row[StoredFilesTable.originalName], row[StoredFilesTable.contentType])
        FileDownload(view(row), url, Instant.now().plus(properties.downloadUrlTtl))
    }

    // ---- For modules that own a file through their own records (caller's transaction; no permission checks) ----

    /** A link that shows the file in place (an `<img>` source), valid for [ttl]; null when the file is gone or storage isn't configured. */
    fun inlineLink(id: UUID, ttl: Duration): FileLink? {
        if (!storage.configured) return null
        val row = active(id) ?: return null
        val url = storage.downloadUrl(row[StoredFilesTable.storageKey], row[StoredFilesTable.originalName], row[StoredFilesTable.contentType], inline = true, ttl = ttl)
        return FileLink(url, row[StoredFilesTable.contentType], Instant.now().plus(ttl))
    }

    /** The file's bytes and type (e.g. a logo printed on a PDF); null when it is gone or storage isn't configured. */
    fun content(id: UUID): FileContent? {
        if (!storage.configured) return null
        val row = active(id) ?: return null
        return FileContent(storage.get(row[StoredFilesTable.storageKey]), row[StoredFilesTable.contentType])
    }

    /** Marks a file the owning record no longer uses as deleted (a replaced logo). */
    fun retire(id: UUID) {
        StoredFilesTable.update({ (StoredFilesTable.id eq id) and (StoredFilesTable.tenantId eq tenantContext.requireTenantId()) }) {
            it[status] = "DELETED"
            it[deletedByUserId] = currentActorProvider.current().userId
            it[deletedAt] = Instant.now()
        }
    }

    private fun active(id: UUID): ResultRow? =
        StoredFilesTable.selectAll()
            .where { (StoredFilesTable.id eq id) and (StoredFilesTable.tenantId eq tenantContext.requireTenantId()) and (StoredFilesTable.status eq "ACTIVE") }
            .firstOrNull()

    /** Marks the file deleted (records are never hard-deleted, docs/03 §13). Uploader or a holder of the purpose's upload permission. */
    fun delete(id: UUID): StoredFileView = tx.execute {
        val row = readable(id)
        val userId = currentActorProvider.current().userId
        val purpose = purposes[row[StoredFilesTable.purpose]]
        val mayDelete = row[StoredFilesTable.uploadedByUserId].value == userId ||
            purpose?.uploadPermission?.let { permissionAuthorizer.hasPermission(userId, it) } == true
        if (!mayDelete) throw ApiErrors.accessDenied()
        StoredFilesTable.update({ StoredFilesTable.id eq id }) {
            it[status] = "DELETED"
            it[deletedByUserId] = userId
            it[deletedAt] = Instant.now()
        }
        domainEvents.record(
            type = "platform.file.deleted",
            aggregateType = "stored-file",
            aggregateId = id,
            aggregateRef = null,
            summary = "File deleted: ${row[StoredFilesTable.originalName]}",
        )
        view(row(id))
    }

    private fun row(id: UUID): ResultRow =
        StoredFilesTable.selectAll()
            .where { (StoredFilesTable.id eq id) and (StoredFilesTable.tenantId eq tenantContext.requireTenantId()) }
            .firstOrNull() ?: throw ApiErrors.fileNotFound()

    /** Not found (rather than forbidden) when the caller may not read it. */
    private fun readable(id: UUID): ResultRow {
        val row = row(id)
        val userId = currentActorProvider.current().userId
        val readPermission = purposes[row[StoredFilesTable.purpose]]?.readPermission
        val allowed = row[StoredFilesTable.uploadedByUserId].value == userId ||
            readPermission == null || permissionAuthorizer.hasPermission(userId, readPermission)
        if (!allowed) throw ApiErrors.fileNotFound()
        return row
    }

    private fun require(permission: String) {
        if (!permissionAuthorizer.hasPermission(currentActorProvider.current().userId, permission)) throw ApiErrors.accessDenied()
    }

    /** Keeps only the last path segment and drops control characters; the name is display-only. */
    private fun cleanName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 && it.code != 127 }.trim().take(255).ifEmpty { "file" }

    private fun view(row: ResultRow) = StoredFileView(
        id = row[StoredFilesTable.id].value,
        purpose = row[StoredFilesTable.purpose],
        originalName = row[StoredFilesTable.originalName],
        contentType = row[StoredFilesTable.contentType],
        sizeBytes = row[StoredFilesTable.sizeBytes],
        sha256 = row[StoredFilesTable.sha256],
        status = row[StoredFilesTable.status],
        linkedType = row[StoredFilesTable.linkedType],
        linkedId = row[StoredFilesTable.linkedId],
        uploadedByUserId = row[StoredFilesTable.uploadedByUserId].value,
        uploadedAt = row[StoredFilesTable.uploadedAt],
    )
}

@Tag(name = "Files", description = "Uploads stored in S3 (docs/03 §8.4)")
@SecurityRequirement(name = "bearerAuth")
interface FilesApi {
    @Operation(summary = "Upload a file (multipart: file + purpose); the type is checked from the file's content")
    fun upload(file: MultipartFile, purpose: String): ApiEnvelope<StoredFileView>

    @Operation(summary = "Upload purposes, with accepted types and size limits")
    fun purposes(): ApiEnvelope<List<FilePurposeView>>

    @Operation(summary = "File details")
    fun get(id: UUID): ApiEnvelope<StoredFileView>

    @Operation(summary = "A short-lived download link (expiresAt); the browser downloads straight from storage")
    fun download(id: UUID): ApiEnvelope<FileDownload>

    @Operation(summary = "Mark a file deleted (uploader, or a holder of the purpose's upload permission)")
    fun delete(id: UUID): ApiEnvelope<StoredFileView>
}

@RestController
@RequestMapping("/api/v1/files")
class FilesController(private val fileService: FileService) : FilesApi {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    override fun upload(@RequestParam("file") file: MultipartFile, @RequestParam("purpose") purpose: String): ApiEnvelope<StoredFileView> =
        ApiEnvelope(fileService.store(purpose, file.originalFilename ?: "file", file.bytes))

    @GetMapping("/purposes")
    override fun purposes(): ApiEnvelope<List<FilePurposeView>> = ApiEnvelope(fileService.purposes())

    @GetMapping("/{id}")
    override fun get(@PathVariable id: UUID): ApiEnvelope<StoredFileView> = ApiEnvelope(fileService.get(id))

    @GetMapping("/{id}/download")
    override fun download(@PathVariable id: UUID): ApiEnvelope<FileDownload> = ApiEnvelope(fileService.download(id))

    @DeleteMapping("/{id}")
    override fun delete(@PathVariable id: UUID): ApiEnvelope<StoredFileView> = ApiEnvelope(fileService.delete(id))
}
