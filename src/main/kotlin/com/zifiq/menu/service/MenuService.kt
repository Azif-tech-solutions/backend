package com.zifiq.menu.service

import com.zifiq.menu.dto.*
import com.zifiq.platform.identity.UserDirectory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class MenuService(
    private val userDirectory: UserDirectory,
) {
    fun registerDealer(request: DealerRegistrationRequest): DealerRegistrationSummary {
        return DealerRegistrationSummary(
            id = UUID.randomUUID(),
            dealerCode = "DLR-" + UUID.randomUUID().toString().take(6).uppercase(),
            companyName = request.companyName,
            registeredAt = Instant.now(),
        )
    }

    fun getDebtorsList(): List<DebtorItem> {
        return listOf(
            DebtorItem(
                id = UUID.randomUUID(),
                debtorName = "Global Logistics Supply",
                debtorType = "DISTRIBUTOR",
                outstandingBalance = BigDecimal("14200.00"),
                dueDate = Instant.now().minusSeconds(86400 * 5),
                daysOverdue = 5,
            ),
        )
    }
}
