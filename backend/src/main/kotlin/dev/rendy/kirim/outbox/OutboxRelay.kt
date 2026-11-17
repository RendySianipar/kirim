package dev.rendy.kirim.outbox

import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Seam between the relay and the broker, so retry logic can be tested with a failing fake. */
fun interface EventPublisher {
    fun publish(event: OutboxEvent)
}

@Component
class KafkaEventPublisher(
    private val kafka: KafkaTemplate<String, String>,
    private val props: OutboxProperties,
) : EventPublisher {

    override fun publish(event: OutboxEvent) {
        val record = ProducerRecord(props.topic, event.aggregateId.toString(), event.payload)
        record.headers().add("eventId", event.id.toString().toByteArray())
        record.headers().add("eventType", event.eventType.toByteArray())
        // Block until the broker acks (acks=all), so "PUBLISHED" really means published.
        kafka.send(record).get(10, TimeUnit.SECONDS)
    }
}

/**
 * Moves events from the outbox table to Kafka.
 *
 * Delivery is at-least-once: if the app crashes after Kafka acked but before
 * the row is marked PUBLISHED, the event is sent again. Consumers dedupe on
 * eventId, so the end result is effectively-once.
 */
@Component
class OutboxRelay(
    private val outbox: OutboxRepository,
    private val publisher: EventPublisher,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val props: OutboxProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Publishes one batch of due events. Returns how many were attempted. */
    fun relayBatch(): Int = tx.execute {
        val now = clock.instant()
        val batch = outbox.claimDue(now, props.batchSize)
        for (event in batch) {
            try {
                publisher.publish(event)
                outbox.markPublished(event.id, clock.instant())
                meters.counter("kirim.outbox.publish", "result", "success").increment()
            } catch (e: Exception) {
                val attempts = event.attempts + 1
                val giveUp = attempts >= props.maxAttempts
                val nextAttemptAt = now.plus(props.backoff.delayFor(attempts))
                outbox.recordFailure(event.id, attempts, nextAttemptAt, e.message ?: e.javaClass.simpleName, giveUp)
                if (giveUp) {
                    meters.counter("kirim.outbox.publish", "result", "dead").increment()
                    log.error("Outbox event {} failed {} times, marking FAILED", event.id, attempts, e)
                } else {
                    meters.counter("kirim.outbox.publish", "result", "retry").increment()
                    log.warn("Outbox event {} attempt {} failed, retrying at {}", event.id, attempts, nextAttemptAt)
                }
            }
        }
        batch.size
    } ?: 0
}

@Component
@ConditionalOnProperty(prefix = "kirim.outbox", name = ["scheduler-enabled"], havingValue = "true", matchIfMissing = true)
class OutboxScheduler(private val relay: OutboxRelay) {

    @Scheduled(fixedDelayString = "\${kirim.outbox.poll-interval-ms:500}")
    fun tick() {
        relay.relayBatch()
    }
}
