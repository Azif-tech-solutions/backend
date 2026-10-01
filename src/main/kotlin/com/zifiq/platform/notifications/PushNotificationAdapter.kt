package com.zifiq.platform.notifications

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PushNotificationAdapter : NotificationChannel {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: NotificationChannelType = NotificationChannelType.PUSH

    override fun send(message: NotificationMessage) {
        logger.info("Sending PUSH notification to recipient={}: body={}", message.recipient, message.body)
        // FCM / APNs integration point
    }
}
