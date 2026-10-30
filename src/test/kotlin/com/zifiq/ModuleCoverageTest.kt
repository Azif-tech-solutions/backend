package com.zifiq

import com.zifiq.ai.dto.AssistantQueryRequest
import com.zifiq.ai.service.AiOperationsService
import com.zifiq.barcode.dto.BarcodeScanRequest
import com.zifiq.barcode.service.BarcodeQrService
import com.zifiq.platform.accesscontrol.RoleAssignmentService
import com.zifiq.platform.notifications.EmailNotificationAdapter
import com.zifiq.platform.notifications.NotificationMessage
import com.zifiq.platform.notifications.PushNotificationAdapter
import com.zifiq.platform.notifications.TelegramNotificationAdapter
import com.zifiq.platform.notifications.WhatsAppNotificationAdapter
import com.zifiq.process.service.ProcessGuideService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID

class ModuleCoverageTest {

    @Test
    fun `ai operations service provides assistant answers and forecasts`() {
        val service = AiOperationsService()

        val assistant = service.askAssistant(AssistantQueryRequest("What is current stock?", "factory"))
        assertTrue(assistant.answer.contains("Premium Blend"))

        val forecasts = service.getReorderForecasts()
        assertFalse(forecasts.isEmpty())

        val briefing = service.getExecutiveBriefing()
        assertNotNull(briefing.headlineSummary)
    }

    @Test
    fun `barcode service scans product codes`() {
        val service = BarcodeQrService()
        val result = service.scanCode(BarcodeScanRequest("P-1001", "LOC-01"))
        assertEquals("P-1001", result.productCode)
    }

    @Test
    fun `process guide service lists commission rules`() {
        val service = ProcessGuideService()
        assertFalse(service.getCommissionRules().isEmpty())
    }

    @Test
    fun `all notification channel adapters handle message delivery without error`() {
        val msg = NotificationMessage(
            tenantId = UUID.randomUUID(),
            recipient = "user@test.com",
            subject = "Test",
            body = "Notification message"
        )

        assertDoesNotThrow { EmailNotificationAdapter().send(msg) }
        assertDoesNotThrow { PushNotificationAdapter().send(msg) }
        assertDoesNotThrow { WhatsAppNotificationAdapter().send(msg) }
        assertDoesNotThrow { TelegramNotificationAdapter().send(msg) }
    }
}
