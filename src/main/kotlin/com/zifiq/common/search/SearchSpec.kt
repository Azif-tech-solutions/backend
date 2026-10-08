package com.zifiq.common.search

import com.zifiq.common.phone.PhoneNumbers
import org.jetbrains.exposed.sql.DecimalColumnType
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.transactions.TransactionManager

/** How a field takes part in free-text search — docs/03-cross-module-conventions.md §4.3. */
enum class SearchKind {
    /** Short code: case-insensitive exact or prefix match. */
    CODE,

    /** Document reference (`INV-4031`): like CODE, plus a bare number matches the numeric suffix. */
    REFERENCE,

    /** Name-like text: accent-insensitive substring, plus typo-tolerant word similarity. Needs a gin_trgm_ops index on `f_unaccent(lower(col))`. */
    TRIGRAM,

    /** A `tsvector` column (generated `search_vector`), queried with `websearch_to_tsquery('simple', …)`. */
    FULLTEXT,

    /** A phone column; matched on the trailing subscriber digits ([PhoneNumbers.matchKey]). */
    PHONE,

    /** A money/quantity column; a numeric term matches it exactly (2 dp). */
    AMOUNT,
}

class SearchField(
    val expression: Expression<*>,
    val kind: SearchKind,
    val label: String,
)

/**
 * Turns a parsed `q` into one SQL condition plus ranking expressions, for one list endpoint. Each
 * endpoint declares its fields once; every module gets identical search behaviour.
 *
 * Must be called inside an Exposed transaction: trigram matching sets the transaction-local
 * `pg_trgm.word_similarity_threshold` (0.5) the first time it's needed.
 */
