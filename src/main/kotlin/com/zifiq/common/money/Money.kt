package com.zifiq.common.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** The one place money is rounded and printed. */
object Money {
    /** Two decimals, half-up: how every amount is stored and compared. */
    fun round(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    /** `₦1,234.56` — always UK digit grouping, whatever the server's locale. */
    fun naira(value: BigDecimal): String = "₦" + String.format(Locale.UK, "%,.2f", value)
}
