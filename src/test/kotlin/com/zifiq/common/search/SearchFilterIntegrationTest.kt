package com.zifiq.common.search

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zifiq.common.filter.FilterCollection
import com.zifiq.common.filter.FilterContext
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.Paging
import com.zifiq.platform.PostgresIntegrationTest
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Runs the search/filter framework against real PostgreSQL with the V7 migration applied
 * (pg_trgm, unaccent, f_unaccent), on a throwaway table so it doesn't depend on any module.
 */
class SearchFilterIntegrationTest : PostgresIntegrationTest() {
    object Items : Table("search_test_items") {
        val id = uuid("id")
        val code = varchar("code", 40)
        val name = varchar("name", 200)
        val phone = varchar("phone", 40).nullable()
        val amount = decimal("amount", 14, 2)
        val status = varchar("status", 20)
        val issued = date("issued")
        val notes = text("notes").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object Lines : Table("search_test_lines") {
        val itemId = uuid("item_id")
        val sku = varchar("sku", 40)
        val qty = decimal("qty", 14, 3)
    }

    private val searchVector = RawExpression<String>({ +"search_test_items.search_vector" })

    private val filters = FilterSpec(
        fields = listOf(
            FilterField("status", "Status", FilterType.ENUM, Items.status, options = listOf("ISSUED", "OVERDUE", "PAID").map { FilterOption(it, it.lowercase()) }, sortable = true),
            FilterField("amount", "Amount", FilterType.MONEY, Items.amount, sortable = true),
            FilterField("issued", "Issued", FilterType.DATE, Items.issued, sortable = true),
            FilterField("name", "Name", FilterType.TEXT, Items.name, sortable = true),
        ),
        collections = listOf(
            FilterCollection(
                key = "lines",
                label = "Lines",
                fields = listOf(
                    FilterField("sku", "SKU", FilterType.REF, Lines.sku),
                    FilterField("qty", "Quantity", FilterType.NUMBER, Lines.qty),
                ),
                exists = { inner ->
                    val link: Op<Boolean> = RawOp { +"search_test_lines.item_id = search_test_items.id" }
                    exists(Lines.selectAll().where { if (inner == null) link else link and inner })
                },
            ),
        ),
        primaryDateKey = "issued",
        defaultSort = listOf("issued" to SortOrder.DESC),
        tiebreaker = Items.id,
    )

    private val search = SearchSpec(
        fields = listOf(
            SearchField(Items.code, SearchKind.REFERENCE, "Number"),
            SearchField(Items.name, SearchKind.TRIGRAM, "Name"),
            SearchField(Items.phone, SearchKind.PHONE, "Phone"),
            SearchField(Items.amount, SearchKind.AMOUNT, "Amount"),
            SearchField(searchVector, SearchKind.FULLTEXT, "Notes"),
        ),
        qualifiers = filters,
    )

    private val context = FilterContext(costVisible = true, zone = ZoneId.of("Africa/Lagos"), today = LocalDate.of(2026, 10, 8))

    companion object {
        private val ids = List(4) { UUID.randomUUID() }

        @JvmStatic
        @BeforeAll
        fun createTable() {
            transaction(database) {
                exec(
                    """
                    create table search_test_items (
                        id uuid primary key, code varchar(40) not null, name varchar(200) not null,
                        phone varchar(40), amount numeric(14,2) not null, status varchar(20) not null,
                        issued date not null, notes text,
                        search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(coalesce(notes, '')))) stored
                    )
                    """.trimIndent(),
                )
                exec("create index search_test_items_name_trgm on search_test_items using gin (f_unaccent(lower(name)) gin_trgm_ops)")
                exec("create table search_test_lines (item_id uuid not null references search_test_items(id), sku varchar(40) not null, qty numeric(14,3) not null)")

                data class Row(val code: String, val name: String, val phone: String?, val amount: String, val status: String, val issued: String, val notes: String?)
                val rows = listOf(
                    Row("INV-4031", "Société Générale Ikeja", "+2348031234567", "52890.00", "ISSUED", "2026-09-15", "Citrus drink delivery to warehouse"),
                    Row("INV-4032", "Okafor & Sons Distribution", "08091112222", "120000.00", "OVERDUE", "2026-10-01", "Malt classic cartons"),
                    Row("INV-104031", "Bendel Stores", null, "7500.00", "PAID", "2026-08-01", "Ginger blend"),
                    Row("DLR-42", "Citrus Hub Lekki", null, "999.99", "ISSUED", "2026-10-07", null),
                )
                Items.batchInsert(rows.withIndex()) { (index, row) ->
                    this[Items.id] = ids[index]
                    this[Items.code] = row.code
                    this[Items.name] = row.name
                    this[Items.phone] = row.phone
                    this[Items.amount] = BigDecimal(row.amount)
                    this[Items.status] = row.status
                    this[Items.issued] = LocalDate.parse(row.issued)
                    this[Items.notes] = row.notes
                }
                Lines.batchInsert(listOf(Triple(0, "ZF-101", "12"), Triple(1, "ZF-101", "200"), Triple(1, "ZF-210", "5"))) { (index, sku, qty) ->
                    this[Lines.itemId] = ids[index]
                    this[Lines.sku] = sku
                    this[Lines.qty] = BigDecimal(qty)
                }
            }
        }
    }

