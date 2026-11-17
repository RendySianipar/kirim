package dev.rendy.kirim.outbox

import dev.rendy.kirim.transfer.RejectionReason
import dev.rendy.kirim.transfer.TransferStatus
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

enum class OutboxStatus { PENDING, PUBLISHED, FAILED }

data class OutboxEvent(
    val id: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val status: OutboxStatus,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastError: String?,
    val createdAt: Instant,
    val publishedAt: Instant?,
) {
    companion object {
        fun pending(id: UUID, aggregateId: UUID, eventType: String, payload: String, now: Instant) =
            OutboxEvent(id, aggregateId, eventType, payload, OutboxStatus.PENDING, 0, now, null, now, null)
    }
}

enum class TransferEventType { TRANSFER_COMPLETED, TRANSFER_REJECTED }

/** The message contract published to Kafka. [eventId] is what consumers dedupe on. */
data class TransferEvent(
    val eventId: UUID,
    val type: TransferEventType,
    val transferId: UUID,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Long,
    val currency: String,
    val status: TransferStatus,
    val failureReason: RejectionReason?,
    val occurredAt: Instant,
)

/** Exponential backoff with a ceiling: initial, initial*m, initial*m^2, ... capped at max. */
data class Backoff(val initial: Duration, val multiplier: Double, val max: Duration) {
    fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt starts at 1" }
        val millis = initial.toMillis() * multiplier.pow(attempt - 1)
        return Duration.ofMillis(min(millis, max.toMillis().toDouble()).toLong())
    }
}

@ConfigurationProperties("kirim.outbox")
data class OutboxProperties(
    val topic: String = "transfer-events",
    val batchSize: Int = 50,
    val maxAttempts: Int = 8,
    val initialBackoff: Duration = Duration.ofSeconds(1),
    val backoffMultiplier: Double = 2.0,
    val maxBackoff: Duration = Duration.ofMinutes(5),
) {
    val backoff: Backoff get() = Backoff(initialBackoff, backoffMultiplier, maxBackoff)
}
