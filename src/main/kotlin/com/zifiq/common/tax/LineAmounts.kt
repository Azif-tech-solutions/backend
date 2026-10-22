package com.zifiq.common.tax

import com.zifiq.common.money.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The money of one sold line — the same arithmetic on a POS sale (docs/09 §2.3), a pro-forma and
 * the invoice issued for them (docs/08 §3), so they agree to the kobo. Quantities are 3 decimals,
 * money 2, half-up: value = quantity × unit price; net = value − discount; VAT = net × rate.
 */
data class LineAmounts(
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val value: BigDecimal,
    val discount: BigDecimal,
    val net: BigDecimal,
    val vatRate: BigDecimal,
    val vat: BigDecimal,
    val total: BigDecimal,
) {
    companion object {
        /** VAT category code that carries the location's rate; zero-rated and exempt items never do. */
        const val STANDARD_RATE = "STANDARD_RATE"

        /** The rate a line pays: the location's while it collects VAT, for standard-rated items only. */
        fun vatRate(vatCategoryCode: String, vatEnabled: Boolean, locationRate: BigDecimal): BigDecimal =
            if (vatCategoryCode == STANDARD_RATE && vatEnabled) locationRate.setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)

        /** [discount] must already be validated as 0 ≤ discount ≤ value (callers raise their own error). */
        fun of(quantity: BigDecimal, unitPrice: BigDecimal, discount: BigDecimal, vatRate: BigDecimal): LineAmounts {
            val qty = quantity.setScale(3, RoundingMode.HALF_UP)
            val price = Money.round(unitPrice)
            val value = qty * price
            val disc = Money.round(discount)
            val net = Money.round(value - disc)
            val rate = vatRate.setScale(2, RoundingMode.HALF_UP)
            val vat = Money.round(net * rate / HUNDRED)
            return LineAmounts(qty, price, value, disc, net, rate, vat, net + vat)
        }

        /** Line value before any discount, for checking a discount against it. */
        fun value(quantity: BigDecimal, unitPrice: BigDecimal): BigDecimal =
            quantity.setScale(3, RoundingMode.HALF_UP) * Money.round(unitPrice)

        private val HUNDRED = BigDecimal(100)
    }
}