    private fun codesFor(q: String): List<String> = transaction(database) {
        val condition = search.condition(search.parse(q))
        val query = Items.selectAll()
        if (condition != null) query.where(condition)
        query.map { it[Items.code] }.sorted()
    }

    private fun codesWhere(build: () -> Op<Boolean>?): List<String> = transaction(database) {
        val condition = build()
        val query = Items.selectAll()
        if (condition != null) query.where(condition)
        query.map { it[Items.code] }.sorted()
    }

    @Test
    fun `accent- and case-insensitive substring`() {
        assertThat(codesFor("societe")).containsExactly("INV-4031")
        assertThat(codesFor("GÉNÉRALE")).containsExactly("INV-4031")
    }

    @Test
    fun `typo-tolerant word similarity`() {
        assertThat(codesFor("Okafr")).containsExactly("INV-4032")
    }

    @Test
    fun `bare number matches reference suffix only at a number boundary`() {
        assertThat(codesFor("4031")).containsExactly("INV-4031")
        assertThat(codesFor("INV-4031")).containsExactly("INV-4031")
        assertThat(codesFor("dlr")).containsExactly("DLR-42")
    }

    @Test
    fun `phone written locally matches a number stored internationally`() {
        assertThat(codesFor("0803 123 4567")).containsExactly("INV-4031")
        assertThat(codesFor("+234 809 111 2222")).containsExactly("INV-4032")
    }

    @Test
    fun `numeric term matches amounts`() {
        assertThat(codesFor("52,890")).containsExactly("INV-4031")
    }

    @Test
    fun `full text, phrases, exclusion and OR`() {
        assertThat(codesFor("warehouse")).containsExactly("INV-4031")
        assertThat(codesFor("\"malt classic\"")).containsExactly("INV-4032")
        assertThat(codesFor("citrus")).containsExactly("DLR-42", "INV-4031")
        assertThat(codesFor("citrus -lekki")).containsExactly("INV-4031")
        assertThat(codesFor("bendel OR okafor")).containsExactly("INV-104031", "INV-4032")
    }

    @Test
    fun `qualifiers in q become filters`() {
        assertThat(codesFor("status:overdue")).containsExactly("INV-4032")
        assertThat(codesFor("amount:>100000")).containsExactly("INV-4032")
        assertThat(codesFor("citrus status:issued")).containsExactly("DLR-42", "INV-4031")
    }

    @Test
    fun `simple parameter filters, ranges and periods`() {
        assertThat(codesWhere { filters.fromParams(mapOf("status" to listOf("ISSUED,PAID")), context) })
            .containsExactly("DLR-42", "INV-104031", "INV-4031")
        assertThat(codesWhere { filters.fromParams(mapOf("amount.gte" to listOf("1000"), "amount.lte" to listOf("60000")), context) })
            .containsExactly("INV-104031", "INV-4031")
        assertThat(codesWhere { filters.fromParams(mapOf("period" to listOf("LAST_MONTH")), context) })
            .containsExactly("INV-4031")
        assertThat(codesWhere { filters.fromParams(mapOf("status!" to listOf("ISSUED")), context) })
            .containsExactly("INV-104031", "INV-4032")
        assertThat(codesWhere { filters.fromParams(mapOf("name" to listOf("générale")), context) })
            .containsExactly("INV-4031")
    }

    @Test
    fun `collection filters hold for the same child row`() {
        val mapper = jacksonObjectMapper()
        val sameLine = mapper.readTree("""{"lines":{"all":[{"field":"sku","op":"eq","value":"ZF-210"},{"field":"qty","op":"gte","value":100}]}}""")
        assertThat(codesWhere { filters.fromJson(sameLine, context) }).isEmpty()

        val other = mapper.readTree("""{"lines":{"all":[{"field":"sku","op":"eq","value":"ZF-210"},{"field":"qty","op":"gte","value":5}]}}""")
        assertThat(codesWhere { filters.fromJson(other, context) }).containsExactly("INV-4032")

        val noLines = mapper.readTree("""{"field":"lines","op":"is_empty"}""")
        assertThat(codesWhere { filters.fromJson(noLines, context) }).containsExactly("DLR-42", "INV-104031")
    }

    @Test
    fun `ranking puts the exact reference first`() {
        val first = transaction(database) {
            val parsed = search.parse("INV-4031 OR 4031")
            Items.selectAll().where(search.condition(parsed)!!)
                .orderBy(*(search.rank(parsed) + filters.sort(emptyList(), context)).toTypedArray())
                .first()[Items.code]
        }
        assertThat(first).isEqualTo("INV-4031")
    }

    @Test
    fun `paging counts the whole filtered set`() {
        val page = transaction(database) {
            Paging.fetch(Items.selectAll().orderBy(*filters.sort(emptyList(), context).toTypedArray()), PageRequest.of(0, 3)) { it[Items.code] }
        }
        assertThat(page.totalItems).isEqualTo(4)
        assertThat(page.totalPages).isEqualTo(2)
        assertThat(page.items).hasSize(3).first().isEqualTo("DLR-42") // newest issued first
    }

    @Test
    fun `statement timeout cancels slow searches with SQLSTATE 57014`() {
        assertThatThrownBy {
            transaction(database) {
                exec("select set_config('statement_timeout', '200ms', true)")
                exec("select pg_sleep(1)")
            }
        }.isInstanceOf(ExposedSQLException::class.java)
            .extracting { (it as ExposedSQLException).sqlState }.isEqualTo("57014")
    }
}
