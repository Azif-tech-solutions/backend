package com.zifiq.invoicing.service

import com.zifiq.inventory.repository.StockSql
import com.zifiq.common.money.Money
import com.zifiq.account.spi.VatRates
import com.zifiq.account.spi.VatSetting
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.json.Json
import com.zifiq.common.search.RawExpression
import com.zifiq.common.tax.HsnCodesTable
import com.zifiq.common.tax.HsnSectionsTable
import com.zifiq.common.tax.IsicClassesTable
import com.zifiq.common.tax.IsicSectionsTable
import com.zifiq.common.tax.VatCategoriesTable
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerStatus
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.inventory.service.StockLocations
import com.zifiq.invoicing.model.InvoiceLinesTable
import com.zifiq.invoicing.model.InvoicesTable
import com.zifiq.invoicing.spi.InvoiceLineRequest
import com.zifiq.invoicing.spi.WalkInBuyer
import com.zifiq.platform.tenancy.model.LocationsTable
import com.zifiq.settings.spi.CompanyProfileProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/** The buyer as printed on the invoice: a registered customer's details or a walk-in block (docs/08 §1.3-6). */
data class BuyerSnapshot(
    val customerId: UUID?,
    val name: String,
    val type: String?,
    val email: String?,
    val phone: String?,
    val tin: String?,
    val businessDescription: String?,
    val streetAddress: String?,
    val city: String?,
    val postalCode: String?,
    val stateCode: String?,
    val lgaCode: String?,
    val countryCode: String,
    val correspondentId: UUID?,
) {
    /** NRS invoice_kind: business-to-business when the buyer has a TIN. */
    val invoiceKind: String get() = if (!tin.isNullOrBlank()) "B2B" else "B2C"
}

/** One priced line: quantities and prices as sold, VAT from the issuing location (docs/08 §1.3-5). */
data class PricedLine(
    val stockItemId: UUID,
    val itemCode: String,
    val itemName: String,
    val quantity: BigDecimal,
    val unitName: String,
    val nrsUnitCode: String?,
    val unitPrice: BigDecimal,
    val discountAmount: BigDecimal,
    val discountRate: BigDecimal?,
    val lineNet: BigDecimal,
    val vatCategoryId: UUID,
    val vatCategoryCode: String,
    val nrsTaxCategoryCode: String,
    val vatRate: BigDecimal,
    val vatAmount: BigDecimal,
    val lineTotal: BigDecimal,
    val isService: Boolean,
    val hsnCode: String?,
    val hsnSectionCode: String?,
    val productCategory: String?,
    val isicCode: String?,
    val serviceCategory: String?,
) {
    val gross: BigDecimal get() = Money.round(quantity * unitPrice)
}

data class InvoiceTotalsCalc(val lineExtension: BigDecimal, val discount: BigDecimal, val vat: BigDecimal, val total: BigDecimal) {
    companion object {
        fun of(lines: List<PricedLine>) = InvoiceTotalsCalc(
            lineExtension = lines.fold(BigDecimal.ZERO) { s, it -> s + it.lineNet }.setScale(2),
            discount = lines.fold(BigDecimal.ZERO) { s, it -> s + it.discountAmount }.setScale(2),
            vat = lines.fold(BigDecimal.ZERO) { s, it -> s + it.vatAmount }.setScale(2),
            total = lines.fold(BigDecimal.ZERO) { s, it -> s + it.lineTotal }.setScale(2),
        )
    }
}

/**
 * Builds what goes on an invoice: validates the issuing showroom and buyer, prices every line with
 * VAT from the location's setting on the issue date, and snapshots issuer and buyer so later edits
 * in Settings or Customers never change an issued invoice (rule 14). Runs in the caller's transaction.
 */
