package dev.rendy.kirim.transfer

import dev.rendy.kirim.account.Account
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** Fast unit tests, no Spring. These drove the design of TransferDecision (red -> green -> refactor). */
class TransferDecisionTest {

    private val limit = 50_000_000L

    private fun account(balance: Long, currency: String = "IDR") =
        Account(UUID.randomUUID(), "owner", currency, balance, Instant.EPOCH)

    @Test
    fun `approves and computes both balances`() {
        val decision = TransferDecision.decide(account(100_000), account(5_000), 30_000, "IDR", limit)

        assertEquals(TransferDecision.Approved(fromBalanceAfter = 70_000, toBalanceAfter = 35_000), decision)
    }

    @Test
    fun `allows sending the exact balance`() {
        val decision = TransferDecision.decide(account(30_000), account(0), 30_000, "IDR", limit)

        assertEquals(TransferDecision.Approved(0, 30_000), decision)
    }

    @Test
    fun `rejects when funds are insufficient`() {
        val decision = TransferDecision.decide(account(29_999), account(0), 30_000, "IDR", limit)

        assertEquals(TransferDecision.Rejected(RejectionReason.INSUFFICIENT_FUNDS), decision)
    }

    @Test
    fun `rejects amounts above the per-transfer limit`() {
        val decision = TransferDecision.decide(account(100_000_000), account(0), limit + 1, "IDR", limit)

        assertEquals(TransferDecision.Rejected(RejectionReason.LIMIT_EXCEEDED), decision)
    }

    @Test
    fun `rejects when currencies do not match`() {
        val decision = TransferDecision.decide(account(100_000), account(0, currency = "SGD"), 1_000, "IDR", limit)

        assertEquals(TransferDecision.Rejected(RejectionReason.CURRENCY_MISMATCH), decision)
    }

    @Test
    fun `non-positive amounts are a programming error`() {
        assertThrows<IllegalArgumentException> { TransferDecision.decide(account(100), account(0), 0, "IDR", limit) }
        assertThrows<IllegalArgumentException> { TransferDecision.decide(account(100), account(0), -5, "IDR", limit) }
    }

    @Test
    fun `cannot transfer to the same account`() {
        val same = account(100)
        assertThrows<IllegalArgumentException> { TransferDecision.decide(same, same, 10, "IDR", limit) }
    }

    @Test
    fun `balance overflow throws instead of wrapping around`() {
        assertThrows<ArithmeticException> {
            TransferDecision.decide(account(10), account(Long.MAX_VALUE), 10, "IDR", Long.MAX_VALUE)
        }
    }
}
