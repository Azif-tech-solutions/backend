package com.zifiq.account.service

import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.Names
import com.zifiq.account.dto.ExchangeRateHistoryRow
import com.zifiq.account.dto.ExchangeRateRow
import com.zifiq.account.dto.PublishRatesRequest
import com.zifiq.account.dto.PublishedRate
import com.zifiq.account.dto.RatePublicationView
import com.zifiq.account.model.ExchangeRatePublicationsTable
import com.zifiq.account.model.ExchangeRatesTable
import com.zifiq.account.spi.ExchangeRates
import com.zifiq.account.spi.RateRef
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Exchange Rate (docs/07 §4.16): append-only publications of buy/sell rates in naira per unit of
 * each tracked currency. Transactions in a foreign currency store the rate they used (rule A13),
 * so a new publication never changes history.
 */
@Service
class ExchangeRateService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val sequences: DocumentSequenceService,
) : ExchangeRates {
    /** One row per tracked currency: the latest rate, how it moved since the previous one, and whether it's from today. */
    fun current(): List<ExchangeRateRow> = tx.execute {
        val tracked = tracked()
        val zone = support.moduleSettings().zone()
        val latest = latestTwo(tracked)
        tracked.map { code ->
            val currency = Currencies.get(code)
            val (now, before) = latest[code].orEmpty().let { it.getOrNull(0) to it.getOrNull(1) }
            val move = if (now != null && before != null) now.buy - before.buy else null
            ExchangeRateRow(
                code = code,
                currency = currency?.name ?: code,
                symbol = currency?.symbol ?: code,
                buy = now?.buy,
                sell = now?.sell,
                spread = now?.let { it.sell - it.buy },
                previousBuy = before?.buy,
                move = move,
                movePct = if (move != null && before!!.buy.signum() != 0) move.multiply(BigDecimal(100)).divide(before.buy, 2, RoundingMode.HALF_UP) else null,
                updatedAt = now?.publishedAt,
                updatedBy = UserRef.of(now?.publishedBy),
                freshness = now?.let { if (LocalDate.ofInstant(it.publishedAt, zone) == support.today()) "Current" else "Stale" },
            )
        }
    }

    /** One FX-n publication; every rate in it shares the same timestamp. */
    fun publish(request: PublishRatesRequest): RatePublicationView = tx.execute {
        val tracked = tracked()
        val rates = request.rates.map { it.copy(currencyCode = it.currencyCode.trim().uppercase()) }
        rates.groupBy { it.currencyCode }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw ApiErrors.validationFailed(mapOf("rates" to "$it appears more than once"))
        }
        rates.forEach { rate ->
            if (rate.currencyCode !in tracked) throw ApiErrors.currencyNotTracked(rate.currencyCode, tracked)
            if (rate.sellRate!! < rate.buyRate!!) throw ApiErrors.invalidRate(rate.currencyCode, "the sell rate must not be below the buy rate")
        }
        val ref = sequences.next("FX")
        val now = Instant.now()
        val publicationId = ExchangeRatePublicationsTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[ExchangeRatePublicationsTable.ref] = ref
            it[publishedByUserId] = support.userId()
            it[publishedAt] = now
        }.value
        ExchangeRatesTable.batchInsert(rates, shouldReturnGeneratedValues = false) { rate ->
            this[ExchangeRatesTable.tenantId] = EntityID(support.tenantId(), TenantsTable)
            this[ExchangeRatesTable.publicationId] = publicationId
            this[ExchangeRatesTable.currencyCode] = rate.currencyCode
            this[ExchangeRatesTable.buyRate] = rate.buyRate!!.setScale(6, RoundingMode.HALF_UP)
            this[ExchangeRatesTable.sellRate] = rate.sellRate!!.setScale(6, RoundingMode.HALF_UP)
            this[ExchangeRatesTable.publishedAt] = now
        }
        support.record(
            "account.fx.published", "exchange_rate_publication", publicationId, ref,
            "$ref: rates published for ${rates.joinToString { it.currencyCode }}",
            mapOf("rates" to rates.associate { it.currencyCode to mapOf("buy" to it.buyRate, "sell" to it.sellRate) }),
        )
        val stored = ExchangeRatesTable.selectAll().where { ExchangeRatesTable.publicationId eq publicationId }
            .map { PublishedRate(it[ExchangeRatesTable.id].value, it[ExchangeRatesTable.currencyCode], it[ExchangeRatesTable.buyRate], it[ExchangeRatesTable.sellRate]) }
        RatePublicationView(publicationId, ref, now, UserRef(support.userId()), stored)
    }

    fun history(request: ListSearchRequest): PageResult<ExchangeRateHistoryRow> = tx.execute {
        val context = context()
        val parsed = historySearch.parse(request.q)
        val condition = listOfNotNull(
            ExchangeRatesTable.tenantId eq support.tenantId(),
            historySearch.condition(parsed),
            historyFilters.fromParams(request.params, context),
            historyFilters.fromJson(request.filter, context),
        ).allOf()!!
        val joined = ExchangeRatesTable.join(ExchangeRatePublicationsTable, JoinType.INNER, ExchangeRatesTable.publicationId, ExchangeRatePublicationsTable.id)
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) historySearch.rank(parsed) + historyFilters.sort(emptyList(), context) else historyFilters.sort(request.sortParams, context)
        val page = Paging.fetch(joined.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        page.withItems(
            page.items.map {
                ExchangeRateHistoryRow(
                    id = it[ExchangeRatesTable.id].value,
                    publicationRef = it[ExchangeRatePublicationsTable.ref],
                    currencyCode = it[ExchangeRatesTable.currencyCode],
                    buyRate = it[ExchangeRatesTable.buyRate],
                    sellRate = it[ExchangeRatesTable.sellRate],
                    publishedAt = it[ExchangeRatesTable.publishedAt],
                    publishedBy = UserRef(it[ExchangeRatePublicationsTable.publishedByUserId]),
                )
            },
        ).copy(meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun historyFilterMetadata(): FilterMetadata = tx.execute { historyFilters.metadata(context()) }

    // ---- ExchangeRates (spi): caller's transaction ----

    override fun current(currencyCode: String): RateRef? = rateAt(currencyCode, null)

    override fun asOf(currencyCode: String, instant: Instant): RateRef? = rateAt(currencyCode, instant)

    private fun rateAt(currencyCode: String, instant: Instant?): RateRef? =
        ExchangeRatesTable.selectAll()
            .where {
                listOfNotNull(
                    ExchangeRatesTable.tenantId eq support.tenantId(),
                    ExchangeRatesTable.currencyCode eq currencyCode.uppercase(),
                    instant?.let { ExchangeRatesTable.publishedAt lessEq it },
                ).allOf()!!
            }
            .orderBy(ExchangeRatesTable.publishedAt to SortOrder.DESC).limit(1).firstOrNull()
            ?.let { RateRef(it[ExchangeRatesTable.id].value, it[ExchangeRatesTable.currencyCode], it[ExchangeRatesTable.buyRate], it[ExchangeRatesTable.sellRate], it[ExchangeRatesTable.publishedAt]) }

    // ---- Helpers ----

    private data class Rate(val buy: BigDecimal, val sell: BigDecimal, val publishedAt: Instant, val publishedBy: UUID)

    /** The two most recent rates per currency. */
    private fun latestTwo(codes: List<String>): Map<String, List<Rate>> {
        if (codes.isEmpty()) return emptyMap()
        val joined = ExchangeRatesTable.join(ExchangeRatePublicationsTable, JoinType.INNER, ExchangeRatesTable.publicationId, ExchangeRatePublicationsTable.id)
        return codes.associateWith { code ->
            joined.selectAll().where { (ExchangeRatesTable.tenantId eq support.tenantId()) and (ExchangeRatesTable.currencyCode eq code) }
                .orderBy(ExchangeRatesTable.publishedAt to SortOrder.DESC).limit(2)
                .map { Rate(it[ExchangeRatesTable.buyRate], it[ExchangeRatesTable.sellRate], it[ExchangeRatesTable.publishedAt], it[ExchangeRatePublicationsTable.publishedByUserId]) }
        }
    }

    private fun tracked(): List<String> = support.moduleSettings().stringList(AccountingSettings.TRACKED_CURRENCIES)

    private fun context() = FilterContext(costVisible = true, zone = support.moduleSettings().zone())

    private val historyFilters = FilterSpec(
        fields = listOf(
            FilterField("currencyCode", "Currency", FilterType.ENUM, ExchangeRatesTable.currencyCode, aliases = setOf("currency"), facetable = true),
            FilterField("publishedBy", "Published by", FilterType.UUID, ExchangeRatePublicationsTable.publishedByUserId),
            FilterField("buyRate", "Buy rate", FilterType.NUMBER, ExchangeRatesTable.buyRate, sortable = true),
            FilterField("publishedAt", "Published", FilterType.DATETIME, ExchangeRatesTable.publishedAt, sortable = true),
        ),
        primaryDateKey = "publishedAt",
        defaultSort = listOf("publishedAt" to SortOrder.DESC),
        tiebreaker = ExchangeRatesTable.id,
    )

    private val historySearch = SearchSpec(
        fields = listOf(SearchField(ExchangeRatePublicationsTable.ref, SearchKind.REFERENCE, "Publication")),
        qualifiers = historyFilters,
    )
}
