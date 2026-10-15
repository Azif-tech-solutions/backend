package com.zifiq.platform.notifications

import com.zifiq.platform.documents.AttachmentResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class EmailNotificationAdapter(
    private val attachmentResolvers: List<AttachmentResolver> = emptyList(),
) : NotificationChannel {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: NotificationChannelType = NotificationChannelType.EMAIL

    override fun send(message: NotificationMessage) {
        // The attachment is rendered at send time from the reference in the metadata (docs/08 §2.6); a
        // failure here fails the send, so the dispatcher retries it like any other delivery error.
        val attachment = message.metadata[AttachmentResolver.METADATA_KEY]?.let { kind ->
            val resolver = attachmentResolvers.firstOrNull { it.kind == kind }
                ?: throw IllegalStateException("No attachment resolver is registered for '$kind'")
            resolver.resolve(message.tenantId, message.metadata) ?: throw IllegalStateException("Attachment '$kind' could not be rendered")
        }
        logger.info("Sending EMAIL notification to recipient={}: subject={}{}", message.recipient, message.subject,
            attachment?.let { ", attachment=${it.fileName} (${it.bytes.size} bytes)" } ?: "")
        // Provider SDK logic (SMTP/SendGrid/SES) integrates here
    }
}
