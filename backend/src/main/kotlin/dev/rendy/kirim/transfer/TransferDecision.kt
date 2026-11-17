package dev.rendy.kirim.transfer

import dev.rendy.kirim.account.Account

enum class RejectionReason { INSUFFICIENT_FUNDS, CURRENCY_MISMATCH, LIMIT_EXCEEDED }

/**
 * Pure business rules for a transfer: no database, no Spring, no clock.
 * This is the part written test-first (see TransferDecisionTest).
 *
 * Programming errors (non-positive amount, same account) throw.
 * Business outcomes (not enough money, over the limit) come back as [Rejected],
 * because a rejected transfer is still a real, recorded event.
 */
sealed interface TransferDecision {

    data class Approved(val fromBalanceAfter: Long, val toBalanceAfter: Long) : TransferDecision

    data class Rejected(val reason: RejectionReason) : TransferDecision

    companion object {
        fun decide(from: Account, to: Account, amount: Long, currency: String, maxAmount: Long): TransferDecision {
            require(amount > 0) { "amount must be positive" }
            require(from.id != to.id) { "cannot transfer to the same account" }

            if (from.currency != currency || to.currency != currency) return Rejected(RejectionReason.CURRENCY_MISMATCH)
            if (amount > maxAmount) return Rejected(RejectionReason.LIMIT_EXCEEDED)
            if (from.balance < amount) return Rejected(RejectionReason.INSUFFICIENT_FUNDS)

            return Approved(
                fromBalanceAfter = from.balance - amount,
                toBalanceAfter = Math.addExact(to.balance, amount), // overflow throws instead of wrapping
            )
        }
    }
}
