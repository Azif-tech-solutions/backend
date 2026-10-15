package com.zifiq.platform

import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.files.FileService
import com.zifiq.platform.files.ObjectStorage
import com.zifiq.platform.files.PlatformFilePurposes
import com.zifiq.platform.files.StorageProperties
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.settings.service.CompanyLogos
import com.zifiq.settings.service.CompanyProfileService
import com.zifiq.settings.service.SettingsFilePurposes
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Object storage in memory, for tests that store files but don't exercise S3 itself (FileStorageIntegrationTest does, against S3Mock). */
class InMemoryObjectStorage : ObjectStorage {
    val objects = ConcurrentHashMap<String, ByteArray>()
    override val configured = true

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        objects[key] = bytes
    }

    override fun get(key: String): ByteArray = objects.getValue(key)

    override fun downloadUrl(key: String, fileName: String, contentType: String, inline: Boolean, ttl: Duration?): String =
        "https://storage.test/$key?disposition=${if (inline) "inline" else "attachment"}"
}

/** The company profile service with its file storage, as Spring wires it. */
fun companyProfiles(
    tx: TenantScopedTransaction,
    tenantContext: TenantContext,
    actors: CurrentActorProvider,
    events: DomainEvents,
    storage: ObjectStorage = InMemoryObjectStorage(),
): CompanyProfileService {
    val properties = StorageProperties(bucket = "test", region = "eu-north-1")
    val files = FileService(tx, tenantContext, actors, PermissionAuthorizer(actors, tx), storage, properties, events, listOf(PlatformFilePurposes(), SettingsFilePurposes()))
    return CompanyProfileService(tx, tenantContext, actors, events, files, CompanyLogos(files))
}
