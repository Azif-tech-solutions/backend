package com.zifiq.history.service

import com.zifiq.account.spi.VatInputQueries
import com.zifiq.account.spi.VatRates
import com.zifiq.common.errors.ApiErrors
import com.zifiq.history.dto.VatRateChange
import com.zifiq.history.dto.VatReport
import com.zifiq.history.dto.VatReportHeader
import com.zifiq.history.dto.VatReportRow
import com.zifiq.history.dto.VatReturn
import com.zifiq.history.dto.VatReturnRow
import com.zifiq.invoicing.spi.InvoiceQueries
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.ref
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.showroom.spi.ShowroomQueries
import com.zifiq.showroom.spi.StockEntryFilter
import com.zifiq.cashier.spi.SalesVatQueries
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

/**
 * VAT compliance (docs/10 §4.6): the Showroom/Factory VAT Report (output vs input VAT, reconciling
 * with the Sales Report) and the VAT return summary. Read-only, aggregated through the owning
 * modules' SPI. Input-line taxable amounts are cost figures, zeroed without `factory.costing.read`;
 * VAT itself is never masked (it is a tax liability, not a cost price).
 *
 * Covered input sources: showroom direct-entry / outsource receipts and running costs. Factory
 * raw-material-receipt input VAT and the derived special-production VAT (doc §11 open items) are not
 * included yet — they contribute zero until those capture paths are wired.
 */
