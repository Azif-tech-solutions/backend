package com.zifiq

import com.zifiq.account.dto.OperatingCostPaymentRequest
import com.zifiq.account.service.AccountService
import com.zifiq.ai.dto.AssistantQueryRequest
import com.zifiq.ai.service.AiOperationsService
import com.zifiq.barcode.dto.BarcodeScanRequest
import com.zifiq.barcode.service.BarcodeQrService
import com.zifiq.cashier.dto.ProFormaInvoiceRequest
import com.zifiq.cashier.dto.SaleLineItem
import com.zifiq.cashier.service.CashierService
import com.zifiq.history.service.HistoryService
import com.zifiq.platform.accesscontrol.RoleAssignmentService
import com.zifiq.platform.identity.UserDirectory
import com.zifiq.platform.notifications.EmailNotificationAdapter
import com.zifiq.platform.notifications.NotificationMessage
import com.zifiq.platform.notifications.PushNotificationAdapter
import com.zifiq.platform.notifications.TelegramNotificationAdapter
import com.zifiq.platform.notifications.WhatsAppNotificationAdapter
import com.zifiq.process.service.ProcessGuideService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.util.UUID

class ModuleCoverageTest {

    @Test
    fun `account mock service still records operating costs until Finance slice 2 replaces it`() {
        val currentActorProvider = mock(com.zifiq.platform.security.CurrentActorProvider::class.java)
        val service = AccountService(currentActorProvider)

        val payment = service.recordOperatingCost(
            OperatingCostPaymentRequest(
                category = "Generator Fuel",
                amount = BigDecimal("250.00"),
                paymentMethod = "PETTY_CASH",
                description = "Emergency fuel refill",
            )
        )
        assertEquals("CONFIRMED", payment.status)
    }

    @Test
    fun `cashier service processes pro forma invoices`() {
        val userDirectory = mock(UserDirectory::class.java)
        val service = CashierService(userDirectory)

        val result = service.generateProForma(
            ProFormaInvoiceRequest(
                customerName = "Acme Corp",
                customerEmail = "acme@corp.com",
                items = listOf(
                    SaleLineItem("P-1001", "Premium Blend", 10.0, BigDecimal("25.00"))
                )
            )
        )

        assertEquals("Acme Corp", result.customerName)
        assertEquals(BigDecimal("250.00"), result.totalAmount)
    }

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
    fun `process guide service lists exchange rates`() {
        val service = ProcessGuideService()
        val rates = service.getExchangeRates()
        assertFalse(rates.isEmpty())
    }

    @Test
    fun `history service provides audit trails`() {
        val service = HistoryService()
        assertFalse(service.getWaybillHistory().isEmpty())
        assertFalse(service.getFraudViolations().isEmpty())
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
