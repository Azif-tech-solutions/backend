package com.zifiq.platform.files

/**
 * The kinds of file that may be uploaded, recognised from their first bytes (magic numbers) rather
 * than the client's file name or Content-Type, which are trivially spoofed.
 */
enum class FileKind(val contentType: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
    CSV("text/csv", "csv"),
    TEXT("text/plain", "txt"),
}

object FileTypeDetector {
    /** Null when the bytes aren't one of the accepted kinds (e.g. an executable or an archive). */
    fun detect(bytes: ByteArray, fileName: String): FileKind? {
        fun startsWith(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all { bytes[it].toInt() and 0xFF == signature[it] }
        return when {
            startsWith(0x25, 0x50, 0x44, 0x46, 0x2D) -> FileKind.PDF                                      // %PDF-
            startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> FileKind.PNG
            startsWith(0xFF, 0xD8, 0xFF) -> FileKind.JPEG
            bytes.size >= 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> FileKind.WEBP
            isText(bytes) -> if (fileName.lowercase().endsWith(".csv")) FileKind.CSV else FileKind.TEXT
            else -> null
        }
    }

    /** UTF-8 text with no control characters other than tab/CR/LF in the first 8 KB. */
    private fun isText(bytes: ByteArray): Boolean {
        val sample = bytes.copyOf(minOf(bytes.size, 8192))
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(sample)).toString() }
            // A multi-byte character cut at the 8 KB boundary is still text.
            .getOrElse { if (bytes.size > sample.size) String(sample, Charsets.UTF_8).dropLast(1) else return false }
        return text.none { it.code < 32 && it != '\t' && it != '\n' && it != '\r' }
    }
}

/**
 * What an upload is for, registered by the owning module (like module settings): which kinds are
 * accepted, how big, and who may upload and read. A null permission means any member of the tenant.
 */
data class FilePurpose(
    val code: String,
    val label: String,
    val kinds: Set<FileKind>,
    val maxBytes: Long,
    val uploadPermission: String? = null,
    val readPermission: String? = null,
)

/** Implemented by each module (a Spring bean) that accepts uploads. */
interface FilePurposeProvider {
    fun purposes(): List<FilePurpose>
}

@org.springframework.stereotype.Component
class PlatformFilePurposes : FilePurposeProvider {
    override fun purposes() = listOf(
        FilePurpose(
            code = GENERAL_ATTACHMENT,
            label = "Attachment (PDF, image or CSV, up to 10 MB)",
            kinds = setOf(FileKind.PDF, FileKind.PNG, FileKind.JPEG, FileKind.WEBP, FileKind.CSV, FileKind.TEXT),
            maxBytes = 10L * 1024 * 1024,
        ),
    )

    companion object {
        const val GENERAL_ATTACHMENT = "general.attachment"
    }
}
