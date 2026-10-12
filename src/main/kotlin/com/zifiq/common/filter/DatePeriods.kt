package com.zifiq.common.filter

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** An inclusive date range in the tenant's timezone. */
data class DateRange(val from: LocalDate?, val to: LocalDate?)

/**
 * Named relative periods (`period=LAST_MONTH`), resolved against "today" in the tenant's timezone —
 * docs/implemented/03-cross-module-conventions.md §5.2. Weeks start on Monday. Calendar periods (week, month,
 * quarter, year) cover the whole period; `…_TO_DATE` and `LAST_n_DAYS` end today.
 */
enum class DatePeriod {
    TODAY, YESTERDAY,
    THIS_WEEK, LAST_WEEK,
    THIS_MONTH, LAST_MONTH,
    THIS_QUARTER, LAST_QUARTER,
    THIS_YEAR, LAST_YEAR,
    LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS,
    MONTH_TO_DATE, YEAR_TO_DATE;

    fun resolve(today: LocalDate): DateRange = when (this) {
        TODAY -> DateRange(today, today)
        YESTERDAY -> today.minusDays(1).let { DateRange(it, it) }
        THIS_WEEK -> week(today)
        LAST_WEEK -> week(today.minusWeeks(1))
        THIS_MONTH -> month(today)
        LAST_MONTH -> month(today.minusMonths(1))
        THIS_QUARTER -> quarter(today)
        LAST_QUARTER -> quarter(today.minusMonths(3))
        THIS_YEAR -> year(today)
        LAST_YEAR -> year(today.minusYears(1))
        LAST_7_DAYS -> DateRange(today.minusDays(6), today)
        LAST_30_DAYS -> DateRange(today.minusDays(29), today)
        LAST_90_DAYS -> DateRange(today.minusDays(89), today)
        MONTH_TO_DATE -> DateRange(today.withDayOfMonth(1), today)
        YEAR_TO_DATE -> DateRange(today.withDayOfYear(1), today)
    }

    private fun week(day: LocalDate): DateRange {
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return DateRange(monday, monday.plusDays(6))
    }

    private fun month(day: LocalDate) =
        DateRange(day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()))

    private fun quarter(day: LocalDate): DateRange {
        val firstMonth = ((day.monthValue - 1) / 3) * 3 + 1
        val start = LocalDate.of(day.year, firstMonth, 1)
        return DateRange(start, start.plusMonths(3).minusDays(1))
    }

    private fun year(day: LocalDate) = DateRange(day.withDayOfYear(1), day.with(TemporalAdjusters.lastDayOfYear()))

    companion object {
        fun parse(value: String): DatePeriod? = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}
