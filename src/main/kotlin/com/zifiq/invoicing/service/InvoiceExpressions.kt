package com.zifiq.invoicing.service

import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.customers.spi.CustomerType
import com.zifiq.invoicing.model.InvoicesTable
import java.math.BigDecimal
import java.time.LocalDate
import org.jetbrains.exposed.sql.SortOrder

/**
 * The derived-status expressions for one request (docs/08 §1.3-3, §9). Tolerance and today are
 * rendered as SQL literals from a BigDecimal and a LocalDate, so nothing user-supplied reaches the
 * string. Every list (history, receivables, deliveries) filters, facets, sorts and labels its rows
 * with these same expressions, so they can never disagree.
 */
internal class InvoiceExpressions(tolerance: BigDecimal, val today: LocalDate) {
    val toleranceSql: String = tolerance.toPlainString()
    val todaySql = "date '$today'"

    val balance = RawExpression<BigDecimal>({ +"("; +InvoicesTable.taxInclusiveAmount; +" - "; +InvoicesTable.amountPaid; +" - "; +InvoicesTable.amountCredited; +")" })

    /** An issued invoice with more than the tolerance left to pay. */
    val open = RawExpression<Boolean>({ +"("; +InvoicesTable.status; +" = 'ISSUED' and ("; +balance; +") > "; +toleranceSql; +")" })

    val displayStatus = RawExpression<String>({
        +"(case when "; +InvoicesTable.status; +" = 'DRAFT' then 'DRAFT' when "; +InvoicesTable.status; +" = 'VOID' then 'VOID' when ("
        +balance; +") <= "; +toleranceSql; +" then 'PAID' when "; +InvoicesTable.dueDate; +" < "; +todaySql; +" then 'OVERDUE' when "
        +InvoicesTable.amountPaid; +" + "; +InvoicesTable.amountCredited; +" > 0 then 'PARTIALLY_PAID' else 'ISSUED' end)"
    })

    val daysOverdue = RawExpression<Int>({
        +"(case when "; +open; +" and "; +InvoicesTable.dueDate; +" < "; +todaySql
        +" then ("; +todaySql; +" - "; +InvoicesTable.dueDate; +") else 0 end)"
    })

    /** Receivables ageing on days past due; null for anything not open. */
    val ageingBucket = RawExpression<String?>({
        +"(case when not "; +open; +" then null when "; +InvoicesTable.dueDate; +" >= "; +todaySql; +" then 'NOT_DUE' when "
        +todaySql; +" - "; +InvoicesTable.dueDate; +" <= 30 then 'WITHIN_30' when "; +todaySql; +" - "; +InvoicesTable.dueDate
        +" <= 60 then 'DAYS_31_60' else 'OVER_60' end)"
    })

    val hasWaybills = RawExpression<Boolean>({ +"exists (select 1 from invoice_waybill_links w where w.invoice_id = "; +InvoicesTable.id; +")" })

    val hasCreditNotes = RawExpression<Boolean>({ +"exists (select 1 from credit_notes cn where cn.invoice_id = "; +InvoicesTable.id; +")" })

    /** The latest delivery's outbox state (PENDING, SENT, FAILED), null when never sent. */
    private val latestDelivery = RawExpression<String?>({
        +"(select o.status from invoice_deliveries d join notification_outbox o on o.id = d.notification_outbox_id where d.invoice_id = "
        +InvoicesTable.id; +" order by d.requested_at desc limit 1)"
    })

    /** SENT, FAILED, NO_EMAIL (never sent, no address) or NOT_SENT (queued, or never sent with an address). */
    val delivery = RawExpression<String>({
        +"(case coalesce("; +latestDelivery; +", case when coalesce("; +InvoicesTable.buyerEmail; +", '') = '' then 'NO_EMAIL' else 'NONE' end)"
        +" when 'SENT' then 'SENT' when 'FAILED' then 'FAILED' when 'NO_EMAIL' then 'NO_EMAIL' else 'NOT_SENT' end)"
    })

