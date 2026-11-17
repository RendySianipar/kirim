package dev.rendy.kirim.transfer

import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.account.LedgerRepository
import dev.rendy.kirim.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tests that prove the locking strategy: real threads, real PostgreSQL.
 */
class ConcurrentTransferIntegrationTest : IntegrationTest() {

    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var ledger: LedgerRepository
    @Autowired lateinit var transfers: TransferRepository
    @Autowired lateinit var service: TransferService

    @Test
    fun `transfers in both directions at once never deadlock, lose money or go negative`() {
        val a = accounts.open("A", "IDR", 1_000_000)
        val b = accounts.open("B", "IDR", 1_000_000)
        val c = accounts.open("C", "IDR", 1_000_000)
        val ids = listOf(a.id, b.id, c.id)
        val totalBefore = 3_000_000L

        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        repeat(200) {
            pool.execute {
                start.await()
                val (from, to) = ids.shuffled().take(2)
                try {
                    service.transfer(UUID.randomUUID().toString(), TransferCommand(from, to, Random.nextLong(1, 50_000), "IDR", null))
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "transfers did not finish (deadlock?)")

        assertTrue(errors.isEmpty(), "unexpected errors: ${errors.take(3)}")
        val balances = ids.map { accounts.get(it).balance }
        assertEquals(totalBefore, balances.sum(), "money was created or destroyed")
        assertTrue(balances.all { it >= 0 }, "a balance went negative: $balances")
        ids.forEach { assertEquals(accounts.get(it).balance, ledger.netAmount(it), "balance and ledger disagree for $it") }
    }

    @Test
    fun `the same Idempotency-Key sent concurrently creates exactly one transfer`() {
        val alice = accounts.open("Alice", "IDR", 100_000)
        val bob = accounts.open("Bob", "IDR", 0)
        val command = TransferCommand(alice.id, bob.id, 10_000, "IDR", "double tap")

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        val futures = (1..10).map {
            pool.submit(
                Callable {
                    start.await()
                    service.transfer("double-tap-key", command)
                },
            )
        }
        start.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, results.map { it.transfer.id }.toSet().size, "all callers should see the same transfer")
        assertEquals(1, results.count { !it.replayed }, "exactly one caller does the work")
        assertEquals(1L, transfers.count())
        assertEquals(90_000L, accounts.get(alice.id).balance)
    }
}
