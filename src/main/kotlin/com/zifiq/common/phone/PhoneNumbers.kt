package com.zifiq.common.phone

/**
 * One phone normalisation for the whole system (docs/implemented/04-customers-distributors.md §4.8): stored
 * values and searched values go through the same rules, so "0803 123 4567", "+2348031234567" and
 * "234-803-123-4567" are the same number.
 */
object PhoneNumbers {
    /** Digits only, Nigerian local form (`0` + 10 digits) and `234…` both converted to `+234…`. */
    fun normalise(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return when {
            digits.length == 11 && digits.startsWith("0") -> "+234" + digits.drop(1)
            digits.length == 13 && digits.startsWith("234") -> "+$digits"
            trimmed.startsWith("+") -> "+$digits"
            else -> digits
        }
    }

    /** True when [raw] looks like a phone number a user typed into a search box (≥ 7 digits, phone punctuation only). */
    fun looksLikePhone(raw: String): Boolean {
        val digits = raw.count { it.isDigit() }
        return digits >= 7 && raw.all { it.isDigit() || it in "+-() ." }
    }

    /**
     * The trailing subscriber digits used for matching: the last 9 digits are identical whether a
     * number was written locally (`0803…`) or internationally (`+234803…`).
     */
    fun matchKey(raw: String): String = raw.filter { it.isDigit() }.takeLast(9)
}
