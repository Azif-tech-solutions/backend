package com.zifiq.common.search

import java.text.Normalizer

data class Match(val field: String, val snippet: String)

/**
 * Server-side highlighting for `highlight=true` (docs/03 §4.3): wraps every accent- and
 * case-insensitive occurrence of the query's positive words in `<mark>…</mark>`. The text is
 * HTML-escaped first, so the snippet is safe to render as HTML.
 */
object Highlighter {
    private const val CONTEXT = 40

    fun matches(query: ParsedQuery, fields: Map<String, String?>): List<Match> {
        val terms = query.clauses.flatten().filterIsInstance<TextTerm>().filterNot { it.negated }
            .map { it.text.trim() }.filter { it.length >= 2 }
        if (terms.isEmpty()) return emptyList()
        return fields.mapNotNull { (field, value) -> value?.let { snippet(it, terms) }?.let { Match(field, it) } }
    }

    private fun snippet(text: String, terms: List<String>): String? {
        val folded = fold(text)
        val ranges = terms.flatMap { term ->
            val needle = fold(term)
            generateSequence(folded.indexOf(needle)) { from -> folded.indexOf(needle, from + 1).takeIf { it >= 0 } }
                .takeWhile { it >= 0 }
                .map { it until it + needle.length }
                .toList()
        }.sortedBy { it.first }
        if (ranges.isEmpty()) return null

        val merged = mutableListOf<IntRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            else merged += range
        }
        val start = (merged.first().first - CONTEXT).coerceAtLeast(0)
        val end = (merged.last().last + 1 + CONTEXT).coerceAtMost(text.length)
        val out = StringBuilder()
        if (start > 0) out.append("…")
        var cursor = start
        for (range in merged) {
            if (range.first < start || range.last >= end) continue
            out.append(escape(text.substring(cursor, range.first)))
            out.append("<mark>").append(escape(text.substring(range.first, range.last + 1))).append("</mark>")
            cursor = range.last + 1
        }
        out.append(escape(text.substring(cursor, end)))
        if (end < text.length) out.append("…")
        return out.toString()
    }

    /** Lower-cases and strips accents character by character, so indexes line up with the original text. */
    private fun fold(text: String): String = buildString(text.length) {
        for (ch in text) {
            val stripped = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            append(if (stripped.length == 1) stripped.lowercase() else ch.lowercase())
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
