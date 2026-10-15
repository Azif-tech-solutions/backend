package com.zifiq.factory.dto

import com.zifiq.platform.directory.Ref
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ---- Factory-to-Factory Trade ----

data class FactoryTradeOfferRequest(
    @field:NotNull val fromLocationId: UUID,
    @field:NotNull val toLocationId: UUID,
    @field:NotNull val stockItemId: UUID,
    @field:DecimalMin("0.0", inclusive = false) val quantity: BigDecimal,
    @field:Pattern(regexp = "SURPLUS|EXPIRY|CHANGEOVER|SCHEDULE|DISCONTINUED") val reason: String,
    @field:NotNull val expiresAt: Instant,
)

data class FactoryTradeRespondRequest(
    @field:Pattern(regexp = "ACCEPTED|REJECTED") val decision: String,
)

data class FactoryTradeOfferSummary(
    val id: UUID,
    val ref: String,
    val fromLocation: Ref,
    val toLocation: Ref,
    val stockItem: Ref,
    val quantity: BigDecimal,
    val reason: String,
    val status: String,
    val offeredAt: Instant,
    val expiresAt: Instant,
)