@Component
class InvoiceBuilder(
    private val support: InvoiceSupport,
    private val stockLocations: StockLocations,
    private val vatRates: VatRates,
    private val customers: CustomerDirectory,
    private val companyProfile: CompanyProfileProvider,
) {
    /** Invoices are issued by a showroom that is trading (not pending opening or suspended). */
    fun requireIssuingShowroom(locationId: UUID) {
        val location = stockLocations.get(locationId)
        if (location.isArea || location.type != "SHOWROOM") throw ApiErrors.invalidLocation("Invoices are issued by a showroom")
        stockLocations.requireTrading(locationId)
    }

    fun vatAt(locationId: UUID, onDate: java.time.LocalDate): VatSetting = vatRates.forLocation(locationId, onDate)

    /** Exactly one of a registered customer (ACTIVE or WATCH) or a walk-in buyer block. */
    fun buyer(customerId: UUID?, walkIn: WalkInBuyer?, emailOverride: String? = null, correspondentId: UUID? = null): BuyerSnapshot {
        if ((customerId == null) == (walkIn == null)) {
            throw ApiErrors.validationFailed(mapOf("customerId" to "give either a customer or walk-in buyer details, not both"))
        }
        if (customerId != null) {
            val customer = customers.get(customerId)
            if (customer.status != CustomerStatus.ACTIVE && customer.status != CustomerStatus.WATCH) throw ApiErrors.customerNotActive(customer.status.name)
            return BuyerSnapshot(
                customerId = customer.id, name = customer.name, type = customer.type.name,
                email = emailOverride?.trim()?.ifEmpty { null } ?: customer.email, phone = customer.phone, tin = customer.tin,
                businessDescription = customer.businessDescription, streetAddress = customer.streetAddress, city = customer.city,
                postalCode = null, stateCode = customer.stateCode, lgaCode = customer.lgaCode, countryCode = customer.countryCode,
                correspondentId = correspondentId ?: customer.correspondentId,
            )
        }
        val buyer = walkIn!!
        if (buyer.name.isBlank()) throw ApiErrors.validationFailed(mapOf("walkInBuyer.name" to "must not be blank"))
        return BuyerSnapshot(
            customerId = null, name = buyer.name.trim(), type = null,
            email = emailOverride?.trim()?.ifEmpty { null } ?: buyer.email?.trim()?.ifEmpty { null }, phone = buyer.phone?.trim()?.ifEmpty { null },
            tin = buyer.tin?.trim()?.ifEmpty { null }, businessDescription = null, streetAddress = buyer.address?.trim()?.ifEmpty { null },
            city = null, postalCode = null, stateCode = buyer.stateCode?.trim()?.ifEmpty { null }, lgaCode = null, countryCode = "NG",
            correspondentId = correspondentId,
        )
    }

    /** The buyer as already stored on an invoice (a walk-in has no record to re-read). */
    fun storedBuyer(row: ResultRow, emailOverride: String? = null) = BuyerSnapshot(
        customerId = row[InvoicesTable.customerId], name = row[InvoicesTable.buyerName], type = row[InvoicesTable.buyerType],
        email = emailOverride?.trim()?.ifEmpty { null } ?: row[InvoicesTable.buyerEmail], phone = row[InvoicesTable.buyerPhone],
        tin = row[InvoicesTable.buyerTin], businessDescription = row[InvoicesTable.buyerBusinessDescription],
        streetAddress = row[InvoicesTable.buyerStreetAddress], city = row[InvoicesTable.buyerCity], postalCode = row[InvoicesTable.buyerPostalCode],
        stateCode = row[InvoicesTable.buyerStateCode], lgaCode = row[InvoicesTable.buyerLgaCode], countryCode = row[InvoicesTable.buyerCountryCode],
        correspondentId = row[InvoicesTable.correspondentId],
    )

    /** For a draft or reissue: the buyer re-read from Customers when registered, else the stored walk-in block. */
    fun refreshedBuyer(row: ResultRow, emailOverride: String? = null): BuyerSnapshot {
        val customerId = row[InvoicesTable.customerId] ?: return storedBuyer(row, emailOverride)
        return buyer(customerId, null, emailOverride ?: row[InvoicesTable.buyerEmail], row[InvoicesTable.correspondentId])
    }

    fun price(inputs: List<InvoiceLineRequest>, vat: VatSetting): List<PricedLine> {
        if (inputs.isEmpty()) throw ApiErrors.validationFailed(mapOf("lines" to "an invoice needs at least one line"))
        val ids = inputs.map { it.stockItemId }.toSet()
        val items = StockItemsTable.select(
            StockItemsTable.id, StockItemsTable.code, StockItemsTable.name, StockItemsTable.status, StockItemsTable.vatCategoryId,
            StockItemsTable.hsnSectionId, StockItemsTable.isicSectionId, StockItemsTable.hsnCode, StockItemsTable.isicCode,
            StockItemsTable.nrsUnitCode, StockItemsTable.isService, unitName,
        ).where { (StockItemsTable.tenantId eq support.tenantId()) and (StockItemsTable.id inList ids) }
            .associateBy { it[StockItemsTable.id].value }
        val vatCategories = VatCategoriesTable.selectAll().where { VatCategoriesTable.id inList items.values.map { it[StockItemsTable.vatCategoryId] }.toSet() }
            .associateBy { it[VatCategoriesTable.id].value }
        val hsnSections = items.values.mapNotNull { it[StockItemsTable.hsnSectionId] }.toSet().let { s ->
            if (s.isEmpty()) emptyMap() else HsnSectionsTable.selectAll().where { HsnSectionsTable.id inList s }.associateBy { it[HsnSectionsTable.id].value }
        }
        val isicSections = items.values.mapNotNull { it[StockItemsTable.isicSectionId] }.toSet().let { s ->
            if (s.isEmpty()) emptyMap() else IsicSectionsTable.selectAll().where { IsicSectionsTable.id inList s }.associateBy { it[IsicSectionsTable.id].value }
        }
        val hsnCodes = items.values.mapNotNull { it[StockItemsTable.hsnCode] }.toSet().let { s ->
            if (s.isEmpty()) emptyMap() else HsnCodesTable.selectAll().where { HsnCodesTable.code inList s }.associate { it[HsnCodesTable.code] to it[HsnCodesTable.description] }
        }
        val isicClasses = items.values.mapNotNull { it[StockItemsTable.isicCode] }.toSet().let { s ->
            if (s.isEmpty()) emptyMap() else IsicClassesTable.selectAll().where { IsicClassesTable.code inList s }.associate { it[IsicClassesTable.code] to it[IsicClassesTable.description] }
        }

        return inputs.mapIndexed { index, input ->
            val field = "lines[$index]"
            val item = items[input.stockItemId] ?: throw ApiErrors.validationFailed(mapOf("$field.stockItemId" to "unknown stock item"))
            if (item[StockItemsTable.status] != "ACTIVE") throw ApiErrors.validationFailed(mapOf("$field.stockItemId" to "${item[StockItemsTable.code]} is ${item[StockItemsTable.status].lowercase()}"))
            if (input.quantity.signum() <= 0) throw ApiErrors.validationFailed(mapOf("$field.quantity" to "must be more than 0"))
            if (input.unitPrice.signum() < 0) throw ApiErrors.validationFailed(mapOf("$field.unitPrice" to "can't be negative"))
            val quantity = input.quantity.setScale(3, RoundingMode.HALF_UP)
            val unitPrice = Money.round(input.unitPrice)
            val value = quantity * unitPrice
            val rate = input.discountRate?.takeIf { it.signum() > 0 }
            if (rate != null && rate > BigDecimal(100)) throw ApiErrors.discountExceedsLine()
            val discount = when {
                input.discountAmount.signum() > 0 -> Money.round(input.discountAmount)
                rate != null -> Money.round(value * rate / BigDecimal(100))
                else -> BigDecimal.ZERO.setScale(2)
            }
            if (discount.signum() < 0 || discount > value) throw ApiErrors.discountExceedsLine()
            val net = Money.round(value - discount)

            val category = vatCategories.getValue(item[StockItemsTable.vatCategoryId])
            val code = category[VatCategoriesTable.code]
            // Standard-rated items take the location's rate while it collects VAT; zero-rated and exempt never do.
            val vatRate = if (code == "STANDARD_RATE" && vat.enabled) vat.rate.setScale(2) else BigDecimal.ZERO.setScale(2)
            val vatAmount = Money.round(net * vatRate / BigDecimal(100))

            val isService = item[StockItemsTable.isService]
            val hsnSection = item[StockItemsTable.hsnSectionId]?.let(hsnSections::get)
            val isicSection = item[StockItemsTable.isicSectionId]?.let(isicSections::get)
            val hsnCode = if (isService) null else item[StockItemsTable.hsnCode]
            val isicCode = if (isService) item[StockItemsTable.isicCode] else null
            PricedLine(
                stockItemId = input.stockItemId, itemCode = item[StockItemsTable.code], itemName = item[StockItemsTable.name],
                quantity = quantity, unitName = item[unitName] ?: "Unit", nrsUnitCode = item[StockItemsTable.nrsUnitCode],
                unitPrice = unitPrice, discountAmount = discount, discountRate = rate?.setScale(4, RoundingMode.HALF_UP),
                lineNet = net, vatCategoryId = item[StockItemsTable.vatCategoryId], vatCategoryCode = code,
                nrsTaxCategoryCode = category[VatCategoriesTable.nrsTaxCategoryCode], vatRate = vatRate, vatAmount = vatAmount,
                lineTotal = net + vatAmount, isService = isService,
                hsnCode = hsnCode, hsnSectionCode = if (isService) null else hsnSection?.get(HsnSectionsTable.code),
                productCategory = if (isService) null else (hsnCode?.let(hsnCodes::get) ?: hsnSection?.get(HsnSectionsTable.name)),
                isicCode = isicCode,
                serviceCategory = if (isService) (isicCode?.let(isicClasses::get) ?: isicSection?.get(IsicSectionsTable.name)) else null,
            )
        }
    }

    /** The issuer block: company profile from Settings plus the issuing showroom. */
    fun issuerSnapshot(locationId: UUID): String {
        val company = companyProfile.get()
        val location = LocationsTable.selectAll().where { LocationsTable.id eq locationId }.single()
        val companyAddress = listOfNotNull(company.streetAddress, company.city, company.stateName).filter { it.isNotBlank() }.joinToString(", ")
        val locationAddress = listOfNotNull(location[LocationsTable.streetAddress] ?: location[LocationsTable.address], location[LocationsTable.city], location[LocationsTable.state])
            .filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null }
        return Json.write(
            mapOf(
                "tradingName" to company.tradingName, "legalName" to company.legalName, "tin" to company.tin,
                "vatRegistrationNumber" to company.vatRegistrationNumber, "nrsBusinessId" to company.nrsBusinessId?.toString(),
                "businessDescription" to company.businessDescription, "email" to company.email, "phone" to company.phone,
                "address" to companyAddress, "streetAddress" to company.streetAddress, "city" to company.city, "postalCode" to company.postalCode,
                "stateCode" to company.stateCode, "lgaCode" to company.lgaCode, "countryCode" to company.countryCode,
                "documentFooter" to company.documentFooter, "logoPresent" to (company.logo != null),
                "locationName" to location[LocationsTable.name], "locationAddress" to locationAddress,
            ),
        )
    }

    fun insertLines(invoiceId: UUID, lines: List<PricedLine>) {
        val tenantId = support.tenantId()
        InvoiceLinesTable.batchInsert(lines.withIndex(), shouldReturnGeneratedValues = false) { (index, line) ->
            this[InvoiceLinesTable.tenantId] = tenantId
            this[InvoiceLinesTable.invoiceId] = invoiceId
            this[InvoiceLinesTable.lineNo] = index + 1
            this[InvoiceLinesTable.stockItemId] = line.stockItemId
            this[InvoiceLinesTable.itemCode] = line.itemCode
            this[InvoiceLinesTable.itemName] = line.itemName.take(200)
            this[InvoiceLinesTable.itemDescription] = null
            this[InvoiceLinesTable.sellersItemIdentification] = line.itemCode
            this[InvoiceLinesTable.quantity] = line.quantity
            this[InvoiceLinesTable.unitName] = line.unitName.take(60)
            this[InvoiceLinesTable.nrsUnitCode] = line.nrsUnitCode
            this[InvoiceLinesTable.baseQuantity] = BigDecimal.ONE
            this[InvoiceLinesTable.unitPrice] = line.unitPrice
            this[InvoiceLinesTable.discountAmount] = line.discountAmount
            this[InvoiceLinesTable.discountRate] = line.discountRate
            this[InvoiceLinesTable.lineNet] = line.lineNet
            this[InvoiceLinesTable.vatCategoryId] = line.vatCategoryId
            this[InvoiceLinesTable.vatCategoryCode] = line.vatCategoryCode
            this[InvoiceLinesTable.nrsTaxCategoryCode] = line.nrsTaxCategoryCode
            this[InvoiceLinesTable.vatRate] = line.vatRate
            this[InvoiceLinesTable.vatAmount] = line.vatAmount
            this[InvoiceLinesTable.lineTotal] = line.lineTotal
            this[InvoiceLinesTable.isService] = line.isService
            this[InvoiceLinesTable.hsnCode] = line.hsnCode
            this[InvoiceLinesTable.hsnSectionCode] = line.hsnSectionCode
            this[InvoiceLinesTable.productCategory] = line.productCategory?.take(500)
            this[InvoiceLinesTable.isicCode] = line.isicCode
            this[InvoiceLinesTable.serviceCategory] = line.serviceCategory?.take(300)
        }
    }

    /** The stored lines as inputs, to re-price them (draft issue, reissue) with prices kept as sold. */
    fun storedLineInputs(invoiceId: UUID): List<InvoiceLineRequest> =
        InvoiceLinesTable.selectAll().where { InvoiceLinesTable.invoiceId eq invoiceId }.orderBy(InvoiceLinesTable.lineNo).map {
            InvoiceLineRequest(
                stockItemId = it[InvoiceLinesTable.stockItemId], quantity = it[InvoiceLinesTable.quantity], unitPrice = it[InvoiceLinesTable.unitPrice],
                discountAmount = it[InvoiceLinesTable.discountAmount], discountRate = it[InvoiceLinesTable.discountRate],
            )
        }

    private val unitName = StockSql.unitName(StockItemsTable.unitOfMeasureId)
}
