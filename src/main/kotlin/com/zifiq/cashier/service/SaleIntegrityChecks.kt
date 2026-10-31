package com.zifiq.cashier.service

import com.zifiq.cashier.model.SalesTable
import com.zifiq.common.integrity.IntegrityChecks
import com.zifiq.common.integrity.IntegrityFindingRef
import com.zifiq.platform.tenancy.TenantScopedTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/** Sales & POS integrity check (docs/10 §4.10): every completed sale is fully settled — paid + on-account = total. */
@Service
class SaleIntegrityChecks(
    private val support: CashierSupport,
    private val tx: TenantScopedTransaction,
) : IntegrityChecks {
    override fun runIntegrityChecks(): List<IntegrityFindingRef> = tx.execute {
        SalesTable.select(SalesTable.ref, SalesTable.total, SalesTable.paidNow, SalesTable.onAccount)
            .where { (SalesTable.tenantId eq support.tenantId()) and (SalesTable.status eq "COMPLETED") }
            .mapNotNull {
                val total = it[SalesTable.total]
                val settled = it[SalesTable.paidNow] + it[SalesTable.onAccount]
                if (settled.compareTo(total) != 0) {
                    IntegrityFindingRef("SALE_SETTLED", "CRITICAL", "sale", it[SalesTable.ref] ?: "", total.toPlainString(), settled.toPlainString(),
                        "Completed sale ${it[SalesTable.ref]} is not fully settled (paid + on-account != total)")
                } else null
            }
    }
}
