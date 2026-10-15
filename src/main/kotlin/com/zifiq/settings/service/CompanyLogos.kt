package com.zifiq.settings.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.zifiq.platform.files.FileContent
import com.zifiq.platform.files.FileKind
import com.zifiq.platform.files.FilePurpose
import com.zifiq.platform.files.FilePurposeProvider
import com.zifiq.platform.files.FileService
import java.time.Duration
import java.util.Base64
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * The company logo as a stored file (docs/06 §4.1, docs/03 §8.4): its upload purpose, the link the
 * profile hands the browser, and the bytes documents embed. A stored file never changes (a new
 * logo is a new file), so the bytes are cached by tenant and file id with no invalidation; the
 * cache is bounded by size, not entry count.
 */
@Component
class CompanyLogos(private val files: FileService) {
    /** Caller's transaction. */
    fun link(fileId: UUID) = files.inlineLink(fileId, LINK_TTL)

    /** The logo as a `data:` URI for a PDF, or null when it's a type the PDF engine can't draw. Caller's transaction. */
    fun dataUri(tenantId: UUID, fileId: UUID, contentType: String): String? {
        if (!inDocuments(contentType)) return null
        val key = LogoKey(tenantId, fileId)
        val content = cache.getIfPresent(key) ?: files.content(fileId)?.also { cache.put(key, it) } ?: return null
        return "data:${content.contentType};base64," + Base64.getEncoder().encodeToString(content.bytes)
    }

    private data class LogoKey(val tenantId: UUID, val fileId: UUID)

    private val cache: Cache<LogoKey, FileContent> = Caffeine.newBuilder()
        .maximumWeight(32L * 1024 * 1024)
        .weigher<LogoKey, FileContent> { _, content -> content.bytes.size }
        .expireAfterAccess(Duration.ofHours(6))
        .build()

    companion object {
        const val PURPOSE = "settings.company-logo"
        const val MAX_BYTES = 1024L * 1024

        /** Long enough that a page showing the logo doesn't outlive its link; the profile hands out a fresh one on every read. */
        val LINK_TTL: Duration = Duration.ofHours(1)

        /** PNG and JPEG are raster images the PDF engine reads directly; SVG is drawn as vectors. AVIF and WebP it can't read. */
        fun inDocuments(contentType: String) = contentType in setOf(FileKind.PNG.contentType, FileKind.JPEG.contentType, FileKind.SVG.contentType)
    }
}

/** The logo's upload purpose. Separate from [CompanyLogos]: FileService collects every purpose, and CompanyLogos needs FileService. */
@Component
class SettingsFilePurposes : FilePurposeProvider {
    override fun purposes() = listOf(
        FilePurpose(
            code = CompanyLogos.PURPOSE,
            label = "Company logo (PNG, JPEG, WebP, AVIF or SVG, up to 1 MB)",
            kinds = setOf(FileKind.PNG, FileKind.JPEG, FileKind.WEBP, FileKind.AVIF, FileKind.SVG),
            maxBytes = CompanyLogos.MAX_BYTES,
            uploadPermission = "settings.manage",
        ),
    )
}
