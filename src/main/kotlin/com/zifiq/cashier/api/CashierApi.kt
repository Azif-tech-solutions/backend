package com.zifiq.cashier.api

import com.zifiq.cashier.dto.CatalogueItem
import com.zifiq.cashier.dto.SaleCompleteRequest
import com.zifiq.cashier.dto.SaleCompletion
import com.zifiq.cashier.dto.SaleCustomerRequest
import com.zifiq.cashier.dto.SaleInvoiceRequest
import com.zifiq.cashier.dto.SaleLinesRequest
import com.zifiq.cashier.dto.SaleRow
import com.zifiq.cashier.dto.SaleStartRequest
import com.zifiq.cashier.dto.SaleVersionRequest
import com.zifiq.cashier.dto.SaleView
import com.zifiq.cashier.dto.SaleVoidRequest
import com.zifiq.cashier.dto.TillCloseRequest
import com.zifiq.cashier.dto.TillOpenRequest
import com.zifiq.cashier.dto.TillSessionView
import com.zifiq.common.paging.PageParams
import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.ResponseEntity

@Tag(name = "Sales & POS — Till sessions", description = "Open a till with a float, see what it has taken, close it with a cash count (docs/09 §4.1)")
@SecurityRequirement(name = "bearerAuth")
interface TillSessionsApi {
    @Operation(summary = "Open a till (a TILL cash account at a showroom) with an opening float")
    fun open(request: TillOpenRequest): ApiEnvelope<TillSessionView>

    @Operation(summary = "The caller's open till session, with what it has taken so far (404 NO_OPEN_TILL_SESSION when none)")
    fun current(): ApiEnvelope<TillSessionView>

    @Operation(summary = "Till sessions (own only, unless cashier.sales.read-all)")
    fun list(locationId: UUID?, cashierUserId: UUID?, status: String?, from: LocalDate?, to: LocalDate?, withVarianceOnly: Boolean?, paging: PageParams): ApiEnvelope<PageResult<TillSessionView>>

    @Operation(summary = "A till session")
    fun get(id: UUID): ApiEnvelope<TillSessionView>

    @Operation(summary = "Close a till with the counted cash; the variance against expected cash is recorded (a supervisor may force-close another cashier's)")
    fun close(id: UUID, request: TillCloseRequest): ApiEnvelope<TillSessionView>
}

@Tag(name = "Sales & POS — Sales", description = "The POS cart, checkout, void, receipt and invoice on request (docs/09 §4.2)")
@SecurityRequirement(name = "bearerAuth")
interface SalesApi {
    @Operation(summary = "Sellable items at the showroom (defaults to the caller's open till's): price for the customer, available stock")
    fun catalogue(locationId: UUID?, q: String?, categoryId: UUID?, customerId: UUID?, includeOutOfStock: Boolean?, paging: PageParams): ApiEnvelope<PageResult<CatalogueItem>>

    @Operation(summary = "Start a sale on the caller's open till (an empty cart)")
    fun start(request: SaleStartRequest): ApiEnvelope<SaleView>

    @Operation(summary = "Set or clear the customer; every line is re-priced (dealer or retail)")
    fun setCustomer(id: UUID, request: SaleCustomerRequest): ApiEnvelope<SaleView>

    @Operation(summary = "Replace the cart's lines; prices come from the price list (quantities beyond stock are flagged, refused at completion)")
    fun setLines(id: UUID, request: SaleLinesRequest): ApiEnvelope<SaleView>

    @Operation(summary = "Clear the sale (no stock or money was touched)")
    fun abandon(id: UUID, request: SaleVersionRequest): ApiEnvelope<SaleView>

    @Operation(summary = "Complete the sale: payments must settle the total; stock out, receipts, wallet debits and the invoice in one transaction")
    fun complete(id: UUID, request: SaleCompleteRequest): ApiEnvelope<SaleCompletion>

    @Operation(summary = "A sale (open or completed)")
    fun get(id: UUID): ApiEnvelope<SaleView>

