package com.zifiq.menu.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class DebtorItem(
    val id: UUID,
    val debtorName: String,
    val debtorType: String, // DEALER, DISTRIBUTOR, CUSTOMER
    val outstandingBalance: BigDecimal,
    val dueDate: Instant,
    val daysOverdue: Int,
)
