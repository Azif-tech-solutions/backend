package com.zifiq.account.service

import com.zifiq.account.dto.AccountLookupRow
import com.zifiq.account.dto.ExpenseCategoryRequest
import com.zifiq.account.dto.ExpenseCategoryView
import com.zifiq.account.model.BankTransactionsTable
import com.zifiq.account.model.CashTransactionsTable
import com.zifiq.account.model.ExpenseCategoriesTable
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.numbering.model.DocumentSequencesTable
import com.zifiq.platform.tenancy.TenantProvisioner
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.springframework.stereotype.Service

/**
 * Expenses / Payment List Guide (docs/07 §4.5). Every tenant starts with the PRD's ten categories;
 * inactive categories stay in historic reports but can't be used on new entries.
 */
@Service
class ExpenseCategoryService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val sequences: DocumentSequenceService,
) : TenantProvisioner {
    private val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(ExpenseCategoriesTable.code, SearchKind.CODE, "Code"),
            SearchField(ExpenseCategoriesTable.name, SearchKind.TRIGRAM, "Name"),
            SearchField(ExpenseCategoriesTable.description, SearchKind.TRIGRAM, "Description"),
        ),
    )

    /** Unpaged: a tenant has tens of categories. [overCap] = spent this month above the monthly cap. */
    fun list(q: String?, accountHeads: Set<String>, status: String?, pettyCashAllowed: Boolean?, overCap: Boolean?, sort: String?): List<ExpenseCategoryView> = tx.execute {
        val parsed = searchSpec.parse(q)
        val condition = listOfNotNull<Op<Boolean>>(
            ExpenseCategoriesTable.tenantId eq support.tenantId(),
            accountHeads.takeIf { it.isNotEmpty() }?.let { heads -> ExpenseCategoriesTable.accountHead inList heads.map { it.uppercase() } },
            status?.let { ExpenseCategoriesTable.status eq it.uppercase() },
            pettyCashAllowed?.let { ExpenseCategoriesTable.pettyCashAllowed eq it },
            searchSpec.condition(parsed),
        ).allOf()!!
        val spent = spentThisMonth()
        val rows = ExpenseCategoriesTable.selectAll().where(condition)
            .orderBy(*(searchSpec.rank(parsed) + (ExpenseCategoriesTable.name to SortOrder.ASC)).toTypedArray())
            .map { toView(it, spent[it[ExpenseCategoriesTable.id].value] ?: BigDecimal.ZERO) }
            .filter { overCap == null || it.overCap == overCap }
        when (sort?.substringBefore(',')?.trim()) {
            null, "", "name" -> rows
            "spentThisMonth" -> rows.sortedByDescending { it.spentThisMonth }
            else -> throw ApiErrors.invalidSortField(sort.substringBefore(','), listOf("name", "spentThisMonth"))
        }
    }

    fun get(id: UUID): ExpenseCategoryView = tx.execute { view(row(id)) }

    fun lookup(q: String?, pettyCashAllowed: Boolean?, activeOnly: Boolean): List<AccountLookupRow> =
        list(q, emptySet(), if (activeOnly) "ACTIVE" else null, pettyCashAllowed, null, null).take(50).map {
            AccountLookupRow(it.id, it.code, it.name, it.monthlyCap?.let { cap -> "${AccountSupport.naira(it.spentThisMonth)} of ${AccountSupport.naira(cap)} this month" } ?: it.accountHead, it.status)
        }

    fun create(request: ExpenseCategoryRequest): ExpenseCategoryView = tx.execute {
        val name = request.name.trim()
        ensureNameFree(name, null)
        val code = uniqueCode(request.code)
        val id = ExpenseCategoriesTable.insertAndGetId {
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[ExpenseCategoriesTable.code] = code
            it[ExpenseCategoriesTable.name] = name
            it[description] = request.description?.trim()?.ifEmpty { null }
            it[accountHead] = request.accountHead!!
            it[monthlyCap] = request.monthlyCap
            it[pettyCashAllowed] = request.pettyCashAllowed
            it[status] = "ACTIVE"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }.value
        support.record("account.expense-category.created", "expense_category", id, code, "Expense category $name added")
        view(row(id))
    }

    /** The code never changes: it's printed on historic reports. */
    fun update(id: UUID, request: ExpenseCategoryRequest): ExpenseCategoryView = tx.execute {
        val existing = row(id)
        val name = request.name.trim()
        ensureNameFree(name, id)
        ExpenseCategoriesTable.update({ (ExpenseCategoriesTable.id eq id) and (ExpenseCategoriesTable.tenantId eq support.tenantId()) }) {
            it[ExpenseCategoriesTable.name] = name
            it[description] = request.description?.trim()?.ifEmpty { null }
            it[accountHead] = request.accountHead!!
            it[monthlyCap] = request.monthlyCap
            it[pettyCashAllowed] = request.pettyCashAllowed
        }
        support.record("account.expense-category.updated", "expense_category", id, existing[ExpenseCategoriesTable.code], "Expense category $name updated")
        view(row(id))
    }

    fun changeStatus(id: UUID, status: String): ExpenseCategoryView = tx.execute {
        val existing = row(id)
        if (existing[ExpenseCategoriesTable.status] != status) {
            ExpenseCategoriesTable.update({ (ExpenseCategoriesTable.id eq id) and (ExpenseCategoriesTable.tenantId eq support.tenantId()) }) { it[ExpenseCategoriesTable.status] = status }
            support.record(
                "account.expense-category.status-changed", "expense_category", id, existing[ExpenseCategoriesTable.code],
                "Expense category ${existing[ExpenseCategoriesTable.name]} ${status.lowercase()}", mapOf("status" to status),
            )
        }
        view(row(id))
    }

    /**
     * For postings: an ACTIVE category of this tenant (rule A9). [forPettyCash] also requires it to be
     * payable from petty cash. Runs in the caller's transaction.
     */
    fun requireUsable(id: UUID, forPettyCash: Boolean = false): ResultRow {
        val category = row(id)
        if (category[ExpenseCategoriesTable.status] != "ACTIVE") throw ApiErrors.expenseCategoryInactive(category[ExpenseCategoriesTable.name])
        if (forPettyCash && !category[ExpenseCategoriesTable.pettyCashAllowed]) {
            throw ApiErrors.validationFailed(mapOf("expenseCategoryId" to "${category[ExpenseCategoriesTable.name]} can't be paid from petty cash"))
        }
        return category
    }

    fun names(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else ExpenseCategoriesTable.select(ExpenseCategoriesTable.id, ExpenseCategoriesTable.name).where { ExpenseCategoriesTable.id inList ids.toSet() }
            .associate { it[ExpenseCategoriesTable.id].value to it[ExpenseCategoriesTable.name] }

    // ---- TenantProvisioner: the PRD's categories for every new tenant ----

    override fun provision(tenantId: UUID, createdByUserId: UUID) {
        val now = Instant.now()
        ExpenseCategoriesTable.batchInsert(DEFAULTS.withIndex().toList(), shouldReturnGeneratedValues = false) { (index, category) ->
            this[ExpenseCategoriesTable.tenantId] = EntityID(tenantId, TenantsTable)
            this[ExpenseCategoriesTable.code] = "EX-${index + 1}"
            this[ExpenseCategoriesTable.name] = category.first
            this[ExpenseCategoriesTable.accountHead] = category.second
            this[ExpenseCategoriesTable.pettyCashAllowed] = category.third
            this[ExpenseCategoriesTable.status] = "ACTIVE"
            this[ExpenseCategoriesTable.createdAt] = now
        }
        DocumentSequencesTable.upsert {
            it[DocumentSequencesTable.tenantId] = tenantId
            it[prefix] = "EX"
            it[nextValue] = DEFAULTS.size.toLong()
        }
    }

    // ---- Helpers (caller's transaction) ----

    private fun row(id: UUID): ResultRow =
        ExpenseCategoriesTable.selectAll().where { (ExpenseCategoriesTable.id eq id) and (ExpenseCategoriesTable.tenantId eq support.tenantId()) }
            .firstOrNull() ?: throw ApiErrors.expenseCategoryNotFound()

    private fun view(row: ResultRow) = toView(row, spentThisMonth()[row[ExpenseCategoriesTable.id].value] ?: BigDecimal.ZERO)

    private fun toView(row: ResultRow, spent: BigDecimal): ExpenseCategoryView {
        val cap = row[ExpenseCategoriesTable.monthlyCap]
        return ExpenseCategoryView(
            id = row[ExpenseCategoriesTable.id].value,
            code = row[ExpenseCategoriesTable.code],
            name = row[ExpenseCategoriesTable.name],
            description = row[ExpenseCategoriesTable.description],
            accountHead = row[ExpenseCategoriesTable.accountHead],
            monthlyCap = cap,
            pettyCashAllowed = row[ExpenseCategoriesTable.pettyCashAllowed],
            status = row[ExpenseCategoriesTable.status],
            spentThisMonth = AccountSupport.money(spent),
            overCap = cap != null && spent > cap,
            createdAt = row[ExpenseCategoriesTable.createdAt],
        )
    }

    /**
     * Net spend per category this calendar month, read straight from the two ledgers: money paid out
     * (bank debits, cash out) less money coming back (reversals post the opposite entry). Running
     * costs and petty cash payments (slice 2) post through the ledgers, so they count automatically.
     */
    fun spentThisMonth(): Map<UUID, BigDecimal> {
        val today = support.today()
        val from = today.withDayOfMonth(1)
        val result = mutableMapOf<UUID, BigDecimal>()
        BankTransactionsTable.selectAll()
            .where { (BankTransactionsTable.tenantId eq support.tenantId()) and BankTransactionsTable.expenseCategoryId.isNotNull() and (BankTransactionsTable.entryDate greaterEq from) and (BankTransactionsTable.entryDate lessEq today) }
            .forEach { row ->
                val signed = if (row[BankTransactionsTable.entryType] == "DEBIT") row[BankTransactionsTable.amount] else row[BankTransactionsTable.amount].negate()
                result.merge(row[BankTransactionsTable.expenseCategoryId]!!, signed, BigDecimal::add)
            }
        CashTransactionsTable.selectAll()
            .where { (CashTransactionsTable.tenantId eq support.tenantId()) and CashTransactionsTable.expenseCategoryId.isNotNull() and (CashTransactionsTable.entryDate greaterEq from) and (CashTransactionsTable.entryDate lessEq today) }
            .forEach { row ->
                val signed = if (row[CashTransactionsTable.direction] == "OUT") row[CashTransactionsTable.amount] else row[CashTransactionsTable.amount].negate()
                result.merge(row[CashTransactionsTable.expenseCategoryId]!!, signed, BigDecimal::add)
            }
        return result
    }

    private fun ensureNameFree(name: String, exceptId: UUID?) {
        val clash = ExpenseCategoriesTable.selectAll()
            .where { (ExpenseCategoriesTable.tenantId eq support.tenantId()) and (ExpenseCategoriesTable.name.lowerCase() eq name.lowercase()) }
            .any { it[ExpenseCategoriesTable.id].value != exceptId }
        if (clash) throw ApiErrors.duplicateName(name)
    }

    /** A typed code must be free; a generated EX-n skips codes someone typed by hand. */
    private fun uniqueCode(requested: String?): String {
        fun taken(code: String) = ExpenseCategoriesTable.selectAll()
            .where { (ExpenseCategoriesTable.tenantId eq support.tenantId()) and (ExpenseCategoriesTable.code.lowerCase() eq code.lowercase()) }.any()
        val typed = requested?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        if (typed != null) {
            if (taken(typed)) throw ApiErrors.duplicateCode(typed)
            return typed
        }
        return generateSequence { sequences.next("EX") }.first { !taken(it) }
    }

    companion object {
        /** The PRD's list (docs/07 §2.4): name, account head, payable from petty cash. */
        val DEFAULTS = listOf(
            Triple("Electrical Bills", "UTILITIES", true),
            Triple("Transportation", "LOGISTICS", true),
            Triple("Loading Charges", "LOGISTICS", true),
            Triple("Offloading Charges", "LOGISTICS", true),
            Triple("Maintenance Expenses", "MAINTENANCE", true),
            Triple("Fuel Expenses", "LOGISTICS", true),
            Triple("Communication Expenses", "ADMINISTRATIVE", true),
            Triple("Office Supplies", "ADMINISTRATIVE", true),
            Triple("Other Operational Costs", "ADMINISTRATIVE", true),
            Triple("Bank Charges", "FINANCE", false),
        )
    }
}