    @Operation(summary = "Sales (default COMPLETED, newest first; own only unless cashier.sales.read-all); ref = a TXN or INV number")
    fun list(locationId: UUID?, cashierUserId: UUID?, customerId: UUID?, channel: String?, status: String?, from: LocalDate?, to: LocalDate?, ref: String?,
             paging: PageParams): ApiEnvelope<PageResult<SaleRow>>

    @Operation(summary = "Void a completed sale within the void window: stock back, receipts and wallet debits reversed, invoice voided")
    fun void(id: UUID, request: SaleVoidRequest): ApiEnvelope<SaleView>

    @Operation(summary = "Issue an invoice for a walk-in sale after the fact")
    fun invoice(id: UUID, request: SaleInvoiceRequest): ApiEnvelope<SaleView>

    @Operation(summary = "Load a sent, valid pro-forma into this open sale (its customer, lines and quoted prices)")
    fun loadProForma(id: UUID, request: com.zifiq.cashier.dto.LoadProFormaRequest): ApiEnvelope<SaleView>

    @Operation(summary = "The itemised sales receipt as a PDF (DUPLICATE COPY once a reprint is recorded)")
    fun receipt(id: UUID): ResponseEntity<ByteArray>
}

@Tag(name = "Sales & POS — Deliver-later", description = "Supply position, customer waybills and the Pending Supply report (docs/09 §4.5–4.6)")
@SecurityRequirement(name = "bearerAuth")
interface WaybillsApi {
    @Operation(summary = "The outstanding position of a sale or invoice (what is still owed), by its TXN or INV reference")
    fun supplyLookup(ref: String): ApiEnvelope<com.zifiq.cashier.dto.SupplyPosition>

    @Operation(summary = "Release outstanding goods against a sale: a delivery (driver) or a collection (collector)")
    fun createWaybill(request: com.zifiq.cashier.dto.WaybillRequest): ApiEnvelope<com.zifiq.cashier.dto.WaybillView>

    @Operation(summary = "A customer waybill")
    fun getWaybill(id: UUID): ApiEnvelope<com.zifiq.cashier.dto.WaybillView>

    @Operation(summary = "The customer waybill as a PDF (DUPLICATE COPY once a reprint is recorded)")
    fun waybillPdf(id: UUID): ResponseEntity<ByteArray>

    @Operation(summary = "Acknowledge a waybill: the signed copy came back")
    fun acknowledgeWaybill(id: UUID, request: com.zifiq.cashier.dto.WaybillAcknowledgeRequest): ApiEnvelope<com.zifiq.cashier.dto.WaybillView>

    @Operation(summary = "Cancel a waybill the same day, before the goods left: stock and the reservation go back")
    fun cancelWaybill(id: UUID, request: com.zifiq.cashier.dto.WaybillCancelRequest): ApiEnvelope<com.zifiq.cashier.dto.WaybillView>

    @Operation(summary = "Customer waybills (newest first)")
    fun listWaybills(locationId: UUID?, saleId: UUID?, driverId: UUID?, status: String?, ref: String?, paging: PageParams): ApiEnvelope<PageResult<com.zifiq.cashier.dto.WaybillRow>>

    @Operation(summary = "Pending Supply: completed sales with goods still owed, oldest first")
    fun pendingSupply(invoice: String?, customerId: UUID?, status: String?, locationId: UUID?, from: LocalDate?, to: LocalDate?, paging: PageParams): ApiEnvelope<PageResult<com.zifiq.cashier.dto.PendingSupplyRow>>
}

@Tag(name = "Sales & POS — Instant Discount", description = "An Administrator's discount on a live sale, approved above a ceiling (docs/09 §4.3)")
@SecurityRequirement(name = "bearerAuth")
interface InstantDiscountApi {
    @Operation(summary = "Apply an Instant Discount to an open sale (applied at once, or sent for approval above the ceiling)")
    fun applyDiscount(id: UUID, request: com.zifiq.cashier.dto.InstantDiscountRequest): ApiEnvelope<com.zifiq.cashier.dto.InstantDiscountView>