    val filterSpec = FilterSpec(
        fields = listOf(
            FilterField("status", "Status", FilterType.ENUM, displayStatus, STATUSES.map { FilterOption(it, label(it)) }, facetable = true),
            FilterField("customerId", "Customer", FilterType.UUID, InvoicesTable.customerId, aliases = setOf("customer"), lookup = "/api/v1/customers/lookup"),
            FilterField("customerType", "Customer type", FilterType.ENUM, InvoicesTable.buyerType,
                CustomerType.entries.map { FilterOption(it.name, it.label) }, facetable = true),
            FilterField("locationId", "Showroom", FilterType.LOCATION, InvoicesTable.locationId, aliases = setOf("showroom"), facetable = true),
            FilterField("sourceType", "Source", FilterType.ENUM, InvoicesTable.sourceType,
                listOf(FilterOption("POS_SALE", "POS sale"), FilterOption("PRO_FORMA", "Pro-forma")), aliases = setOf("source"), facetable = true),
            FilterField("invoiceKind", "Invoice kind", FilterType.ENUM, InvoicesTable.invoiceKind, listOf(FilterOption("B2B", "B2B"), FilterOption("B2C", "B2C")), facetable = true),
            FilterField("delivery", "Delivery", FilterType.ENUM, delivery, DELIVERIES.map { FilterOption(it, deliveryLabel(it)) }, facetable = true),
            FilterField("ageingBucket", "Ageing", FilterType.ENUM, ageingBucket, AGEING.map { FilterOption(it, ageingLabel(it)) }, aliases = setOf("ageing"), facetable = true),
            FilterField("issueDate", "Issue date", FilterType.DATE, InvoicesTable.issueDate, sortable = true),
            FilterField("dueDate", "Due date", FilterType.DATE, InvoicesTable.dueDate, sortable = true),
            FilterField("issuedAt", "Issued", FilterType.DATETIME, InvoicesTable.issuedAt, sortable = true),
            FilterField("voidedAt", "Voided", FilterType.DATETIME, InvoicesTable.voidedAt, sortable = true),
            FilterField("lastPaymentAt", "Last payment", FilterType.DATETIME, InvoicesTable.lastPaymentAt, sortable = true),
            FilterField("number", "Number", FilterType.TEXT, InvoicesTable.number, sortable = true),
            FilterField("buyerName", "Customer name", FilterType.TEXT, InvoicesTable.buyerName, sortable = true),
            FilterField("total", "Total", FilterType.MONEY, InvoicesTable.taxInclusiveAmount, sortable = true),
            FilterField("paid", "Paid", FilterType.MONEY, InvoicesTable.amountPaid),
            FilterField("credited", "Credited", FilterType.MONEY, InvoicesTable.amountCredited),
            FilterField("balance", "Balance", FilterType.MONEY, balance, sortable = true),
            FilterField("daysOverdue", "Days overdue", FilterType.NUMBER, daysOverdue, sortable = true),
            FilterField("overdueOnly", "Overdue only", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +daysOverdue; +" > 0)" })),
            FilterField("issuedBy", "Issued by", FilterType.UUID, InvoicesTable.issuedByUserId),
            FilterField("voidedBy", "Voided by", FilterType.UUID, InvoicesTable.voidedByUserId),
            FilterField("correspondentId", "Correspondent", FilterType.UUID, InvoicesTable.correspondentId, lookup = "/api/v1/customers/correspondents/lookup"),
            FilterField("vatEnabled", "VAT collected", FilterType.BOOLEAN, InvoicesTable.vatEnabled),
            FilterField("hasWaybills", "Has waybills", FilterType.BOOLEAN, hasWaybills),
            FilterField("hasCreditNotes", "Has credit notes", FilterType.BOOLEAN, hasCreditNotes),
            FilterField("hasReplacement", "Reissued", FilterType.BOOLEAN, RawExpression<Boolean>({ +"("; +InvoicesTable.replacedByInvoiceId; +" is not null)" })),
        ),
        primaryDateKey = "issueDate",
        defaultSort = listOf("issuedAt" to SortOrder.DESC),
        tiebreaker = InvoicesTable.id,
    )

    val searchSpec = SearchSpec(
        fields = listOf(
            SearchField(InvoicesTable.number, SearchKind.REFERENCE, "Number"),
            SearchField(InvoicesTable.sourceRef, SearchKind.REFERENCE, "Source reference"),
            SearchField(InvoicesTable.proFormaRef, SearchKind.REFERENCE, "Pro-forma"),
            SearchField(InvoicesTable.buyerName, SearchKind.TRIGRAM, "Customer"),
            SearchField(InvoicesTable.buyerPhone, SearchKind.PHONE, "Phone"),
            SearchField(InvoicesTable.buyerTin, SearchKind.CODE, "TIN"),
            SearchField(InvoicesTable.buyerEmail, SearchKind.CODE, "E-mail"),
            SearchField(RawExpression<String>({ +"invoices.search_vector" }), SearchKind.FULLTEXT, "Text"),
            SearchField(InvoicesTable.taxInclusiveAmount, SearchKind.AMOUNT, "Total"),
        ),
        qualifiers = filterSpec,
    )

    companion object {
        val STATUSES = listOf("DRAFT", "ISSUED", "PARTIALLY_PAID", "PAID", "OVERDUE", "VOID")
        val DELIVERIES = listOf("SENT", "FAILED", "NO_EMAIL", "NOT_SENT")
        val AGEING = listOf("NOT_DUE", "WITHIN_30", "DAYS_31_60", "OVER_60")

        fun label(status: String): String = when (status) {
            "DRAFT" -> "Draft"
            "ISSUED" -> "Issued"
            "PARTIALLY_PAID" -> "Partially Paid"
            "PAID" -> "Paid"
            "OVERDUE" -> "Overdue"
            "VOID" -> "Void"
            else -> status
        }

        fun deliveryLabel(code: String): String = when (code) {
            "SENT" -> "Sent"
            "FAILED" -> "Failed"
            "NO_EMAIL" -> "No email on file"
            else -> "Not sent"
        }

        fun ageingLabel(code: String): String = when (code) {
            "NOT_DUE" -> "Not yet due"
            "WITHIN_30" -> "Within 30 days"
            "DAYS_31_60" -> "31–60 days"
            "OVER_60" -> "Over 60 days"
            else -> code
        }
    }
}
