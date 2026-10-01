package com.zifiq.platform.notifications

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TelegramNotificationAdapter : NotificationChannel {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: NotificationChannelType = NotificationChannelType.TELEGRAM

    override fun send(message: NotificationMessage) {
        logger.info("Sending TELEGRAM notification to recipient={}: body={}", message.recipient, message.body)
        // Telegram Bot API integration point
    }
}
