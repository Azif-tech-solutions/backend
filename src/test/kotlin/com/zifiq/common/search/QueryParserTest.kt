package com.zifiq.common.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryParserTest {
    private val qualifiers = mapOf("status" to "status", "st" to "status", "total" to "total", "customer" to "customer", "cost" to "cost")

    @Test
    fun `blank input parses to empty`() {
        assertThat(QueryParser.parse(null).isEmpty).isTrue()
        assertThat(QueryParser.parse("   ").isEmpty).isTrue()
    }

    @Test
    fun `words become AND clauses`() {
        val parsed = QueryParser.parse("citrus 50cl")
        assertThat(parsed.clauses).hasSize(2)
        assertThat(parsed.clauses.map { (it.single() as TextTerm).text }).containsExactly("citrus", "50cl")
    }

    @Test
    fun `quoted phrase and exclusion`() {
        val parsed = QueryParser.parse("\"okafor & sons\" -classic -\"ginger blend\"")
        val terms = parsed.clauses.map { it.single() as TextTerm }
        assertThat(terms[0]).isEqualTo(TextTerm("okafor & sons", phrase = true, negated = false))
        assertThat(terms[1]).isEqualTo(TextTerm("classic", phrase = false, negated = true))
        assertThat(terms[2]).isEqualTo(TextTerm("ginger blend", phrase = true, negated = true))
    }

    @Test
    fun `OR joins neighbours into one clause`() {
        val parsed = QueryParser.parse("malt ikeja OR lekki")
        assertThat(parsed.clauses).hasSize(2)
        assertThat(parsed.clauses[1].map { (it as TextTerm).text }).containsExactly("ikeja", "lekki")
    }

    @Test
    fun `known qualifiers become filters, aliases resolve, ranges parse`() {
        val parsed = QueryParser.parse("st:overdue total:>=500000 customer:\"okafor & sons\" total:10..20", qualifiers)
        val terms = parsed.clauses.map { it.single() as QualifierTerm }
        assertThat(terms[0]).isEqualTo(QualifierTerm("status", QualifierOperator.EQ, listOf("overdue"), false))
        assertThat(terms[1]).isEqualTo(QualifierTerm("total", QualifierOperator.GTE, listOf("500000"), false))
        assertThat(terms[2]).isEqualTo(QualifierTerm("customer", QualifierOperator.EQ, listOf("okafor & sons"), false))
        assertThat(terms[3]).isEqualTo(QualifierTerm("total", QualifierOperator.RANGE, listOf("10", "20"), false))
    }

    @Test
    fun `unknown qualifier is plain text so a typo still searches`() {
        val term = QueryParser.parse("staus:overdue", qualifiers).clauses.single().single()
        assertThat(term).isEqualTo(TextTerm("staus:overdue", phrase = false, negated = false))
    }

    @Test
    fun `forbidden qualifier is dropped and reported`() {
        val parsed = QueryParser.parse("cost:>5000 citrus", qualifiers, forbiddenQualifiers = setOf("cost"))
        assertThat(parsed.ignoredQualifiers).containsExactly("cost")
        assertThat(parsed.clauses.flatten()).containsExactly(TextTerm("citrus", false, false))
    }

    @Test
    fun `input is capped at the maximum length`() {
        val parsed = QueryParser.parse("a".repeat(500))
        assertThat((parsed.clauses.single().single() as TextTerm).text).hasSize(QueryParser.MAX_LENGTH)
    }

    @Test
    fun `spaced phone numbers stay one term, separate numbers do not merge`() {
        assertThat(QueryParser.parse("0803 123 4567").clauses.flatten()).containsExactly(TextTerm("0803 123 4567", false, false))
        assertThat(QueryParser.parse("+234 803 123 4567 okafor").clauses.flatten())
            .containsExactly(TextTerm("+234 803 123 4567", false, false), TextTerm("okafor", false, false))
        assertThat(QueryParser.parse("2026 500").clauses).hasSize(2)
    }

    @Test
    fun `multi-value qualifier splits on commas`() {
        val term = QueryParser.parse("status:issued,overdue", qualifiers).clauses.single().single() as QualifierTerm
        assertThat(term.values).containsExactly("issued", "overdue")
    }
}