class SearchSpec(
    val fields: List<SearchField>,
    private val qualifiers: QualifierResolver? = null,
) {
    /** Parses [q] with this spec's qualifier names (aliases included) and cost-field exclusions. */
    fun parse(q: String?, costVisible: Boolean = true): ParsedQuery =
        QueryParser.parse(
            raw = q,
            qualifiers = qualifiers?.qualifierNames().orEmpty(),
            forbiddenQualifiers = if (costVisible) emptySet() else qualifiers?.costQualifiers().orEmpty(),
        )

    /** The WHERE condition for [query], or null when there is nothing to search. */
    fun condition(query: ParsedQuery): Op<Boolean>? =
        query.clauses
            .mapNotNull { clause -> clause.mapNotNull(::termCondition).anyOf() }
            .allOf()

    /**
     * Ranking for "best match first" when `q` is present and the client didn't pass a sort:
     * exact code, then code prefix, then trigram similarity, then full-text rank. The endpoint
     * appends its default sort and `id` after these.
     */
    fun rank(query: ParsedQuery): List<Pair<Expression<*>, SortOrder>> {
        val text = query.rankingText.trim()
        if (text.isEmpty()) return emptyList()

        val codes = fields.filter { it.kind == SearchKind.CODE || it.kind == SearchKind.REFERENCE }
        val trigrams = fields.filter { it.kind == SearchKind.TRIGRAM }
        val fullText = fields.filter { it.kind == SearchKind.FULLTEXT }
        val ranks = mutableListOf<Pair<Expression<*>, SortOrder>>()

        if (codes.isNotEmpty()) {
            ranks += caseRank(codes) { field -> +"lower(("; +field.expression; +")::text) = lower("; text(text); +")" } to SortOrder.ASC
            ranks += caseRank(codes) { field ->
                +"lower(("; +field.expression; +")::text) LIKE lower("; text(escapeLike(text) + "%"); +")"
            } to SortOrder.ASC
        }
        if (trigrams.isNotEmpty()) {
            ranks += RawExpression<Double>({
                +"greatest("
                trigrams.forEachIndexed { index, field ->
                    if (index > 0) +", "
                    +"coalesce(word_similarity(f_unaccent(lower("; text(text); +")), f_unaccent(lower("; +field.expression; +"))), 0)"
                }
                +")"
            }) to SortOrder.DESC
        }
        if (fullText.isNotEmpty()) {
            ranks += RawExpression<Double>({
                fullText.forEachIndexed { index, field ->
                    if (index > 0) +" + "
                    +"ts_rank_cd("; +field.expression; +", websearch_to_tsquery('simple', f_unaccent("; text(text); +")))"
                }
            }) to SortOrder.DESC
        }
        return ranks
    }

    private fun caseRank(fields: List<SearchField>, test: QueryBuilder.(SearchField) -> Unit) =
        RawExpression<Int>({
            +"CASE WHEN "
            fields.forEachIndexed { index, field ->
                if (index > 0) +" OR "
                test(field)
            }
            +" THEN 0 ELSE 1 END"
        })

    private fun termCondition(term: QueryTerm): Op<Boolean>? {
        val op = when (term) {
            is TextTerm -> textCondition(term.text, term.phrase)
            is QualifierTerm -> qualifiers?.qualifierCondition(term)
        } ?: return null
        return if (term.negated) NotOp(op) else op
    }

    private fun textCondition(rawText: String, phrase: Boolean): Op<Boolean>? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        return fields.mapNotNull { field -> fieldCondition(field, text, phrase) }.anyOf()
    }

    private fun fieldCondition(field: SearchField, text: String, phrase: Boolean): Op<Boolean>? {
        val expr = field.expression
        return when (field.kind) {
            SearchKind.CODE, SearchKind.REFERENCE -> {
                val prefix = RawOp {
                    +"lower(("; +expr; +")::text) LIKE lower("; text(escapeLike(text) + "%"); +")"
                }
                if (text.length == 1) return prefix
                val exact = RawOp { +"lower(("; +expr; +")::text) = lower("; text(text); +")" }
                val suffix = if (field.kind == SearchKind.REFERENCE) referenceSuffix(expr, text) else null
                listOfNotNull(exact, prefix, suffix).anyOf()
            }
            SearchKind.TRIGRAM -> {
                if (text.length < 2) return null
                val contains = RawOp {
                    +"f_unaccent(lower("; +expr; +")) LIKE '%' || f_unaccent(lower("; text(escapeLike(text)); +")) || '%'"
                }
                if (phrase || text.length < 4) return contains
                enableWordSimilarityThreshold()
                val similar = RawOp { +"f_unaccent(lower("; text(text); +")) <% f_unaccent(lower("; +expr; +"))" }
                listOf(contains, similar).anyOf()
            }
            SearchKind.FULLTEXT -> {
                if (text.length < 2) return null
                val tsQuery = if (phrase) "\"$text\"" else text
                RawOp { +"("; +expr; +") @@ websearch_to_tsquery('simple', f_unaccent("; text(tsQuery); +"))" }
            }
            SearchKind.PHONE -> {
                if (!PhoneNumbers.looksLikePhone(text)) return null
                val key = PhoneNumbers.matchKey(text)
                RawOp {
                    +"right(regexp_replace(coalesce("; +expr; +", ''), '[^0-9]', '', 'g'), "
                    registerArgument(IntegerColumnType(), key.length); +") = "; text(key)
                }
            }
            SearchKind.AMOUNT -> {
                val amount = text.replace(",", "").removePrefix("₦").trim().toBigDecimalOrNull() ?: return null
                RawOp { +"("; +expr; +") = "; registerArgument(DecimalColumnType(20, 4), amount) }
            }
        }
    }

    /** A bare number (`4031`) matches references ending in that number (`INV-4031`, `INV-04031`). */
    private fun referenceSuffix(expr: Expression<*>, text: String): Op<Boolean>? {
        if (!text.all { it.isDigit() }) return null
        val significant = text.trimStart('0')
        if (significant.isEmpty()) return null
        // Digits only, so the pattern can't carry regex metacharacters from user input.
        return RawOp { +"("; +expr; +")::text ~ "; text("(^|[^0-9])0*$significant$") }
    }

    private fun enableWordSimilarityThreshold() {
        TransactionManager.current().exec("select set_config('pg_trgm.word_similarity_threshold', '0.5', true)")
    }
}

/** Implemented by FilterSpec: resolves `field:value` qualifiers from `q` into filter conditions. */
interface QualifierResolver {
    /** Every accepted qualifier name and alias (lower-case) mapped to its canonical filter key. */
    fun qualifierNames(): Map<String, String>

    /** Canonical keys of cost fields — never usable as qualifiers by callers who can't see cost. */
    fun costQualifiers(): Set<String>

    fun qualifierCondition(term: QualifierTerm): Op<Boolean>
}

internal fun QueryBuilder.text(value: String) = registerArgument(TextColumnType(), value)
