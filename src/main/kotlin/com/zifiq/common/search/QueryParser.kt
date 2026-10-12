package com.zifiq.common.search

/**
 * Parses the free-text `q` parameter into a small AST before anything touches SQL — see
 * docs/implemented/03-cross-module-conventions.md §4.3. Supported syntax:
 *
 * - words: every word must match (AND)
 * - `"exact phrase"`
 * - `-word` / `-"phrase"`: exclude
 * - `a OR b`: either side
 * - `field:value`, `field:a,b`, `field:>n`, `field:>=n`, `field:<n`, `field:<=n`, `field:a..b`:
 *   a qualifier, applied as a filter on a whitelisted field rather than as free text
 *
 * Qualifier names are resolved through [qualifiers] (alias -> canonical key). A token whose prefix
 * isn't a known qualifier is searched as plain text, so a typo still searches. Qualifiers on fields
 * the caller may not filter ([forbiddenQualifiers], e.g. cost fields) are dropped and reported in
 * [ParsedQuery.ignoredQualifiers].
 */
object QueryParser {
    const val MAX_LENGTH = 200

    fun parse(
        raw: String?,
        qualifiers: Map<String, String> = emptyMap(),
        forbiddenQualifiers: Set<String> = emptySet(),
    ): ParsedQuery {
        val input = raw?.trim()?.take(MAX_LENGTH).orEmpty()
        if (input.isEmpty()) return ParsedQuery.EMPTY

        val lookup = qualifiers.mapKeys { it.key.lowercase() }
        val ignored = mutableListOf<String>()
        val clauses = mutableListOf<MutableList<QueryTerm>>()
        var joinWithPrevious = false

        for (token in mergePhoneGroups(tokenize(input))) {
            if (!token.quoted && !token.negated && token.text == "OR") {
                joinWithPrevious = clauses.isNotEmpty()
                continue
            }
            val term = toTerm(token, lookup, forbiddenQualifiers, ignored) ?: continue
            if (joinWithPrevious) clauses.last().add(term) else clauses.add(mutableListOf(term))
            joinWithPrevious = false
        }
        return ParsedQuery(clauses, ignored)
    }

    private fun toTerm(
        token: RawToken,
        qualifiers: Map<String, String>,
        forbidden: Set<String>,
        ignored: MutableList<String>,
    ): QueryTerm? {
        if (!token.quoted) {
            val colon = token.text.indexOf(':')
            if (colon > 0 && colon < token.text.length - 1) {
                val name = token.text.substring(0, colon).lowercase()
                val canonical = qualifiers[name]
                if (canonical != null) {
                    if (canonical in forbidden) {
                        ignored += name
                        return null
                    }
                    return qualifier(canonical, token.text.substring(colon + 1).trim('"'), token.negated)
                }
            }
        }
        if (token.text.isBlank()) return null
        return TextTerm(token.text, phrase = token.quoted, negated = token.negated)
    }

    private fun qualifier(field: String, value: String, negated: Boolean): QueryTerm {
        fun of(op: QualifierOperator, values: List<String>) = QualifierTerm(field, op, values, negated)
        return when {
            value.startsWith(">=") -> of(QualifierOperator.GTE, listOf(value.drop(2)))
            value.startsWith("<=") -> of(QualifierOperator.LTE, listOf(value.drop(2)))
            value.startsWith(">") -> of(QualifierOperator.GT, listOf(value.drop(1)))
            value.startsWith("<") -> of(QualifierOperator.LT, listOf(value.drop(1)))
            value.contains("..") -> of(QualifierOperator.RANGE, value.split("..", limit = 2))
            else -> of(QualifierOperator.EQ, value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }
    }

    private data class RawToken(val text: String, val quoted: Boolean, val negated: Boolean)

    private val digitGroup = Regex("""^\+?\d{2,5}$""")

    /**
     * People type phone numbers in groups ("0803 123 4567", "+234 803 123 4567"). Splitting those
     * on spaces would AND three meaningless fragments, so a run of 2+ bare digit groups whose
     * digits add up to a phone length (10–13) is rejoined into one term.
     */
    private fun mergePhoneGroups(tokens: List<RawToken>): List<RawToken> {
        val result = mutableListOf<RawToken>()
        var i = 0
        while (i < tokens.size) {
            var end = i
            while (end < tokens.size && !tokens[end].quoted && !tokens[end].negated && digitGroup.matches(tokens[end].text) &&
                (end == i || !tokens[end].text.startsWith("+"))
            ) end++
            val run = tokens.subList(i, end)
            val digits = run.sumOf { token -> token.text.count { it.isDigit() } }
            if (run.size >= 2 && digits in 10..13) {
                result += RawToken(run.joinToString(" ") { it.text }, quoted = false, negated = false)
                i = end
            } else {
                result += tokens[i]
                i++
            }
        }
        return result
    }

    private fun tokenize(input: String): List<RawToken> {
        val tokens = mutableListOf<RawToken>()
        var i = 0
        while (i < input.length) {
            if (input[i].isWhitespace()) { i++; continue }

            var negated = false
            if (input[i] == '-' && i + 1 < input.length && !input[i + 1].isWhitespace()) {
                negated = true
                i++
            }

            if (input[i] == '"') {
                val end = input.indexOf('"', i + 1).let { if (it < 0) input.length else it }
                tokens += RawToken(input.substring(i + 1, end).trim(), quoted = true, negated = negated)
                i = end + 1
                continue
            }

            // A bare word; a qualifier value may itself be quoted (`customer:"okafor & sons"`).
            val start = i
            var inQuote = false
            while (i < input.length && (inQuote || !input[i].isWhitespace())) {
                if (input[i] == '"') inQuote = !inQuote
                i++
            }
            tokens += RawToken(input.substring(start, i), quoted = false, negated = negated)
        }
        return tokens.filter { it.text.isNotEmpty() }
    }
}

sealed interface QueryTerm {
    val negated: Boolean
}

data class TextTerm(val text: String, val phrase: Boolean, override val negated: Boolean) : QueryTerm

data class QualifierTerm(
    val field: String,
    val operator: QualifierOperator,
    val values: List<String>,
    override val negated: Boolean,
) : QueryTerm

enum class QualifierOperator { EQ, GT, GTE, LT, LTE, RANGE }

/** AND of [clauses]; each clause is an OR of its terms. */
data class ParsedQuery(
    val clauses: List<List<QueryTerm>>,
    val ignoredQualifiers: List<String>,
) {
    val isEmpty: Boolean get() = clauses.isEmpty()

    /** The positive free-text words/phrases, joined — used for ranking. */
    val rankingText: String
        get() = clauses.flatten().filterIsInstance<TextTerm>().filterNot { it.negated }.joinToString(" ") { it.text }

    companion object {
        val EMPTY = ParsedQuery(emptyList(), emptyList())
    }
}
