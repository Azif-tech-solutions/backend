package com.zifiq.account.service

import com.zifiq.platform.directory.ref
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.Names
import com.zifiq.common.money.Money
import com.zifiq.account.dto.WalletFundingRequest
import com.zifiq.account.dto.WalletFundingRow
import com.zifiq.account.dto.WalletWithdrawalRequest
import com.zifiq.account.dto.WalletWithdrawalRow
import com.zifiq.account.model.BankAccountsTable
import com.zifiq.account.model.CashAccountsTable
import com.zifiq.account.model.PaymentReceiptsTable
import com.zifiq.account.model.WalletFundingsTable
import com.zifiq.account.model.WalletWithdrawalsTable
import com.zifiq.account.repository.LedgerPosting
import com.zifiq.account.spi.Channel
import com.zifiq.account.spi.ReceiptRequest
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.filter.FilterField
import com.zifiq.common.filter.FilterMetadata
import com.zifiq.common.filter.FilterOption
import com.zifiq.common.filter.FilterSpec
import com.zifiq.common.filter.FilterType
import com.zifiq.common.filter.ListSearchRequest
import com.zifiq.common.paging.PageMeta
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.common.search.RawExpression
import com.zifiq.common.search.SearchField
import com.zifiq.common.search.SearchKind
import com.zifiq.common.search.SearchSpec
import com.zifiq.common.search.allOf
import com.zifiq.customers.spi.BalanceType
import com.zifiq.customers.spi.CustomerDirectory
import com.zifiq.customers.spi.CustomerRef
import com.zifiq.customers.spi.CustomerType
import com.zifiq.customers.spi.LedgerEntryType
import com.zifiq.customers.spi.WalletLedger
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.security.otp.OtpChallengeService
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service
import com.zifiq.customers.spi.LedgerPosting as CustomerPosting

/**
 * Wallet and deposit money (docs/07 §4.12–4.13): funding a dealer's wallet or a special customer's
 * deposit (credited only once the money is confirmed — PRD rule 7), and paying money out of them:
 * dealer-requested withdrawals behind an Admin-issued OTP for exactly that dealer and amount (rule
 * 8), deposit refunds, and Management-authorised recoveries. Balances live in Customers' ledger,
 * reached only through its spi.
 */
