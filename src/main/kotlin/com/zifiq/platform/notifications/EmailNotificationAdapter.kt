package com.zifiq.platform.notifications

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class EmailNotificationAdapter : NotificationChannel {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: NotificationChannelType = NotificationChannelType.EMAIL

    override fun send(message: NotificationMessage) {
        logger.info("Sending EMAIL notification to recipient={}: subject={}", message.recipient, message.subject)
        // Provider SDK logic (SMTP/SendGrid/SES) integrates here
    }
}
