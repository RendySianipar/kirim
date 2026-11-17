package dev.rendy.kirim.notification

import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.outbox.OutboxRelay
import dev.rendy.kirim.outbox.OutboxRepository
import dev.rendy.kirim.support.IntegrationTest
import dev.rendy.kirim.transfer.TransferCommand
import dev.rendy.kirim.transfer.TransferService
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End to end through a real Kafka broker: transfer -> outbox -> Kafka -> consumer -> notifications. */
class TransferEventFlowIntegrationTest : IntegrationTest() {

    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var transfers: TransferService
    @Autowired lateinit var relay: OutboxRelay
    @Autowired lateinit var outbox: OutboxRepository
    @Autowired lateinit var notifications: NotificationService

    @Test
    fun `a completed transfer notifies sender and receiver exactly once, even on redelivery`() {
        val alice = accounts.open("Alice", "IDR", 100_000)
        val bob = accounts.open("Bob", "IDR", 0)
        val result = transfers.transfer(UUID.randomUUID().toString(), TransferCommand(alice.id, bob.id, 12_500, "IDR", null))

        relay.relayBatch() // real KafkaEventPublisher

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertEquals(1, notifications.forAccount(alice.id).size)
            assertEquals(1, notifications.forAccount(bob.id).size)
        }
        assertTrue(notifications.forAccount(bob.id).single().message.contains("12,500"))

        // Simulate Kafka redelivering the same message (at-least-once delivery).
        val event = outbox.findByAggregate(result.transfer.id).single()
        notifications.handle(event.payload)
        notifications.handle(event.payload)

        assertEquals(1, notifications.forAccount(alice.id).size)
        assertEquals(1, notifications.forAccount(bob.id).size)
    }
}
