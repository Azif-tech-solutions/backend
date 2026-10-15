package com.zifiq.customers.service

import com.zifiq.common.paging.PagingPolicy
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.Facets
import com.zifiq.common.filter.FilterCollection
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.FacetCount
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.Highlighter
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.RawOp
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.common.search.text
import com.zifiq.common.tax.NigeriaStatesTable
import com.zifiq.customers.dto.CustomerLookupRow
import com.zifiq.customers.dto.CustomerStats
import com.zifiq.customers.dto.CustomerSummary
import com.zifiq.customers.model.CorrespondentsTable
import com.zifiq.customers.model.CustomerLedgerEntriesTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.customers.model.DealerCategoriesTable
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.platform.customfields.CustomFieldEntity
import com.zifiq.platform.customfields.CustomFieldValidator
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import java.math.BigDecimal
import java.util.UUID
import org.jetbrains.exposed.sql.DecimalColumnType
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Sum
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/** Customer register search, filters, facets and pickers — docs/04 §4.2 and §9, on the docs/03 §4–§5 framework. */
@Service
class CustomerListService(
    private val support: CustomerSupport,
    private val tx: TenantScopedTransaction,
    private val locationAccess: LocationAccessService,
    private val customFields: CustomFieldValidator,
    private val moduleSettings: ModuleSettings,
) {
    private fun bool(sql: QueryBuilderSql): Expression<Boolean> = RawExpression(sql)

    /** Built per request: the tenant's custom fields become `attr.<key>` filters. */
    private fun filterSpec(): FilterSpec {
        val typeOptions = CustomerType.entries.map { FilterOption(it.name, it.label) }
        val statusOptions = CustomerStatus.entries.map { FilterOption(it.name, it.name.lowercase().replaceFirstChar(Char::titlecase)) }
        val fields = mutableListOf(
            FilterField("type", "Customer type", FilterType.ENUM, CustomersTable.type, typeOptions, sortable = false, facetable = true),
            FilterField("status", "Status", FilterType.ENUM, CustomersTable.status, statusOptions, facetable = true),
            FilterField("isTemporary", "Temporary walk-in", FilterType.BOOLEAN, CustomersTable.isTemporary),
            FilterField("locationId", "Home location", FilterType.LOCATION, CustomersTable.locationId, aliases = setOf("location"), facetable = true, lookup = "/api/v1/factory/setup/factories"),
            FilterField("correspondentId", "Correspondent", FilterType.UUID, CustomersTable.correspondentId, aliases = setOf("rep"), facetable = true, lookup = "/api/v1/customers/correspondents/lookup"),
            FilterField("dealerCategoryId", "Dealer category", FilterType.UUID, CustomersTable.dealerCategoryId, facetable = true, lookup = "/api/v1/customers/dealer-categories"),
            FilterField("stateCode", "State", FilterType.ENUM, CustomersTable.stateCode, aliases = setOf("state"), facetable = true),
            FilterField("lgaCode", "LGA", FilterType.ENUM, CustomersTable.lgaCode, aliases = setOf("lga")),
            FilterField("name", "Name", FilterType.TEXT, CustomersTable.name, sortable = true),
            FilterField("code", "Code", FilterType.REF, CustomersTable.code, sortable = true),
            FilterField("walletBalance", "Wallet balance", FilterType.MONEY, CustomersTable.walletBalance, aliases = setOf("wallet"), sortable = true),
            FilterField("depositBalance", "Deposit balance", FilterType.MONEY, CustomersTable.depositBalance, aliases = setOf("deposit"), sortable = true),
            FilterField("creditLimit", "Credit limit", FilterType.MONEY, CustomersTable.creditLimit, sortable = true),
            FilterField("lifetimeValue", "Lifetime value", FilterType.MONEY, CustomersTable.lifetimeValue, sortable = true),
            FilterField("hasWalletBalance", "Holds a wallet balance", FilterType.BOOLEAN, bool { +"("; +CustomersTable.walletBalance; +" > 0)" }),
            FilterField("hasEmail", "Has e-mail", FilterType.BOOLEAN, bool { +"("; +CustomersTable.email; +" IS NOT NULL)" }),
            FilterField("hasPhone", "Has phone", FilterType.BOOLEAN, bool { +"("; +CustomersTable.phoneE164; +" IS NOT NULL)" }),
            FilterField("hasTin", "Has TIN", FilterType.BOOLEAN, bool { +"("; +CustomersTable.tin; +" IS NOT NULL)" }),
            FilterField("hasCorrespondent", "Has correspondent", FilterType.BOOLEAN, bool { +"("; +CustomersTable.correspondentId; +" IS NOT NULL)" }),
            FilterField("customerSince", "Customer since", FilterType.DATE, CustomersTable.customerSince, sortable = true),
            FilterField("lastPurchaseAt", "Last purchase", FilterType.DATETIME, CustomersTable.lastPurchaseAt, sortable = true),
            FilterField("createdAt", "Registered", FilterType.DATETIME, CustomersTable.createdAt, sortable = true),
            FilterField("createdBy", "Registered by", FilterType.UUID, CustomersTable.createdByUserId),
        )
        fields += customFields.definitions(CustomFieldEntity.CUSTOMER).map { definition ->
            val (type, cast) = when (definition.dataType) {
                "NUMBER" -> FilterType.NUMBER to "::numeric"
                "DATE" -> FilterType.DATE to "::date"
                "BOOLEAN" -> FilterType.BOOLEAN to "::boolean"
                "SELECT" -> FilterType.ENUM to ""
                else -> FilterType.TEXT to ""
            }
            FilterField(
                key = "attr.${definition.key}",
                label = definition.label,
                type = type,
                expression = RawExpression<Any>({ +"(("; +CustomersTable.customAttributes; +" ->> "; text(definition.key); +")$cast)" }),
                options = definition.options.map { FilterOption(it, it) },
                facetable = definition.dataType == "SELECT",
            )
        }

        val ledger = FilterCollection(
            key = "ledger",
            label = "Wallet/deposit history",
            fields = listOf(
                FilterField("entryType", "Entry type", FilterType.ENUM, CustomerLedgerEntriesTable.entryType, LedgerEntryType.entries.map { FilterOption(it.name, it.displayType) }),
                FilterField("balanceType", "Balance", FilterType.ENUM, CustomerLedgerEntriesTable.balanceType, listOf(FilterOption("WALLET", "Wallet"), FilterOption("DEPOSIT", "Deposit"))),
                FilterField("amount", "Amount", FilterType.MONEY, CustomerLedgerEntriesTable.amount),
                FilterField("entryDate", "Date", FilterType.DATE, CustomerLedgerEntriesTable.entryDate),
                FilterField("sourceModule", "Source module", FilterType.ENUM, CustomerLedgerEntriesTable.sourceModule),
            ),
            exists = { inner ->
                val link: Op<Boolean> = RawOp { +CustomerLedgerEntriesTable.customerId; +" = "; +CustomersTable.id }
                exists(CustomerLedgerEntriesTable.selectAll().where { if (inner == null) link else link and inner })
            },
        )
        return FilterSpec(
            fields = fields,
            collections = listOf(ledger),
            primaryDateKey = "customerSince",
            defaultSort = listOf("name" to SortOrder.ASC),
            tiebreaker = CustomersTable.id,
        )
    }

    private fun searchSpec(filters: FilterSpec) = SearchSpec(
        fields = listOf(
            SearchField(CustomersTable.code, SearchKind.REFERENCE, "Code"),
            SearchField(CustomersTable.phoneE164, SearchKind.PHONE, "Phone"),
            SearchField(CustomersTable.email, SearchKind.CODE, "E-mail"),
            SearchField(CustomersTable.tin, SearchKind.CODE, "TIN"),
            SearchField(CustomersTable.name, SearchKind.TRIGRAM, "Name"),
            SearchField(CustomersTable.contactPerson, SearchKind.TRIGRAM, "Contact person"),
            SearchField(RawExpression<String>({ +"customers.search_vector" }), SearchKind.FULLTEXT, "Address and description"),
        ),
        qualifiers = filters,
    )

    private fun context() = FilterContext(costVisible = true, zone = moduleSettings.zone())

    /**
     * Rows the caller may see at all: tenant, location scope, own-customers scope; REMOVED and
     * temporary records only when the request filters on them explicitly.
     */
    private fun scope(request: ListSearchRequest, correspondentId: UUID? = null): Op<Boolean> {
        val parts = mutableListOf<Op<Boolean>>(CustomersTable.tenantId eq support.tenantId())
        locationAccess.resolveFilter(null)?.let { parts += CustomersTable.locationId eq it }
        if (!support.can("customers.read")) {
            if (!support.can("customers.read.own")) throw ApiErrors.accessDenied()
            parts += RawOp {
                +CustomersTable.correspondentId; +" IN (SELECT id FROM correspondents WHERE user_id = "
                registerArgument(org.jetbrains.exposed.sql.UUIDColumnType(), support.userId()); +")"
            }
        }
        val mentioned = request.params.keys.map { it.substringBefore('.').removeSuffix("!") } + mentionedInJson(request)
        if ("status" !in mentioned) parts += RawOp { +CustomersTable.status; +" <> 'REMOVED'" }
        if ("isTemporary" !in mentioned) parts += CustomersTable.isTemporary eq false
        correspondentId?.let { parts += CustomersTable.correspondentId eq it }
        return parts.allOf()!!
    }

    private fun mentionedInJson(request: ListSearchRequest): List<String> =
        request.filter?.findValues("field")?.map { it.asText().substringBefore('.') }.orEmpty() +
            listOfNotNull(request.q?.takeIf { it.contains("status:", ignoreCase = true) }?.let { "status" })

    fun filters(): FilterMetadata = tx.execute { filterSpec().metadata(context()) }

    fun list(request: ListSearchRequest, correspondentId: UUID? = null): PageResult<CustomerSummary> = tx.execute {
        val filters = filterSpec()
        val search = searchSpec(filters)
        val context = context()
        val parsed = search.parse(request.q)
        val page = PageRequest.of(request.page, request.size)

        val scope = scope(request, correspondentId)
        val searchCondition = search.condition(parsed)
        val jsonCondition = filters.fromJson(request.filter, context)
        val condition = listOfNotNull(scope, searchCondition, filters.fromParams(request.params, context), jsonCondition).allOf()!!

        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) search.rank(parsed) + filters.sort(emptyList(), context)
        else filters.sort(request.sortParams, context)
        val query = support.joined().selectAll().where(condition).orderBy(*sort.toTypedArray())
        val result = Paging.fetch(query, page, support::toSummary)

        val facets = request.facets.take(Facets.MAX_FACETS).associateWith { key ->
            val others = Facets.paramsWithout(request.params, key)
            val facetCondition = listOfNotNull(scope, searchCondition, filters.fromParams(others, context), jsonCondition).allOf()
            facet(key, facetCondition, filters)
        }
        val summary = if (request.summary) summary(condition) else null
        val suggestions = if (result.totalItems == 0L && !parsed.isEmpty) suggest(scope, parsed.rankingText) else emptyList()
        val matches = if (request.highlight && !parsed.isEmpty) {
            result.items.associate { row ->
                row.id.toString() to Highlighter.matches(parsed, mapOf("name" to row.name, "contactPerson" to row.contactPerson, "code" to row.code))
            }.filterValues { it.isNotEmpty() }
        } else emptyMap()

        result.copy(
            facets = facets.ifEmpty { null },
            summary = summary,
            meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers, suggestions = suggestions, matches = matches),
        )
    }

    private fun facet(key: String, condition: Op<Boolean>?, filters: FilterSpec): List<FacetCount> {
        val base = support.joined()
        return when (key) {
            "type" -> Facets.count(base, condition, CustomersTable.type) { v -> v?.let { CustomerType.valueOf(it).label } }
            "status" -> Facets.count(base, condition, CustomersTable.status)
            "locationId" -> Facets.count(base, condition, CustomersTable.locationId) { v -> v?.let(::locationName) }
            "correspondentId" -> Facets.count(base, condition, CustomersTable.correspondentId) { v -> v?.let(::correspondentName) }
            "dealerCategoryId" -> Facets.count(base, condition, CustomersTable.dealerCategoryId) { v -> v?.let(::categoryName) }
            "stateCode" -> Facets.count(base, condition, CustomersTable.stateCode) { v -> v?.let(::stateName) }
            else -> {
                val field = filters.fields.firstOrNull { it.key == key && it.facetable }
                    ?: throw ApiErrors.invalidFilter(key, "Not a facet", filters.fields.filter { it.facetable }.map { it.key })
                Facets.count(base, condition, field.expression)
            }
        }
    }

    private fun summary(condition: Op<Boolean>): Map<String, Any?> {
        val count = CustomersTable.id.count()
        val wallet = Sum(CustomersTable.walletBalance, DecimalColumnType(16, 2))
        val deposit = Sum(CustomersTable.depositBalance, DecimalColumnType(16, 2))
        val lifetime = Sum(CustomersTable.lifetimeValue, DecimalColumnType(16, 2))
        val row = support.joined().select(count, wallet, deposit, lifetime).where(condition).single()
        return mapOf(
            "count" to row[count],
            "walletBalanceTotal" to (row[wallet] ?: BigDecimal.ZERO),
            "depositBalanceTotal" to (row[deposit] ?: BigDecimal.ZERO),
            "lifetimeValueTotal" to (row[lifetime] ?: BigDecimal.ZERO),
        )
    }

    /** "Did you mean": the closest names the caller could see (docs/03 §4.6). */
    private fun suggest(scope: Op<Boolean>, text: String): List<String> {
        if (text.length < 3) return emptyList()
        val similarity = RawExpression<Double>({ +"similarity(f_unaccent(lower("; +CustomersTable.name; +")), f_unaccent(lower("; text(text); +")))" })
        val close: Op<Boolean> = RawOp { +"similarity(f_unaccent(lower("; +CustomersTable.name; +")), f_unaccent(lower("; text(text); +"))) >= 0.3" }
        return CustomersTable.select(CustomersTable.name).where(scope and close)
            .orderBy(similarity to SortOrder.DESC).limit(PagingPolicy.SUGGESTIONS).map { it[CustomersTable.name] }.distinct()
    }

    /** Register header tiles: one grouped query, never a row scan in memory. */
    fun stats(type: CustomerType?, locationId: UUID?): CustomerStats = tx.execute {
        val parts = mutableListOf<Op<Boolean>>(
            CustomersTable.tenantId eq support.tenantId(),
            CustomersTable.isTemporary eq false,
            RawOp { +CustomersTable.status; +" <> 'REMOVED'" },
        )
        locationAccess.resolveFilter(locationId)?.let { parts += CustomersTable.locationId eq it }
        type?.let { parts += CustomersTable.type eq it.name }
        val count = CustomersTable.id.count()
        val wallet = Sum(CustomersTable.walletBalance, DecimalColumnType(16, 2))
        val deposit = Sum(CustomersTable.depositBalance, DecimalColumnType(16, 2))
        val withWallet = RawExpression<Long>({ +"count(*) filter (where "; +CustomersTable.walletBalance; +" > 0)" })
        val groups = CustomersTable.select(CustomersTable.type, CustomersTable.status, count, wallet, deposit, withWallet)
            .where(parts.allOf()!!)
            .groupBy(CustomersTable.type, CustomersTable.status)
            .toList()
        fun typeCount(t: CustomerType) = groups.filter { it[CustomersTable.type] == t.name }.sumOf { it[count] }
        fun statusCount(s: CustomerStatus) = groups.filter { it[CustomersTable.status] == s.name }.sumOf { it[count] }
        CustomerStats(
            total = groups.sumOf { it[count] },
            active = statusCount(CustomerStatus.ACTIVE),
            watch = statusCount(CustomerStatus.WATCH),
            suspended = statusCount(CustomerStatus.SUSPENDED),
            dealers = typeCount(CustomerType.AUTHORISED_DEALER),
            special = typeCount(CustomerType.SPECIAL_CUSTOMER),
            ordinary = typeCount(CustomerType.ORDINARY_CUSTOMER),
            noCredit = typeCount(CustomerType.NO_CREDIT_WALKIN),
            walletsHeld = groups.sumOf { (it[withWallet] as Number).toLong() },
            walletBalanceTotal = groups.fold(BigDecimal.ZERO.setScale(2)) { sum, row -> sum + (row[wallet] ?: BigDecimal.ZERO) },
            depositBalanceTotal = groups.fold(BigDecimal.ZERO.setScale(2)) { sum, row -> sum + (row[deposit] ?: BigDecimal.ZERO) },
        )
    }

    /**
     * Picker for POS, pro-forma and returns (docs/04 §9). Not location-restricted: a customer can buy
     * anywhere. With `forSale`, suspended customers show as disabled, with the reason.
     */
    fun lookup(q: String?, types: Set<CustomerType>?, forSale: Boolean, limit: Int): List<CustomerLookupRow> = tx.execute {
        pickerRefs(q, types, limit)
            .map { ref ->
                val disabled = forSale && ref.status == CustomerStatus.SUSPENDED
                CustomerLookupRow(
                    id = ref.id,
                    code = ref.code,
                    label = ref.name,
                    sublabel = listOfNotNull(ref.code, ref.dealerCategoryName ?: ref.type.label, ref.phone?.let { "…" + it.filter(Char::isDigit).takeLast(4) }).joinToString(" · "),
                    type = ref.type,
                    status = ref.status,
                    disabled = disabled,
                    disabledReason = if (disabled) "Suspended" else null,
                    walletBalance = ref.walletBalance,
                    depositBalance = ref.depositBalance,
                    creditLimit = ref.creditLimit,
                )
            }
    }

    /**
     * Best matches by code, phone, name — for pickers and `CustomerDirectory.search`. Runs in the
     * caller's transaction; excludes REMOVED records and isn't location-restricted.
     */
    fun pickerRefs(q: String?, types: Set<CustomerType>?, limit: Int): List<CustomerRef> {
        val search = searchSpec(filterSpec())
        val parsed = search.parse(q)
        val parts = mutableListOf<Op<Boolean>>(CustomersTable.tenantId eq support.tenantId(), RawOp { +CustomersTable.status; +" <> 'REMOVED'" })
        types?.takeIf { it.isNotEmpty() }?.let { set -> parts += RawOp { +CustomersTable.type; +" IN ("; set.forEachIndexed { i, t -> if (i > 0) +", "; text(t.name) }; +")" } }
        search.condition(parsed)?.let { parts += it }
        Paging.limitStatementTime()
        return support.joined().selectAll().where(parts.allOf()!!)
            .orderBy(*(search.rank(parsed) + (CustomersTable.name to SortOrder.ASC) + (CustomersTable.id to SortOrder.ASC)).toTypedArray())
            .limit(PagingPolicy.lookupLimit(limit))
            .map(support::toRef)
    }

    private fun locationName(id: String) = LocationsTable.select(LocationsTable.name).where { LocationsTable.id eq UUID.fromString(id) }.firstOrNull()?.get(LocationsTable.name)
    private fun correspondentName(id: String) = CorrespondentsTable.select(CorrespondentsTable.name).where { CorrespondentsTable.id eq UUID.fromString(id) }.firstOrNull()?.get(CorrespondentsTable.name)
    private fun categoryName(id: String) = DealerCategoriesTable.select(DealerCategoriesTable.name).where { DealerCategoriesTable.id eq UUID.fromString(id) }.firstOrNull()?.get(DealerCategoriesTable.name)
    private fun stateName(code: String) = NigeriaStatesTable.select(NigeriaStatesTable.name).where { NigeriaStatesTable.code eq code }.firstOrNull()?.get(NigeriaStatesTable.name)
}

private typealias QueryBuilderSql = org.jetbrains.exposed.sql.QueryBuilder.() -> Unit