@Service
class VatComplianceService(
    private val support: HistorySupport,
    private val tx: TenantScopedTransaction,
    private val sales: SalesVatQueries,
    private val invoices: InvoiceQueries,
    private val showroom: ShowroomQueries,
    private val runningCosts: VatInputQueries,
    private val vatRates: VatRates,
) {
    fun report(locationId: UUID, from: LocalDate, to: LocalDate, type: String?): VatReport = tx.execute {
        requireRange(from, to)
        support.assertAccess(locationId)
        val want = type?.trim()?.uppercase()
        val costVisible = support.costVisible()
        val rows = mutableListOf<VatReportRow>()

        if (want != "INPUT") {
            sales.outputVatLines(locationId, from, to).forEach {
                rows += VatReportRow(it.date, it.invoiceNumber ?: it.saleRef, it.customerName, null, "Sale ${it.saleRef}", "Output", it.taxableAmount, it.rate, it.vat)
            }
            invoices.creditNoteLines(locationId, from, to).forEach {
                rows += VatReportRow(it.date, it.creditNoteNumber, it.buyerName, it.buyerTin, "Credit note (inv ${it.invoiceNumber})", "Output", it.lineNet.negate(), it.vatRate, it.vatAmount.negate())
            }
        }
        val inputRows = mutableListOf<VatReportRow>()
        if (want != "OUTPUT") {
            showroom.stockEntries(StockEntryFilter(null, setOf(locationId), from, to)).filter { it.inputVatAmount.signum() > 0 }.forEach {
                inputRows += VatReportRow(it.receivedDate, it.ref, it.supplierName ?: "", null, "${label(it.kind)} receipt", "Input", it.lineCost, it.inputVatRate, it.inputVatAmount)
            }
            runningCosts.runningCostInputVat(locationId, from, to).forEach {
                inputRows += VatReportRow(it.date, it.ref, it.counterparty, it.counterpartyTin, "Running cost", "Input", it.taxableAmount, rate(it.vat, it.taxableAmount), it.vat)
            }
        }

        val outputVat = rows.sumBd { it.vat }
        val outputBase = rows.sumBd { it.taxableAmount }
        val inputVat = inputRows.sumBd { it.vat }
        val inputBase = inputRows.sumBd { it.taxableAmount }
        val setting = vatRates.forLocation(locationId, to)
        val displayInput = if (costVisible) inputRows else inputRows.map { it.copy(taxableAmount = BigDecimal.ZERO) }
        VatReport(
            header = VatReportHeader(
                location = Ref.location(locationId), vatEnabled = setting.enabled, currentRate = setting.rate,
                rateChanges = listOf(VatRateChange(setting.rate, setting.effectiveFrom)),
                outputVat = outputVat, outputBase = outputBase, inputVat = inputVat,
                inputBase = if (costVisible) inputBase else BigDecimal.ZERO,
                netVatPayable = outputVat - inputVat, costVisible = costVisible,
            ),
            rows = (rows + displayInput).sortedBy { it.date },
        )
    }

    fun vatReturn(period: String): VatReturn = tx.execute {
        val month = runCatching { YearMonth.parse(period.trim()) }.getOrNull() ?: throw ApiErrors.validationFailed(mapOf("period" to "use YYYY-MM"))
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val visible = support.visibleLocations()
        val locations = LocationsTable.select(LocationsTable.id, LocationsTable.vatRegistrationNumber)
            .where {
                var c = (LocationsTable.tenantId eq support.tenantId()) and (LocationsTable.type inList listOf("FACTORY", "SHOWROOM"))
                if (visible != null) c = c and (LocationsTable.id inList visible)
                c
            }
            .associate { it[LocationsTable.id].value to it[LocationsTable.vatRegistrationNumber] }
        val rows = locations.mapNotNull { (loc, tin) -> returnRow(loc, tin, from, to) }
        VatReturn(period = month.toString(), rows = rows, companyTotal = companyTotal(rows))
    }

    fun returnCsv(period: String): ByteArray {
        val ret = vatReturn(period)
        val sb = StringBuilder("location,tin,outputBase,outputVat,creditNoteVat,inputVat,netPayable,salesCount,zeroRatedSales,exemptSales\n")
        (ret.rows + ret.companyTotal).forEach { r ->
            sb.append("${r.location?.id ?: "TOTAL"},${r.vatRegistrationNumber ?: ""},${r.outputBase},${r.outputVat},${r.creditNoteVat},${r.inputVat},${r.netPayable},${r.salesCount},${r.zeroRatedSales},${r.exemptSales}\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun returnRow(locationId: UUID, tin: String?, from: LocalDate, to: LocalDate): VatReturnRow? {
        val saleLines = sales.outputVatLines(locationId, from, to)
        val creditVat = invoices.creditNoteLines(locationId, from, to).sumBd { it.vatAmount }
        val inputVat = showroom.stockEntries(StockEntryFilter(null, setOf(locationId), from, to)).sumBd { it.inputVatAmount } +
            runningCosts.runningCostInputVat(locationId, from, to).sumBd { it.vat }
        if (saleLines.isEmpty() && creditVat.signum() == 0 && inputVat.signum() == 0) return null
        val standard = saleLines.filter { it.vatCategoryCode == "STANDARD_RATE" }
        val outputVat = saleLines.sumBd { it.vat }
        return VatReturnRow(
            location = ref(locationId, Ref::location), vatRegistrationNumber = tin,
            outputBase = standard.sumBd { it.taxableAmount }, outputVat = outputVat, creditNoteVat = creditVat, inputVat = inputVat,
            netPayable = outputVat - creditVat - inputVat,
            salesCount = saleLines.map { it.saleRef }.distinct().size,
            zeroRatedSales = saleLines.filter { it.vatCategoryCode == "ZERO_RATED" }.sumBd { it.taxableAmount },
            exemptSales = saleLines.filter { it.vatCategoryCode == "EXEMPT" }.sumBd { it.taxableAmount },
        )
    }

    private fun companyTotal(rows: List<VatReturnRow>) = VatReturnRow(
        location = null, vatRegistrationNumber = null,
        outputBase = rows.sumBd { it.outputBase }, outputVat = rows.sumBd { it.outputVat }, creditNoteVat = rows.sumBd { it.creditNoteVat },
        inputVat = rows.sumBd { it.inputVat }, netPayable = rows.sumBd { it.netPayable }, salesCount = rows.sumOf { it.salesCount },
        zeroRatedSales = rows.sumBd { it.zeroRatedSales }, exemptSales = rows.sumBd { it.exemptSales },
    )

    private fun requireRange(from: LocalDate, to: LocalDate) {
        if (ChronoUnit.DAYS.between(from, to) > 366) throw ApiErrors.rangeTooLarge(366)
    }

    private fun rate(vat: BigDecimal, taxable: BigDecimal): BigDecimal =
        if (taxable.signum() == 0) BigDecimal.ZERO else (vat * BigDecimal(100)).divide(taxable, 2, RoundingMode.HALF_UP)

    private fun label(kind: String) = kind.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun <T> Iterable<T>.sumBd(selector: (T) -> BigDecimal): BigDecimal = fold(BigDecimal.ZERO) { a, t -> a + selector(t) }
}