    @Operation(summary = "Approve a pending discount")
    fun approveDiscount(id: UUID): ApiEnvelope<com.zifiq.cashier.dto.InstantDiscountView>

    @Operation(summary = "Reject a pending discount")
    fun rejectDiscount(id: UUID, request: com.zifiq.cashier.dto.RejectRequest): ApiEnvelope<com.zifiq.cashier.dto.InstantDiscountView>

    @Operation(summary = "Withdraw a live discount (the Administrator who applied it)")
    fun withdrawDiscount(id: UUID): ApiEnvelope<com.zifiq.cashier.dto.InstantDiscountView>

    @Operation(summary = "Recent instant discounts")
    fun listDiscounts(status: String?, reason: String?, saleId: UUID?, paging: PageParams): ApiEnvelope<PageResult<com.zifiq.cashier.dto.InstantDiscountRow>>
}

@Tag(name = "Sales & POS — Pro-formas", description = "Pro-forma invoices (quotations) and conversion (docs/09 §4.4)")
@SecurityRequirement(name = "bearerAuth")
interface ProFormaApi {
    @Operation(summary = "Create a pro-forma (quotation); ?send=true also sends it")
    fun createProForma(send: Boolean?, request: com.zifiq.cashier.dto.ProFormaRequest): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "Edit a draft pro-forma")
    fun updateProForma(id: UUID, request: com.zifiq.cashier.dto.ProFormaRequest): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "Send a draft pro-forma to the customer")
    fun sendProForma(id: UUID): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "The customer declined the quotation")
    fun declineProForma(id: UUID, request: com.zifiq.cashier.dto.RejectRequest): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "Withdraw (cancel) a pro-forma")
    fun cancelProForma(id: UUID, request: com.zifiq.cashier.dto.RejectRequest): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "Re-issue an expired or declined pro-forma as a new draft at today's prices")
    fun reissueProForma(id: UUID): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "Approve the line discounts on a pro-forma awaiting approval")
    fun approveProFormaDiscount(id: UUID): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "Reject the line discounts (back to draft to reduce them)")
    fun rejectProFormaDiscount(id: UUID, request: com.zifiq.cashier.dto.RejectRequest): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "Convert a confirmed pro-forma to an order: a reserved sale, invoiced on account")
    fun convertProForma(id: UUID, request: com.zifiq.cashier.dto.ProFormaConvertRequest): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "A pro-forma")
    fun getProForma(id: UUID): ApiEnvelope<com.zifiq.cashier.dto.ProFormaView>

    @Operation(summary = "Pro-formas (newest first)")
    fun listProFormas(status: String?, locationId: UUID?, customerId: UUID?, ref: String?, from: LocalDate?, to: LocalDate?, paging: PageParams): ApiEnvelope<PageResult<com.zifiq.cashier.dto.ProFormaRow>>

    @Operation(summary = "The pro-forma as a PDF")
    fun proFormaPdf(id: UUID): ResponseEntity<ByteArray>
}

@Tag(name = "Sales & POS — Reports", description = "Sales Report and Customers/Distributors Comprehensive (docs/09 §4.6)")
@SecurityRequirement(name = "bearerAuth")
interface SalesReportApi {
    @Operation(summary = "Daily Sales Report (one row per day and showroom)")
    fun salesDaily(locationId: UUID?, cashierUserId: UUID?, channel: String?, from: LocalDate, to: LocalDate): ApiEnvelope<com.zifiq.cashier.dto.SalesReport>

    @Operation(summary = "Customers/Distributors Comprehensive (kind: DISTRIBUTORS | NO_CREDIT | SPECIAL)")
    fun customerActivity(kind: String?, customerId: UUID?, locationId: UUID?, from: LocalDate, to: LocalDate): ApiEnvelope<com.zifiq.cashier.dto.CustomerActivityReport>
}
