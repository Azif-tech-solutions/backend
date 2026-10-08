package com.zifiq.common.filter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.phone.PhoneNumbers
import com.zifiq.platform.api.ApiException
import java.time.LocalDate
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.junit.jupiter.api.Test

/** The parts of the framework that need no database: validation, presets, phone matching. */
class FilterSpecUnitTest {
    private object Items : Table("items") {
        val id = uuid("id")
        val status = varchar("status", 20)
        val total = decimal("total", 14, 2)
        val unitCost = decimal("unit_cost", 14, 2)
    }

    private val spec = FilterSpec(
        fields = listOf(
            FilterField("status", "Status", FilterType.ENUM, Items.status, options = listOf(FilterOption("OVERDUE", "Overdue"), FilterOption("PAID", "Paid")), sortable = true),
            FilterField("total", "Total", FilterType.MONEY, Items.total, sortable = true),
            FilterField("unitCost", "Unit cost", FilterType.MONEY, Items.unitCost, sortable = true, cost = true),
        ),
        defaultSort = listOf("total" to SortOrder.DESC),
        tiebreaker = Items.id,
    )
    private val zone = ZoneId.of("Africa/Lagos")
    private val noCost = FilterContext(costVisible = false, zone = zone, today = LocalDate.of(2026, 10, 8))
    private val withCost = noCost.copy(costVisible = true)

    private fun code(block: () -> Unit) =
        assertThatThrownBy(block).isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    @Test
    fun `unknown filter is rejected, not ignored`() {
        code { spec.fromParams(mapOf("staus" to listOf("PAID")), withCost) }.isEqualTo(ErrorCode.INVALID_FILTER)
    }

    @Test
    fun `cost filters and sorts are invisible without cost visibility`() {
        code { spec.fromParams(mapOf("unitCost.gte" to listOf("100")), noCost) }.isEqualTo(ErrorCode.INVALID_FILTER)
        code { spec.sort(listOf("unitCost,desc"), noCost) }.isEqualTo(ErrorCode.INVALID_SORT_FIELD)
        assertThat(spec.metadata(noCost).fields.map { it.key }).doesNotContain("unitCost")
        assertThat(spec.fromParams(mapOf("unitCost.gte" to listOf("100")), withCost)).isNotNull()
    }

    @Test
    fun `enum values are validated and accept labels`() {
        code { spec.fromParams(mapOf("status" to listOf("LOST")), withCost) }.isEqualTo(ErrorCode.INVALID_FILTER)
        assertThat(spec.fromParams(mapOf("status" to listOf("overdue")), withCost)).isNotNull()
    }

    @Test
    fun `bad numbers, operators and ranges are rejected`() {
        code { spec.fromParams(mapOf("total.gte" to listOf("lots")), withCost) }.isEqualTo(ErrorCode.INVALID_FILTER)
        code { spec.fromParams(mapOf("total.contains" to listOf("5")), withCost) }.isEqualTo(ErrorCode.INVALID_FILTER)
        code { spec.fromParams(mapOf("from" to listOf("2026-10-01")), withCost) }.isEqualTo(ErrorCode.INVALID_FILTER) // no date dimension
    }

    @Test
    fun `advanced filter limits are enforced`() {
        val mapper = jacksonObjectMapper()
        val deep = mapper.readTree("""{"all":[{"all":[{"all":[{"all":[{"all":[{"field":"total","op":"gt","value":1}]}]}]}]}]}""")
        code { spec.fromJson(deep, withCost) }.isEqualTo(ErrorCode.FILTER_TOO_COMPLEX)

        val wide = mapper.readTree("""{"any":[${(1..31).joinToString(",") { """{"field":"total","op":"gt","value":$it}""" }}]}""")
        code { spec.fromJson(wide, withCost) }.isEqualTo(ErrorCode.FILTER_TOO_COMPLEX)

        val badOp = mapper.readTree("""{"field":"total","op":"like","value":1}""")
        code { spec.fromJson(badOp, withCost) }.isEqualTo(ErrorCode.INVALID_FILTER)
    }

    @Test
    fun `sort appends the tiebreaker and falls back to the default`() {
        assertThat(spec.sort(emptyList(), withCost).map { it.second }).containsExactly(SortOrder.DESC, SortOrder.ASC)
        assertThat(spec.sort(listOf("status,asc"), withCost)).hasSize(2)
        code { spec.sort(listOf("status,sideways"), withCost) }.isEqualTo(ErrorCode.INVALID_SORT_FIELD)
    }

    @Test
    fun `period presets resolve in calendar terms with Monday weeks`() {
        val thursday = LocalDate.of(2026, 10, 8)
        assertThat(DatePeriod.THIS_WEEK.resolve(thursday)).isEqualTo(DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)))
        assertThat(DatePeriod.LAST_MONTH.resolve(thursday)).isEqualTo(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
        assertThat(DatePeriod.THIS_QUARTER.resolve(thursday)).isEqualTo(DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)))
        assertThat(DatePeriod.LAST_QUARTER.resolve(thursday)).isEqualTo(DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30)))
        assertThat(DatePeriod.LAST_7_DAYS.resolve(thursday)).isEqualTo(DateRange(LocalDate.of(2026, 10, 2), thursday))
        assertThat(DatePeriod.parse("year_to_date")).isEqualTo(DatePeriod.YEAR_TO_DATE)
    }

    @Test
    fun `phone numbers match whether written locally or internationally`() {
        assertThat(PhoneNumbers.normalise("0803 123 4567")).isEqualTo("+2348031234567")
        assertThat(PhoneNumbers.normalise("234-803-123-4567")).isEqualTo("+2348031234567")
        assertThat(PhoneNumbers.matchKey("0803 123 4567")).isEqualTo(PhoneNumbers.matchKey("+2348031234567"))
        assertThat(PhoneNumbers.looksLikePhone("0803 123 4567")).isTrue()
        assertThat(PhoneNumbers.looksLikePhone("INV-4031")).isFalse()
    }
}
