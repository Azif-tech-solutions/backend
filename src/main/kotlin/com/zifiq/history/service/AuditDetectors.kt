package com.zifiq.history.service

import com.zifiq.platform.events.DomainEventRecord
import com.zifiq.platform.events.EventConsumer
import java.math.BigDecimal
import org.springframework.stereotype.Component

/**
 * The audit detectors (docs/10 §4.4): an event consumer that watches other modules' events and
 * raises an **alert** (never a record — a person decides, PRD rule 13) when one looks worth a look.
 * Runs in the event's tenant transaction (docs/03 §7.6). Each alert is deduped by rule + source ref,
 * so the same trigger never alerts twice.
 *
 * Implemented here: TILL_VARIANCE, WITHDRAWAL_WITHOUT_OTP, WITHDRAWAL_FLAGGED and WALLET_REVERSAL —
 * the triggers fully determinable from the event payload. Still to come (need a cost lookup, a
 * windowed count, a cross-record check, or doc 16): LARGE_ADJUSTMENT, REPEATED_DECLINES,
 * SALE_VOID_AFTER_PAYMENT, DISCOUNT_ABOVE_CEILING_REJECTED_THEN_REAPPLIED, AI_ANOMALY.
 */
@Component
class AuditDetectors(
    private val support: HistorySupport,
    private val alerts: AuditAlertService,
) : EventConsumer {
    override val name = "history.audit-detector"
    override val eventTypes = setOf(
        "cashier.till.variance",
        "account.wallet.withdrawn",
        "account.wallet.withdrawal-flagged",
        "customers.wallet.entry-reversed",
        "customers.wallet.adjusted",
    )

    override fun handle(event: DomainEventRecord) {
        when (event.eventType) {
            "cashier.till.variance" -> {
                val variance = bd(event.payload["variance"]).abs()
                val threshold = support.settings().decimal(HistorySettings.TILL_VARIANCE_THRESHOLD)
                if (variance >= threshold) {
                    raise(event, "TILL_VARIANCE", amount = variance, "Till variance of ${variance.toPlainString()} on ${event.aggregateRef}",
                        linkType = "TILL_SESSION")
                }
            }
            "account.wallet.withdrawn" -> {
                val dealerInitiated = event.payload["initiatedBy"] == "DEALER"
                val otpVerified = event.payload["otpVerified"] == true
                if (dealerInitiated && !otpVerified) {
                    raise(event, "WITHDRAWAL_WITHOUT_OTP", amount = bd(event.payload["amount"]),
                        "Dealer-initiated withdrawal ${event.aggregateRef} without a verified OTP", linkType = "WALLET_WITHDRAWAL")
                }
            }
            "account.wallet.withdrawal-flagged" ->
                raise(event, "WITHDRAWAL_FLAGGED", amount = bd(event.payload["amount"]),
                    "Withdrawal ${event.aggregateRef} flagged for review", linkType = "WALLET_WITHDRAWAL")
            "customers.wallet.entry-reversed", "customers.wallet.adjusted" ->
                raise(event, "WALLET_REVERSAL", amount = bd(event.payload["amount"]),
                    "Dealer wallet ${if (event.eventType.endsWith("adjusted")) "adjusted" else "entry reversed"} — ${event.aggregateRef}", linkType = "CUSTOMER")
        }
    }

    private fun raise(event: DomainEventRecord, rule: String, amount: BigDecimal?, summary: String, linkType: String) {
        val evidence = buildMap<String, Any?> {
            put("eventId", event.id.toString())
            event.aggregateRef?.let { put("refs", listOf(mapOf("type" to linkType, "ref" to it))) }
            amount?.let { put("amount", it) }
        }
        alerts.raise(
            ruleCode = rule,
            subjectUserId = event.actorUserId,
            locationId = event.locationId,
            summary = summary,
            amount = amount,
            evidence = evidence,
            dedupeKey = "$rule:${event.aggregateRef ?: event.id}",
        )
    }

    private fun bd(value: Any?): BigDecimal = when (value) {
        null -> BigDecimal.ZERO
        is BigDecimal -> value
        is Number -> BigDecimal(value.toString())
        else -> value.toString().toBigDecimalOrNull() ?: BigDecimal.ZERO
    }
}
