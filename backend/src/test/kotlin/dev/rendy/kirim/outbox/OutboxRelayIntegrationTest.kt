package dev.rendy.kirim.outbox

import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.support.IntegrationTest
import dev.rendy.kirim.support.MutableClock
import dev.rendy.kirim.transfer.TransferCommand
import dev.rendy.kirim.transfer.TransferService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Drives the relay by hand with a fake publisher and a controllable clock, so
 * retry timing can be asserted exactly instead of with sleeps.
 */
class OutboxRelayIntegrationTest : IntegrationTest() {

    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var transfers: TransferService
    @Autowired lateinit var outbox: OutboxRepository
    @Autowired lateinit var tx: TransactionTemplate

    private val props = OutboxProperties(
        maxAttempts = 3,
        initialBackoff = Duration.ofSeconds(1),
        backoffMultiplier = 2.0,
        maxBackoff = Duration.ofMinutes(1),
    )

    /** Starts just after "now" so events created by the real clock are already due. */
    private val clock = MutableClock(Instant.now().plusSeconds(5))

    private fun relayWith(publisher: EventPublisher) =
        OutboxRelay(outbox, publisher, tx, clock, props, SimpleMeterRegistry())

    private fun createTransferEvent(): OutboxEvent {
        val alice = accounts.open("Alice", "IDR", 50_000)
        val bob = accounts.open("Bob", "IDR", 0)
        val result = transfers.transfer(UUID.randomUUID().toString(), TransferCommand(alice.id, bob.id, 1_000, "IDR", null))
        return outbox.findByAggregate(result.transfer.id).single()
    }

    @Test
    fun `publishes pending events and marks them PUBLISHED`() {
        val event = createTransferEvent()
        val sent = mutableListOf<OutboxEvent>()

        assertEquals(1, relayWith { sent += it }.relayBatch())

        assertEquals(listOf(event.id), sent.map { it.id })
        assertEquals(OutboxStatus.PUBLISHED, outbox.findById(event.id)!!.status)
        assertEquals(0, relayWith { sent += it }.relayBatch(), "published events are not sent again")
    }

    @Test
    fun `failed publishes are retried with exponential backoff, then marked FAILED`() {
        val event = createTransferEvent()
        var calls = 0
        val relay = relayWith { calls++; throw IllegalStateException("broker down") }

        relay.relayBatch() // attempt 1 fails -> retry in 1s
        assertEquals(1, calls)
        assertEquals(1, outbox.findById(event.id)!!.attempts)

        relay.relayBatch() // not due yet
        assertEquals(1, calls)

        clock.advance(Duration.ofSeconds(1))
        relay.relayBatch() // attempt 2 fails -> retry in 2s
        assertEquals(2, calls)

        clock.advance(Duration.ofSeconds(1))
        relay.relayBatch() // only 1s of the 2s has passed
        assertEquals(2, calls)

        clock.advance(Duration.ofSeconds(1))
        relay.relayBatch() // attempt 3 fails -> maxAttempts reached
        assertEquals(3, calls)

        val failed = outbox.findById(event.id)!!
        assertEquals(OutboxStatus.FAILED, failed.status)
        assertEquals(3, failed.attempts)
        assertEquals("broker down", failed.lastError)

        clock.advance(Duration.ofHours(1))
        relay.relayBatch()
        assertEquals(3, calls, "FAILED events are parked, not retried forever")
    }

    @Test
    fun `a transient failure recovers on the next attempt`() {
        val event = createTransferEvent()
        var calls = 0
        val relay = relayWith { calls++; if (calls == 1) throw IllegalStateException("timeout") }

        relay.relayBatch()
        clock.advance(Duration.ofSeconds(1))
        relay.relayBatch()

        val published = outbox.findById(event.id)!!
        assertEquals(OutboxStatus.PUBLISHED, published.status)
        assertEquals(1, published.attempts)
    }
}
