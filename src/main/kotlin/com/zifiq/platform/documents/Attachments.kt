package com.zifiq.platform.documents

import java.util.UUID

/** A rendered file to attach to an e-mail. */
class DocumentAttachment(val fileName: String, val contentType: String, val bytes: ByteArray)

/**
 * Renders the file an outbox e-mail refers to (docs/08 §2.6). An outbox row carries only a
 * reference in its metadata (`attachment` = [kind], plus the ids the resolver needs), never the
 * bytes, so a retry renders the identical, immutable document again. Each issuing module registers
 * one per kind; platform must not depend on business modules. Called by the e-mail adapter inside the
 * dispatcher's tenant-scoped transaction.
 */
interface AttachmentResolver {
    val kind: String
    fun resolve(tenantId: UUID, metadata: Map<String, String>): DocumentAttachment?

    companion object {
        /** Metadata key naming the attachment kind. */
        const val METADATA_KEY = "attachment"
    }
}
