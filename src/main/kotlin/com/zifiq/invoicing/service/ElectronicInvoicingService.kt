package com.zifiq.invoicing.service

import com.zifiq.invoicing.dto.*
import com.zifiq.platform.notifications.NotificationChannelType
import com.zifiq.platform.notifications.NotificationDispatcher
import com.zifiq.platform.notifications.NotificationMessage
import com.zifiq.platform.security.CurrentActorProvider
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class ElectronicInvoicingService(
    private val notificationDispatcher: NotificationDispatcher,
    private val currentActorProvider: CurrentActorProvider,
) {
    fun generateInvoice(request: InvoiceGenerationRequest): ElectronicInvoiceSummary {
        val invoiceId = UUID.randomUUID()
        val invoiceNum = "EINV-" + UUID.randomUUID().toString().take(8).uppercase()

        return ElectronicInvoiceSummary(
            invoiceId = invoiceId,
            invoiceNumber = invoiceNum,
            customerName = request.customerName,
            totalAmount = BigDecimal("1250.00"),
            taxAmount = BigDecimal("93.75"),
            status = "UNPAID",
            pdfUrl = "/api/v1/invoicing/pdf/$invoiceId",
            createdAt = Instant.now(),
        )
    }

    fun deliverInvoice(request: InvoiceDeliveryRequest): InvoiceDeliverySummary {
        val actor = currentActorProvider.current()
        notificationDispatcher.send(
            channel = NotificationChannelType.EMAIL,
            message = NotificationMessage(
                tenantId = actor.tenantId,
                recipient = request.recipientEmail,
                subject = "Tax Invoice Delivery",
                body = "Please find attached your Electronic Invoice for recent transactions.",
            ),
        )

        return InvoiceDeliverySummary(
            invoiceId = request.invoiceId,
            deliveryStatus = "SENT",
            dispatchedAt = Instant.now(),
        )
    }

    fun getInvoiceHistory(): List<ElectronicInvoiceSummary> {
        return listOf(
            ElectronicInvoiceSummary(
                invoiceId = UUID.randomUUID(),
                invoiceNumber = "EINV-88391A",
                customerName = "Apex Mega Wholesale",
                totalAmount = BigDecimal("4500.00"),
                taxAmount = BigDecimal("337.50"),
                status = "PAID",
                pdfUrl = "/api/v1/invoicing/pdf/sample",
                createdAt = Instant.now().minusSeconds(86400),
            ),
        )
    }
}
