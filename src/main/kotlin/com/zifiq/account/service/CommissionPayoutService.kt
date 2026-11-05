package com.zifiq.account.service

import com.zifiq.account.repository.LedgerPosting
import com.zifiq.account.spi.CommissionPayoutPosting
import com.zifiq.account.spi.CommissionPayoutRequest
import com.zifiq.account.spi.CommissionPayouts
import com.zifiq.common.money.Money
import org.springframework.stereotype.Service

/**
 * Pays commission out of a cash or bank account for the Communication module (docs/07 §5.6, doc 12
 * §4.3). Cash sufficiency and account access are enforced by [MoneyMoves]; a reversal posts the money
 * back into the same account. Wallet payouts are the caller's own (customers `WalletLedger`). Runs in
 * the caller's tenant-scoped transaction.
 */
@Service
class CommissionPayoutService(
    private val support: AccountSupport,
    private val moves: MoneyMoves,
) : CommissionPayouts {
    override fun payOut(request: CommissionPayoutRequest): CommissionPayoutPosting {
        val account = moves.source(request.method, request.bankAccountId, request.cashAccountId)
        val posted = moves.out(
            account, Money.round(request.amount),
            LedgerPosting(request.ref, request.description, "COMMISSION_PAYOUT", request.sourceId, entryDate = support.today(), userId = support.userId()),
        )
        return CommissionPayoutPosting(posted.cashTransactionId, posted.bankTransactionId)
    }

    override fun reversePayout(request: CommissionPayoutRequest, reason: String): CommissionPayoutPosting {
        val account = moves.source(request.method, request.bankAccountId, request.cashAccountId)
        val posted = moves.into(
            account, Money.round(request.amount),
            LedgerPosting(request.ref, "Commission payout reversed: $reason", "COMMISSION_PAYOUT_REVERSAL", request.sourceId, entryDate = support.today(), userId = support.userId()),
        )
        return CommissionPayoutPosting(posted.cashTransactionId, posted.bankTransactionId)
    }
}
