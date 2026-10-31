package com.zifiq.invoicing.service

import com.zifiq.common.integrity.IntegrityChecks
import com.zifiq.common.integrity.IntegrityFindingRef
import com.zifiq.invoicing.model.InvoiceLinesTable
import com.zifiq.invoicing.model.InvoicePaymentsTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.springframework.stereotype.Service

/**
 * Electronic Invoicing integrity checks (docs/10 §4.10): each non-void invoice's header totals agree
 * with its lines and payments (VAT = Σ line VAT; tax-inclusive = tax-exclusive + VAT; amount-paid =
 * Σ active payments), and the issued invoice numbers form a gap-free, duplicate-free sequence (rule 14).
 */
@Service
class InvoiceIntegrityChecks(
    private val support: InvoiceSupport,
    private val tx: TenantScopedTransaction,
) : IntegrityChecks {
    override fun runIntegrityChecks(): List<IntegrityFindingRef> = tx.execute {
        buildList {
            addAll(invoiceTotals())
            addAll(sequenceGapless())
        }
    }

    private fun invoiceTotals(): List<IntegrityFindingRef> {
        val vatSum = InvoiceLinesTable.vatAmount.sum()
        val lineVat = InvoiceLinesTable.select(InvoiceLinesTable.invoiceId, vatSum).where { InvoiceLinesTable.tenantId eq support.tenantId() }
            .groupBy(InvoiceLinesTable.invoiceId).associate { it[InvoiceLinesTable.invoiceId].value to (it[vatSum] ?: BigDecimal.ZERO) }
        val paidSum = InvoicePaymentsTable.amount.sum()
        val paid = InvoicePaymentsTable.select(InvoicePaymentsTable.invoiceId, paidSum)
            .where { (InvoicePaymentsTable.tenantId eq support.tenantId()) and InvoicePaymentsTable.reversalReason.isNull() and (InvoicePaymentsTable.receiptStatus notInList listOf("DECLINED", "VOIDED")) }
            .groupBy(InvoicePaymentsTable.invoiceId).associate { it[InvoicePaymentsTable.invoiceId].value to (it[paidSum] ?: BigDecimal.ZERO) }
        return InvoicesTable.select(InvoicesTable.id, InvoicesTable.number, InvoicesTable.vatTotal, InvoicesTable.taxExclusiveAmount, InvoicesTable.taxInclusiveAmount, InvoicesTable.amountPaid)
            .where { (InvoicesTable.tenantId eq support.tenantId()) and (InvoicesTable.status neq "VOID") }
            .flatMap { inv ->
                val id = inv[InvoicesTable.id].value
                val ref = inv[InvoicesTable.number] ?: id.toString()
                buildList {
                    val expectedVat = lineVat[id] ?: BigDecimal.ZERO
                    if (expectedVat.compareTo(inv[InvoicesTable.vatTotal]) != 0)
                        add(f("INVOICE_TOTALS", "invoice", ref, expectedVat, inv[InvoicesTable.vatTotal], "Invoice $ref VAT total disagrees with its lines"))
                    val expectedInclusive = inv[InvoicesTable.taxExclusiveAmount] + inv[InvoicesTable.vatTotal]
                    if (expectedInclusive.compareTo(inv[InvoicesTable.taxInclusiveAmount]) != 0)
                        add(f("INVOICE_TOTALS", "invoice", ref, expectedInclusive, inv[InvoicesTable.taxInclusiveAmount], "Invoice $ref tax-inclusive total is not exclusive + VAT"))
                    val expectedPaid = paid[id] ?: BigDecimal.ZERO
                    if (expectedPaid.compareTo(inv[InvoicesTable.amountPaid]) != 0)
                        add(f("INVOICE_TOTALS", "invoice", ref, expectedPaid, inv[InvoicesTable.amountPaid], "Invoice $ref amount-paid disagrees with its active payments"))
                }
            }
    }

    private fun sequenceGapless(): List<IntegrityFindingRef> {
        val numbers = InvoicesTable.select(InvoicesTable.number)
            .where { (InvoicesTable.tenantId eq support.tenantId()) and (InvoicesTable.status neq "VOID") }
            .mapNotNull { it[InvoicesTable.number] }
        val suffixes = numbers.mapNotNull { it.substringAfterLast('-').toIntOrNull() }
        if (suffixes.isEmpty()) return emptyList()
        val findings = mutableListOf<IntegrityFindingRef>()
        val dupes = suffixes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (dupes.isNotEmpty()) findings += f("INVOICE_SEQUENCE_GAPLESS", "invoice", "sequence", "no duplicates", "duplicates: ${dupes.sorted()}", "Duplicate invoice numbers")
        val sorted = suffixes.distinct().sorted()
        val expectedCount = sorted.last() - sorted.first() + 1
        if (expectedCount != sorted.size) {
            val missing = (sorted.first()..sorted.last()).filter { it !in sorted.toSet() }
            findings += f("INVOICE_SEQUENCE_GAPLESS", "invoice", "sequence", "$expectedCount contiguous", "${sorted.size} present", "Gap(s) in invoice numbering: missing ${missing.take(20)}")
        }
        return findings
    }

    private fun f(code: String, type: String, ref: String, expected: Any?, actual: Any?, message: String) =
        IntegrityFindingRef(code, "CRITICAL", type, ref, expected?.toString(), actual?.toString(), message)
}
