package dev.rendy.kirim.outbox

import dev.rendy.kirim.common.instant
import dev.rendy.kirim.common.instantOrNull
import dev.rendy.kirim.common.toTimestamp
import dev.rendy.kirim.common.uuid
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class OutboxRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        OutboxEvent(
            id = rs.uuid("id"),
            aggregateId = rs.uuid("aggregate_id"),
            eventType = rs.getString("event_type"),
            payload = rs.getString("payload"),
            status = OutboxStatus.valueOf(rs.getString("status")),
            attempts = rs.getInt("attempts"),
            nextAttemptAt = rs.instant("next_attempt_at"),
            lastError = rs.getString("last_error"),
            createdAt = rs.instant("created_at"),
            publishedAt = rs.instantOrNull("published_at"),
        )
    }

    fun insert(e: OutboxEvent) {
        jdbc.sql(
            """
            INSERT INTO outbox_events (id, aggregate_id, event_type, payload, status, attempts,
                                       next_attempt_at, last_error, created_at, published_at)
            VALUES (:id, :aggregateId, :eventType, :payload, :status, :attempts,
                    :nextAttemptAt, :lastError, :createdAt, NULL)
            """,
        )
            .param("id", e.id)
            .param("aggregateId", e.aggregateId)
            .param("eventType", e.eventType)
            .param("payload", e.payload)
            .param("status", e.status.name)
            .param("attempts", e.attempts)
            .param("nextAttemptAt", e.nextAttemptAt.toTimestamp())
            .param("lastError", e.lastError)
            .param("createdAt", e.createdAt.toTimestamp())
            .update()
    }

    /**
     * Claims due events. FOR UPDATE SKIP LOCKED lets several app instances run
     * the relay at once without publishing the same row twice.
     * Must be called inside a transaction (the locks live until commit).
     */
    fun claimDue(now: Instant, limit: Int): List<OutboxEvent> =
        jdbc.sql(
            """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
        )
            .param("now", now.toTimestamp())
            .param("limit", limit)
            .query(mapper)
            .list()

    fun markPublished(id: UUID, now: Instant) {
        jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED', published_at = :now WHERE id = :id")
            .param("now", now.toTimestamp())
            .param("id", id)
            .update()
    }

    fun recordFailure(id: UUID, attempts: Int, nextAttemptAt: Instant, error: String, giveUp: Boolean) {
        jdbc.sql(
            """
            UPDATE outbox_events
            SET attempts = :attempts, next_attempt_at = :nextAttemptAt, last_error = :error, status = :status
            WHERE id = :id
            """,
        )
            .param("attempts", attempts)
            .param("nextAttemptAt", nextAttemptAt.toTimestamp())
            .param("error", error.take(1000))
            .param("status", if (giveUp) OutboxStatus.FAILED.name else OutboxStatus.PENDING.name)
            .param("id", id)
            .update()
    }

    fun findById(id: UUID): OutboxEvent? =
        jdbc.sql("SELECT * FROM outbox_events WHERE id = :id")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    fun findByAggregate(aggregateId: UUID): List<OutboxEvent> =
        jdbc.sql("SELECT * FROM outbox_events WHERE aggregate_id = :aggregateId ORDER BY created_at")
            .param("aggregateId", aggregateId)
            .query(mapper)
            .list()
}
