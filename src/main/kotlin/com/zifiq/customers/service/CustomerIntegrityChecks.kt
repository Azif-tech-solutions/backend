package com.zifiq.customers.service

import com.zifiq.common.integrity.IntegrityChecks
import com.zifiq.common.integrity.IntegrityFindingRef
import com.zifiq.customers.model.CustomerLedgerEntriesTable
import com.zifiq.customers.model.CustomersTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * Customers integrity checks (docs/10 §4.10): no wallet or deposit balance is negative (rule 5), and
 * each stored balance agrees with the end of its ledger chain (the newest entry's running
 * balance_after for that balance type, or zero when there are none).
 */
@Service
class CustomerIntegrityChecks(
    private val support: CustomerSupport,
    private val tx: TenantScopedTransaction,
) : IntegrityChecks {
    override fun runIntegrityChecks(): List<IntegrityFindingRef> = tx.execute {
        val findings = mutableListOf<IntegrityFindingRef>()
        // WALLET_NEGATIVE
        CustomersTable.select(CustomersTable.code, CustomersTable.walletBalance, CustomersTable.depositBalance)
            .where { (CustomersTable.tenantId eq support.tenantId()) and ((CustomersTable.walletBalance less BigDecimal.ZERO) or (CustomersTable.depositBalance less BigDecimal.ZERO)) }
            .forEach {
                val neg = if (it[CustomersTable.walletBalance].signum() < 0) it[CustomersTable.walletBalance] else it[CustomersTable.depositBalance]
                findings += IntegrityFindingRef("WALLET_NEGATIVE", "CRITICAL", "customer", it[CustomersTable.code], ">= 0", neg.toPlainString(), "Negative wallet/deposit balance for ${it[CustomersTable.code]}")
            }
        // WALLET_CHAIN: stored balance == latest ledger balance_after per balance type.
        CustomersTable.select(CustomersTable.id, CustomersTable.code, CustomersTable.walletBalance, CustomersTable.depositBalance)
            .where { CustomersTable.tenantId eq support.tenantId() }
            .forEach { cust ->
                val id = cust[CustomersTable.id].value
                checkChain(id, cust[CustomersTable.code], "WALLET", cust[CustomersTable.walletBalance])?.let(findings::add)
                checkChain(id, cust[CustomersTable.code], "DEPOSIT", cust[CustomersTable.depositBalance])?.let(findings::add)
            }
        findings
    }

    private fun checkChain(customerId: UUID, code: String, balanceType: String, stored: BigDecimal): IntegrityFindingRef? {
        val latest = CustomerLedgerEntriesTable.select(CustomerLedgerEntriesTable.balanceAfter)
            .where { (CustomerLedgerEntriesTable.customerId eq customerId) and (CustomerLedgerEntriesTable.balanceType eq balanceType) }
            .orderBy(CustomerLedgerEntriesTable.createdAt to SortOrder.DESC)
            .limit(1).firstOrNull()?.get(CustomerLedgerEntriesTable.balanceAfter) ?: BigDecimal.ZERO
        return if (latest.compareTo(stored) != 0) {
            IntegrityFindingRef("WALLET_CHAIN", "CRITICAL", "customer", "$code/$balanceType", latest.toPlainString(), stored.toPlainString(),
                "$balanceType balance for $code disagrees with the end of its ledger")
        } else null
    }
}
