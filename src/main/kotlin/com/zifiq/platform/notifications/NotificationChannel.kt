package com.zifiq.platform.notifications

import java.util.UUID
import org.springframework.stereotype.Component

enum class NotificationChannelType {
    PUSH,
    EMAIL,
    WHATSAPP,
    TELEGRAM,
}

data class NotificationMessage(
    val tenantId: UUID,
    val recipient: String,
    val subject: String?,
    val body: String,
    val metadata: Map<String, String> = emptyMap(),
)

interface NotificationChannel {
    val type: NotificationChannelType

    fun send(message: NotificationMessage)
}

@Component
class NotificationDispatcher(channels: List<NotificationChannel>) {
    private val channelsByType = channels.associateBy(NotificationChannel::type)

    fun send(channel: NotificationChannelType, message: NotificationMessage) {
        val provider = channelsByType[channel]
            ?: throw IllegalStateException("No provider is configured for notification channel $channel")
        provider.send(message)
    }
}