@Service
class WalletMoneyService(
    private val support: AccountSupport,
    private val tx: TenantScopedTransaction,
    private val receipts: ReceiptWriter,
    private val moves: MoneyMoves,
    private val sequences: DocumentSequenceService,
    private val customers: CustomerDirectory,
    private val walletLedger: WalletLedger,
    private val otp: OtpChallengeService,
    private val permissions: PermissionAuthorizer,
) : ReceiptSourceHandler {
    // ---- Funding (§4.12) ----

    fun fund(request: WalletFundingRequest): WalletFundingRow = tx.execute {
        val customer = customers.getForUpdate(request.customerId!!)
        val balanceType = BalanceType.valueOf(request.balanceType!!)
        checkHolder(customer, balanceType)
        val channel = Channel.valueOf(request.channel!!)
        val amount = Money.round(request.amount!!)
        val id = UUID.randomUUID()
        val ref = sequences.next("WF")
        val receipt = receipts.record(
            ReceiptRequest(
                locationId = request.locationId, cashAccountId = request.cashAccountId, customerId = customer.id, customerName = customer.name,
                channel = channel, amount = amount, sourceModule = ReceiptWriter.SOURCE_MODULE,
                sourceType = if (balanceType == BalanceType.WALLET) "WALLET_FUNDING" else "DEPOSIT_FUNDING", sourceId = id, sourceRef = ref,
                receivingBankAccountId = request.receivingBankAccountId, terminalRef = request.terminalRef, payerReference = request.payerReference,
            ),
        )
        WalletFundingsTable.insert {
            it[WalletFundingsTable.id] = EntityID(id, WalletFundingsTable)
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[WalletFundingsTable.ref] = ref
            it[customerId] = customer.id
            it[customerCode] = customer.code
            it[customerName] = customer.name
            it[WalletFundingsTable.balanceType] = balanceType.name
            it[WalletFundingsTable.channel] = channel.name
            it[WalletFundingsTable.amount] = amount
            it[locationId] = request.locationId
            it[paymentReceiptId] = receipt.id
            it[status] = "AWAITING_CONFIRMATION"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }
        support.record("account.wallet-funding.created", "wallet_funding", id, ref,
            "$ref: ${Money.naira(amount)} ${balanceType.name.lowercase()} funding for ${customer.code} ${customer.name} by ${ReceiptService.channelName(channel.name).lowercase()}",
            mapOf("amount" to amount, "channel" to channel.name, "receipt" to receipt.ref), request.locationId)
        // Cash is in the till already: credit now. Anything else waits for Accounts to confirm the receipt.
        if (channel == Channel.CASH) credit(id)
        fundingRow(id)
    }

    fun fundings(request: ListSearchRequest): PageResult<WalletFundingRow> = tx.execute {
        val context = support.filterContext()
        val parsed = fundingSearch.parse(request.q)
        val condition = listOfNotNull(
            WalletFundingsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { WalletFundingsTable.locationId inList it },
            fundingSearch.condition(parsed),
            fundingFilters.fromParams(request.params, context),
            fundingFilters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) fundingSearch.rank(parsed) + fundingFilters.sort(emptyList(), context) else fundingFilters.sort(request.sortParams, context)
        val joined = fundingJoin()
        val page = Paging.fetch(joined.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) {
            val all = joined.select(WalletFundingsTable.status, WalletFundingsTable.amount).where(condition).toList()
            fun sum(status: String?) = Money.round(all.filter { status == null || it[WalletFundingsTable.status] == status }.fold(BigDecimal.ZERO) { s, it -> s + it[WalletFundingsTable.amount] })
            mapOf("total" to sum(null), "confirmed" to sum("CONFIRMED"), "awaiting" to sum("AWAITING_CONFIRMATION"))
        } else null
        page.withItems(toFundingRows(page.items)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun fundingFilterMetadata(): FilterMetadata = tx.execute { fundingFilters.metadata(support.filterContext()) }

    // ---- ReceiptSourceHandler: what Accounts' decision on the receipt means for the funding ----

    override val sourceTypes = setOf("WALLET_FUNDING", "DEPOSIT_FUNDING")

    override fun approved(receipt: ResultRow) = credit(receipt[PaymentReceiptsTable.sourceId])

    override fun declined(receipt: ResultRow, note: String) {
        val id = receipt[PaymentReceiptsTable.sourceId]
        WalletFundingsTable.update({ WalletFundingsTable.id eq id }) { it[status] = "DECLINED" }
        val funding = WalletFundingsTable.selectAll().where { WalletFundingsTable.id eq id }.single()
        support.record("account.wallet-funding.declined", "wallet_funding", id, funding[WalletFundingsTable.ref],
            "${funding[WalletFundingsTable.ref]} declined: the wallet was not credited — $note", mapOf("amount" to funding[WalletFundingsTable.amount]), funding[WalletFundingsTable.locationId])
    }

    /** Takes back what the funding credited; refused when it has already been spent. */
    override fun voided(receipt: ResultRow, reason: String) {
        val id = receipt[PaymentReceiptsTable.sourceId]
        val funding = WalletFundingsTable.selectAll().where { WalletFundingsTable.id eq id }.forUpdate().single()
        funding[WalletFundingsTable.customerLedgerEntryId]?.let { walletLedger.reverse(it, "Funding ${funding[WalletFundingsTable.ref]} voided: $reason", ReceiptWriter.SOURCE_MODULE) }
        WalletFundingsTable.update({ WalletFundingsTable.id eq id }) { it[status] = "VOIDED" }
    }

    private fun credit(fundingId: UUID) {
        val funding = WalletFundingsTable.selectAll().where { WalletFundingsTable.id eq fundingId }.forUpdate().single()
        val ref = funding[WalletFundingsTable.ref]
        val entry = walletLedger.credit(
            CustomerPosting(
                customerId = funding[WalletFundingsTable.customerId], balanceType = BalanceType.valueOf(funding[WalletFundingsTable.balanceType]),
                entryType = LedgerEntryType.FUNDING, amount = funding[WalletFundingsTable.amount], reference = ref,
                description = "${if (funding[WalletFundingsTable.balanceType] == "WALLET") "Wallet" else "Deposit"} funding by ${ReceiptService.channelName(funding[WalletFundingsTable.channel]).lowercase()}",
                paymentMethod = ReceiptService.channelName(funding[WalletFundingsTable.channel]), sourceModule = ReceiptWriter.SOURCE_MODULE,
                sourceType = "WALLET_FUNDING", sourceId = fundingId, locationId = funding[WalletFundingsTable.locationId],
            ),
        )
        WalletFundingsTable.update({ WalletFundingsTable.id eq fundingId }) { it[status] = "CONFIRMED"; it[customerLedgerEntryId] = entry.id }
        support.record("account.wallet-funding.confirmed", "wallet_funding", fundingId, ref,
            "$ref confirmed: ${Money.naira(funding[WalletFundingsTable.amount])} credited to ${funding[WalletFundingsTable.customerCode]} (balance ${Money.naira(entry.balanceAfter)})",
            mapOf("amount" to funding[WalletFundingsTable.amount], "balanceAfter" to entry.balanceAfter), funding[WalletFundingsTable.locationId])
    }

    // ---- Withdrawals and deposit refunds (§4.13) ----

    /**
     * Dealer requests and deposit refunds pay money out (CASH or BANK); company recoveries claw the
     * balance back and pay nobody (NONE). A dealer request needs a verified OTP issued for this
     * customer and this amount, used once.
     */
    fun withdraw(request: WalletWithdrawalRequest): WalletWithdrawalRow = tx.execute {
        val reason = request.reason!!
        if (reason == "INVOICE_SETTLEMENT") throw ApiErrors.useInvoicePayment()
        val dealerInitiated = reason in DEALER_REASONS
        val balanceType = BalanceType.valueOf(request.balanceType!!)
        val payoutKind = request.payoutKind!!
        val amount = Money.round(request.amount!!)
        val date = request.withdrawalDate!!
        support.requireNotFuture("withdrawalDate", date)
        request.locationId?.let { support.validateLocation(it) }

        if (dealerInitiated) {
            if (!support.can("accounting.wallet.withdraw")) throw ApiErrors.accessDenied()
            if (payoutKind == "NONE") throw ApiErrors.validationFailed(mapOf("payoutKind" to "a ${reason.lowercase().replace('_', ' ')} pays the money out: CASH or BANK"))
            if ((reason == "DEPOSIT_REFUND") != (balanceType == BalanceType.DEPOSIT)) {
                throw ApiErrors.validationFailed(mapOf("balanceType" to "a deposit refund comes from the DEPOSIT, a dealer request from the WALLET"))
            }
        } else {
            if (!support.can("accounting.wallet.recover")) throw ApiErrors.accessDenied()
            if (payoutKind != "NONE") throw ApiErrors.validationFailed(mapOf("payoutKind" to "a recovery or correction pays nobody: NONE"))
            val authoriser = request.authorisedByUserId ?: throw ApiErrors.validationFailed(mapOf("authorisedByUserId" to "is required for a company-initiated withdrawal"))
            if (!permissions.hasPermission(authoriser, "accounting.wallet.recover")) {
                throw ApiErrors.validationFailed(mapOf("authorisedByUserId" to "must be someone who may authorise wallet recoveries"))
            }
        }

        val customer = customers.getForUpdate(request.customerId!!)
        if (dealerInitiated && reason == "DEALER_REQUEST" && support.moduleSettings().boolean(AccountingSettings.WITHDRAWAL_OTP_REQUIRED)) {
            checkOtp(request.otpChallengeId, customer, amount)
        }
        val payout = when (payoutKind) {
            "CASH" -> moves.cash(request.payoutCashAccountId ?: throw ApiErrors.validationFailed(mapOf("payoutCashAccountId" to "is required for a cash payout")))
            "BANK" -> moves.bank(request.payoutBankAccountId ?: throw ApiErrors.validationFailed(mapOf("payoutBankAccountId" to "is required for a bank payout")))
            else -> null
        }
        if (payout != null && !payout.bank && amount > payout.balance) throw ApiErrors.insufficientCash(payout.code, payout.balance, amount)

        val id = UUID.randomUUID()
        val ref = sequences.next("WW")
        val before = if (balanceType == BalanceType.WALLET) customer.walletBalance else customer.depositBalance
        val entry = walletLedger.debit(
            CustomerPosting(
                customerId = customer.id, balanceType = balanceType, entryType = LedgerEntryType.WITHDRAWAL_DEBIT, amount = amount, reference = ref,
                description = "${reason.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)}" + (request.note?.trim()?.ifEmpty { null }?.let { ": $it" } ?: ""),
                paymentMethod = payoutKind, sourceModule = ReceiptWriter.SOURCE_MODULE, sourceType = "WALLET_WITHDRAWAL", sourceId = id, locationId = request.locationId,
                reason = reason,
            ),
        )
        val posted = payout?.let {
            moves.out(it, amount, LedgerPosting(ref, "${if (reason == "DEPOSIT_REFUND") "Deposit refund" else "Wallet withdrawal"} to ${customer.code} ${customer.name}" +
                (request.paidVia?.trim()?.ifEmpty { null }?.let { via -> " — $via" } ?: ""), if (reason == "DEPOSIT_REFUND") "DEPOSIT_REFUND" else "WALLET_WITHDRAWAL", id,
                entryDate = date, userId = support.userId()))
        }
        WalletWithdrawalsTable.insert {
            it[WalletWithdrawalsTable.id] = EntityID(id, WalletWithdrawalsTable)
            it[tenantId] = EntityID(support.tenantId(), TenantsTable)
            it[WalletWithdrawalsTable.ref] = ref
            it[customerId] = customer.id
            it[customerCode] = customer.code
            it[customerName] = customer.name
            it[WalletWithdrawalsTable.balanceType] = balanceType.name
            it[WalletWithdrawalsTable.reason] = reason
            it[initiatedBy] = if (dealerInitiated) "DEALER" else "COMPANY"
            it[WalletWithdrawalsTable.amount] = amount
            it[balanceBefore] = before
            it[balanceAfter] = entry.balanceAfter
            it[WalletWithdrawalsTable.payoutKind] = payoutKind
            it[payoutCashAccountId] = payout?.id?.takeUnless { payout.bank }
            it[payoutBankAccountId] = payout?.id?.takeIf { payout.bank }
            it[paidVia] = request.paidVia?.trim()?.ifEmpty { null }
            it[otpChallengeId] = request.otpChallengeId.takeIf { reason == "DEALER_REQUEST" }
            it[authorisedByUserId] = request.authorisedByUserId.takeUnless { dealerInitiated }
            it[note] = request.note?.trim()?.ifEmpty { null }
            it[withdrawalDate] = date
            it[locationId] = request.locationId
            it[customerLedgerEntryId] = entry.id
            it[cashTransactionId] = posted?.cashTransactionId
            it[bankTransactionId] = posted?.bankTransactionId
            it[auditStatus] = "COMPLETED"
            it[createdByUserId] = support.userId()
            it[createdAt] = Instant.now()
        }
        val verified = request.otpChallengeId != null && reason == "DEALER_REQUEST"
        support.record("account.wallet.withdrawn", "wallet_withdrawal", id, ref,
            "$ref: ${Money.naira(amount)} ${reason.lowercase().replace('_', ' ')} for ${customer.code} ${customer.name} (${if (dealerInitiated) "dealer" else "company"}-initiated" +
                (if (verified) ", OTP verified" else "") + ")",
            mapOf("amount" to amount, "reason" to reason, "initiatedBy" to if (dealerInitiated) "DEALER" else "COMPANY", "otpVerified" to verified), request.locationId)
        withdrawalRow(id)
    }

    fun withdrawals(request: ListSearchRequest): PageResult<WalletWithdrawalRow> = tx.execute {
        val context = support.filterContext()
        val parsed = withdrawalSearch.parse(request.q)
        val condition = listOfNotNull(
            WalletWithdrawalsTable.tenantId eq support.tenantId(),
            support.visibleLocations()?.let { WalletWithdrawalsTable.locationId inList it },
            withdrawalSearch.condition(parsed),
            withdrawalFilters.fromParams(request.params, context),
            withdrawalFilters.fromJson(request.filter, context),
        ).allOf()!!
        val sort = if (request.sort.isEmpty() && !parsed.isEmpty) withdrawalSearch.rank(parsed) + withdrawalFilters.sort(emptyList(), context) else withdrawalFilters.sort(request.sortParams, context)
        val joined = withdrawalJoin()
        val page = Paging.fetch(joined.selectAll().where(condition).orderBy(*sort.toTypedArray()), PageRequest.of(request.page, request.size)) { it }
        val summary = if (request.summary) {
            val all = joined.select(WalletWithdrawalsTable.initiatedBy, WalletWithdrawalsTable.amount, WalletWithdrawalsTable.auditStatus).where(condition).toList()
                .filter { it[WalletWithdrawalsTable.auditStatus] != "REVERSED" }
            fun sum(by: String?) = Money.round(all.filter { by == null || it[WalletWithdrawalsTable.initiatedBy] == by }.fold(BigDecimal.ZERO) { s, it -> s + it[WalletWithdrawalsTable.amount] })
            mapOf("totalWithdrawn" to sum(null), "dealerInitiated" to sum("DEALER"), "companyInitiated" to sum("COMPANY"))
        } else null
        page.withItems(toWithdrawalRows(page.items)).copy(summary = summary, meta = PageMeta(ignoredQualifiers = parsed.ignoredQualifiers))
    }

    fun withdrawalFilterMetadata(): FilterMetadata = tx.execute { withdrawalFilters.metadata(support.filterContext()) }

    fun getWithdrawal(id: UUID): WalletWithdrawalRow = tx.execute { withdrawalRow(id) }

    /** Flag a withdrawal for investigation, or clear the flag. */
    fun setAuditStatus(id: UUID, status: String): WalletWithdrawalRow = tx.execute {
        val row = withdrawal(id, forUpdate = true)
        if (row[WalletWithdrawalsTable.auditStatus] == "REVERSED") throw ApiErrors.alreadyReversed()
        WalletWithdrawalsTable.update({ WalletWithdrawalsTable.id eq id }) { it[auditStatus] = status }
        if (status == "UNDER_REVIEW") {
            support.record("account.wallet.withdrawal-flagged", "wallet_withdrawal", id, row[WalletWithdrawalsTable.ref],
                "${row[WalletWithdrawalsTable.ref]} flagged for review", mapOf("amount" to row[WalletWithdrawalsTable.amount]), row[WalletWithdrawalsTable.locationId])
        }
        withdrawalRow(id)
    }

    /** The balance is credited back and any payout returned to the account it left. */
    fun reverse(id: UUID, reason: String): WalletWithdrawalRow = tx.execute {
        val row = withdrawal(id, forUpdate = true)
        if (row[WalletWithdrawalsTable.auditStatus] == "REVERSED") throw ApiErrors.alreadyReversed()
        val ref = row[WalletWithdrawalsTable.ref]
        walletLedger.reverse(row[WalletWithdrawalsTable.customerLedgerEntryId], "Withdrawal $ref reversed: ${reason.trim()}", ReceiptWriter.SOURCE_MODULE)
        val payoutId = row[WalletWithdrawalsTable.payoutBankAccountId] ?: row[WalletWithdrawalsTable.payoutCashAccountId]
        if (payoutId != null) {
            val account = moves.existing(row[WalletWithdrawalsTable.payoutBankAccountId] != null, payoutId)
            val source = if (row[WalletWithdrawalsTable.reason] == "DEPOSIT_REFUND") "DEPOSIT_REFUND" else "WALLET_WITHDRAWAL"
            moves.into(account, row[WalletWithdrawalsTable.amount], LedgerPosting(ref, "Reversal of $ref: ${reason.trim()}", source, id, entryDate = support.today(), userId = support.userId()))
        }
        WalletWithdrawalsTable.update({ WalletWithdrawalsTable.id eq id }) {
            it[auditStatus] = "REVERSED"; it[reversalReason] = reason.trim(); it[reversedByUserId] = support.userId(); it[reversedAt] = Instant.now()
        }
        support.record("account.wallet.withdrawal-reversed", "wallet_withdrawal", id, ref, "$ref reversed: ${reason.trim()}",
            mapOf("amount" to row[WalletWithdrawalsTable.amount]), row[WalletWithdrawalsTable.locationId])
        withdrawalRow(id)
    }

    // ---- Helpers ----

    /** docs/04 §1.3: wallets are funded for dealers; deposits are held by special customers. */
    private fun checkHolder(customer: CustomerRef, balanceType: BalanceType) {
        if (balanceType == BalanceType.WALLET && customer.type != CustomerType.AUTHORISED_DEALER) {
            throw ApiErrors.balanceTypeNotAllowed("Only Authorised Dealers can fund a wallet directly; other customers' wallets hold return credits")
        }
        if (balanceType == BalanceType.DEPOSIT && customer.type != CustomerType.SPECIAL_CUSTOMER) throw ApiErrors.balanceTypeNotAllowed("Only Special Customers hold a deposit")
    }

    /** PRD rule 8: verified by the caller, for this customer and this exact amount, and not used before. */
    private fun checkOtp(challengeId: UUID?, customer: CustomerRef, amount: BigDecimal) {
        val id = challengeId ?: throw ApiErrors.otpNotVerified()
        val challenge = otp.verifiedChallenge(id, "WALLET_WITHDRAWAL") ?: throw ApiErrors.otpNotVerified()
        if (challenge.targetReference != customer.id.toString()) throw ApiErrors.otpMismatch("it was issued for another customer")
        val issuedFor = challenge.context["amount"]?.toString()?.toBigDecimalOrNull()
        if (issuedFor == null || issuedFor.compareTo(amount) != 0) throw ApiErrors.otpMismatch("it was issued for ${issuedFor?.let(Money::naira) ?: "no amount"}, not ${Money.naira(amount)}")
        if (!WalletWithdrawalsTable.selectAll().where { WalletWithdrawalsTable.otpChallengeId eq id }.empty()) throw ApiErrors.otpAlreadyUsed()
    }

    private val fundingReceipt = PaymentReceiptsTable.alias("wf_receipt")

    private fun fundingJoin() = WalletFundingsTable.join(fundingReceipt, JoinType.INNER, WalletFundingsTable.paymentReceiptId, fundingReceipt[PaymentReceiptsTable.id])

    private fun fundingRow(id: UUID): WalletFundingRow =
        toFundingRows(fundingJoin().selectAll().where { (WalletFundingsTable.id eq id) and (WalletFundingsTable.tenantId eq support.tenantId()) }.toList())
            .singleOrNull() ?: throw ApiErrors.walletFundingNotFound()

    private fun toFundingRows(rows: List<ResultRow>): List<WalletFundingRow> {
        val locations = Names.locations(rows.mapNotNull { it[WalletFundingsTable.locationId] })
        return rows.map { row ->
            WalletFundingRow(
                id = row[WalletFundingsTable.id].value, ref = row[WalletFundingsTable.ref], date = row[WalletFundingsTable.createdAt],
                customer = Ref.customer(row[WalletFundingsTable.customerId]), customerCode = row[WalletFundingsTable.customerCode], customerName = row[WalletFundingsTable.customerName],
                balanceType = row[WalletFundingsTable.balanceType], channel = row[WalletFundingsTable.channel], amount = row[WalletFundingsTable.amount],
                location = ref(row[WalletFundingsTable.locationId], Ref::location),
                receipt = Ref.receipt(row[WalletFundingsTable.paymentReceiptId]),
                confirmationStatus = row[fundingReceipt[PaymentReceiptsTable.confirmationStatus]], status = row[WalletFundingsTable.status],
                createdBy = UserRef(row[WalletFundingsTable.createdByUserId]),
            )
        }
    }

    private val payoutCash = CashAccountsTable.alias("ww_cash")
    private val payoutBank = BankAccountsTable.alias("ww_bank")

    private fun withdrawalJoin() = WalletWithdrawalsTable
        .join(payoutCash, JoinType.LEFT, WalletWithdrawalsTable.payoutCashAccountId, payoutCash[CashAccountsTable.id])
        .join(payoutBank, JoinType.LEFT, WalletWithdrawalsTable.payoutBankAccountId, payoutBank[BankAccountsTable.id])

    private fun withdrawal(id: UUID, forUpdate: Boolean = false): ResultRow {
        val query = WalletWithdrawalsTable.selectAll().where { (WalletWithdrawalsTable.id eq id) and (WalletWithdrawalsTable.tenantId eq support.tenantId()) }
        if (forUpdate) query.forUpdate()
        val row = query.firstOrNull() ?: throw ApiErrors.withdrawalNotFound()
        val visible = support.visibleLocations()
        if (visible != null && row[WalletWithdrawalsTable.locationId] !in visible) throw ApiErrors.withdrawalNotFound()
        return row
    }

    private fun withdrawalRow(id: UUID): WalletWithdrawalRow {
        withdrawal(id)
        return toWithdrawalRows(withdrawalJoin().selectAll().where { WalletWithdrawalsTable.id eq id }.toList()).single()
    }

    private fun toWithdrawalRows(rows: List<ResultRow>): List<WalletWithdrawalRow> {
        return rows.map { row ->
            WalletWithdrawalRow(
                id = row[WalletWithdrawalsTable.id].value, ref = row[WalletWithdrawalsTable.ref], date = row[WalletWithdrawalsTable.withdrawalDate],
                customer = Ref.customer(row[WalletWithdrawalsTable.customerId]), customerCode = row[WalletWithdrawalsTable.customerCode], customerName = row[WalletWithdrawalsTable.customerName],
                balanceType = row[WalletWithdrawalsTable.balanceType], initiatedBy = row[WalletWithdrawalsTable.initiatedBy], reason = row[WalletWithdrawalsTable.reason],
                balanceBefore = row[WalletWithdrawalsTable.balanceBefore], amount = row[WalletWithdrawalsTable.amount], balanceAfter = row[WalletWithdrawalsTable.balanceAfter],
                payoutKind = row[WalletWithdrawalsTable.payoutKind], payoutAccount = row[payoutCash[CashAccountsTable.code]] ?: row[payoutBank[BankAccountsTable.code]],
                paidVia = row[WalletWithdrawalsTable.paidVia], otp = if (row[WalletWithdrawalsTable.otpChallengeId] != null) "Verified" else "Not required",
                authorisedBy = UserRef.of(row[WalletWithdrawalsTable.authorisedByUserId]), processedBy = UserRef(row[WalletWithdrawalsTable.createdByUserId]),
                auditStatus = row[WalletWithdrawalsTable.auditStatus], reversalReason = row[WalletWithdrawalsTable.reversalReason], note = row[WalletWithdrawalsTable.note],
                createdAt = row[WalletWithdrawalsTable.createdAt],
            )
        }
    }

    private val fundingFilters = FilterSpec(
        fields = listOf(
            FilterField("customerId", "Customer", FilterType.UUID, WalletFundingsTable.customerId, aliases = setOf("customer"), lookup = "/api/v1/customers/lookup"),
            FilterField("balanceType", "Balance", FilterType.ENUM, WalletFundingsTable.balanceType, listOf(FilterOption("WALLET", "Wallet"), FilterOption("DEPOSIT", "Deposit"))),
            FilterField("status", "Status", FilterType.ENUM, WalletFundingsTable.status,
                listOf("AWAITING_CONFIRMATION", "CONFIRMED", "DECLINED", "VOIDED").map { FilterOption(it, BankAccountService.label(it)) }, facetable = true),
            FilterField("channel", "Channel", FilterType.ENUM, WalletFundingsTable.channel, listOf("CASH", "POS", "FUND_TRANSFER", "CHEQUE").map { FilterOption(it, ReceiptService.channelName(it)) }, facetable = true),
            FilterField("locationId", "Location", FilterType.LOCATION, WalletFundingsTable.locationId),
            FilterField("amount", "Amount", FilterType.MONEY, WalletFundingsTable.amount, sortable = true),
            FilterField("createdAt", "Date", FilterType.DATETIME, WalletFundingsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "createdAt",
        defaultSort = listOf("createdAt" to SortOrder.DESC),
        tiebreaker = WalletFundingsTable.id,
    )

    private val fundingSearch = SearchSpec(
        fields = listOf(
            SearchField(WalletFundingsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(WalletFundingsTable.customerCode, SearchKind.REFERENCE, "Customer code"),
            SearchField(WalletFundingsTable.customerName, SearchKind.TRIGRAM, "Customer"),
            SearchField(fundingReceipt[PaymentReceiptsTable.payerReference], SearchKind.CODE, "Payer reference"),
            SearchField(WalletFundingsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = fundingFilters,
    )

    private val otpVerified = RawExpression<Boolean>({ +"("; +WalletWithdrawalsTable.otpChallengeId; +" IS NOT NULL)" })

    private val withdrawalFilters = FilterSpec(
        fields = listOf(
            FilterField("customerId", "Customer", FilterType.UUID, WalletWithdrawalsTable.customerId, aliases = setOf("customer"), lookup = "/api/v1/customers/lookup"),
            FilterField("reason", "Reason", FilterType.ENUM, WalletWithdrawalsTable.reason,
                listOf("DEALER_REQUEST", "EXCESS_COMMISSION_RECOVERY", "OVER_CREDIT_CORRECTION", "DEPOSIT_REFUND").map { FilterOption(it, BankAccountService.label(it)) }, facetable = true),
            FilterField("initiatedBy", "Initiated by", FilterType.ENUM, WalletWithdrawalsTable.initiatedBy, listOf(FilterOption("DEALER", "Dealer"), FilterOption("COMPANY", "Company")), facetable = true),
            FilterField("payoutKind", "Payout", FilterType.ENUM, WalletWithdrawalsTable.payoutKind, listOf(FilterOption("CASH", "Cash"), FilterOption("BANK", "Bank"), FilterOption("NONE", "None"))),
            FilterField("auditStatus", "Audit status", FilterType.ENUM, WalletWithdrawalsTable.auditStatus,
                listOf(FilterOption("COMPLETED", "Completed"), FilterOption("UNDER_REVIEW", "Under review"), FilterOption("REVERSED", "Reversed"))),
            FilterField("processedBy", "Processed by", FilterType.UUID, WalletWithdrawalsTable.createdByUserId),
            FilterField("otpVerified", "OTP verified", FilterType.BOOLEAN, otpVerified),
            FilterField("amount", "Amount", FilterType.MONEY, WalletWithdrawalsTable.amount, sortable = true),
            FilterField("withdrawalDate", "Date", FilterType.DATE, WalletWithdrawalsTable.withdrawalDate, sortable = true),
            FilterField("createdAt", "Recorded", FilterType.DATETIME, WalletWithdrawalsTable.createdAt, sortable = true),
        ),
        primaryDateKey = "withdrawalDate",
        defaultSort = listOf("withdrawalDate" to SortOrder.DESC, "createdAt" to SortOrder.DESC),
        tiebreaker = WalletWithdrawalsTable.id,
    )

    private val withdrawalSearch = SearchSpec(
        fields = listOf(
            SearchField(WalletWithdrawalsTable.ref, SearchKind.REFERENCE, "Reference"),
            SearchField(WalletWithdrawalsTable.customerCode, SearchKind.REFERENCE, "Customer code"),
            SearchField(WalletWithdrawalsTable.customerName, SearchKind.TRIGRAM, "Customer"),
            SearchField(WalletWithdrawalsTable.paidVia, SearchKind.TRIGRAM, "Paid via"),
            SearchField(WalletWithdrawalsTable.amount, SearchKind.AMOUNT, "Amount"),
        ),
        qualifiers = withdrawalFilters,
    )

    companion object {
        private val DEALER_REASONS = setOf("DEALER_REQUEST", "DEPOSIT_REFUND")
    }
}
