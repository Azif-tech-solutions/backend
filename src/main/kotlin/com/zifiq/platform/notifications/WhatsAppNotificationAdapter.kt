package com.zifiq.platform.notifications

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class WhatsAppNotificationAdapter : NotificationChannel {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: NotificationChannelType = NotificationChannelType.WHATSAPP

    override fun send(message: NotificationMessage) {
        logger.info("Sending WHATSAPP notification to recipient={}: body={}", message.recipient, message.body)
        // Meta Business API / Twilio WhatsApp integration point
    }
}
