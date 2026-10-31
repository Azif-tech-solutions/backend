package com.zifiq.inventory.codes

/**
 * GS1 mod-10 check digits for EAN-13 / EAN-8 / UPC-A (docs/11 §4.1). The weighting alternates 1 and 3
 * from the right across the data digits; the check digit makes the weighted total a multiple of ten.
 */
object CheckDigits {
    /** The mod-10 check digit for [dataDigits] (all but the final digit of a GTIN), weighting from the right. */
    fun gs1CheckDigit(dataDigits: String): Int {
        require(dataDigits.all { it.isDigit() }) { "digits only" }
        var sum = 0
        // The right-most data digit carries weight 3, then 1, 3, 1, ... leftward.
        for ((i, c) in dataDigits.reversed().withIndex()) {
            val weight = if (i % 2 == 0) 3 else 1
            sum += (c - '0') * weight
        }
        return (10 - sum % 10) % 10
    }

    /** True when [code] is all digits, of an EAN/UPC length (8/12/13), and its last digit is the correct check digit. */
    fun isValidGtin(code: String): Boolean {
        if (code.length !in setOf(8, 12, 13) || !code.all { it.isDigit() }) return false
        return gs1CheckDigit(code.dropLast(1)) == (code.last() - '0')
    }

    /** [dataDigits] with its check digit appended. */
    fun withCheckDigit(dataDigits: String): String = dataDigits + gs1CheckDigit(dataDigits)
}
