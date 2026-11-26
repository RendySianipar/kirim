package dev.rendy.kirim.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.rendy.kirim.common.instant
import dev.rendy.kirim.common.toTimestamp
import dev.rendy.kirim.common.uuid
import dev.rendy.kirim.outbox.TransferEvent
import dev.rendy.kirim.outbox.TransferEventType
import dev.rendy.kirim.security.AccountAccess
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/*
 * A downstream consumer of transfer events. In a real system this would be its
 * own service (push notifications); it lives in the same codebase here to keep
 * the project easy to run, but it only talks to the rest through Kafka.
 */

data class Notification(val id: UUID, val eventId: UUID, val accountId: UUID, val message: String, val createdAt: Instant)

@Repository
class NotificationRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        Notification(rs.uuid("id"), rs.uuid("event_id"), rs.uuid("account_id"), rs.getString("message"), rs.instant("created_at"))
    }

    /** Returns false if this event was already processed (duplicate delivery). */
    fun markProcessed(eventId: UUID, now: Instant): Boolean =
        jdbc.sql("INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, :now) ON CONFLICT (event_id) DO NOTHING")
            .param("eventId", eventId)
            .param("now", now.toTimestamp())
            .update() == 1

    fun insert(n: Notification) {
        jdbc.sql(
            "INSERT INTO notifications (id, event_id, account_id, message, created_at) VALUES (:id, :eventId, :accountId, :message, :createdAt)",
        )
            .param("id", n.id)
            .param("eventId", n.eventId)
            .param("accountId", n.accountId)
            .param("message", n.message)
            .param("createdAt", n.createdAt.toTimestamp())
            .update()
    }

    fun findByAccount(accountId: UUID): List<Notification> =
        jdbc.sql("SELECT * FROM notifications WHERE account_id = :accountId ORDER BY created_at DESC LIMIT 50")
            .param("accountId", accountId)
            .query(mapper)
            .list()
}

@Service
class NotificationService(
    private val repository: NotificationRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Idempotent: the dedupe insert and the notifications commit (or roll back) together. */
    @Transactional
    fun handle(payload: String) {
        val event = objectMapper.readValue<TransferEvent>(payload)
        val now = clock.instant()

        if (!repository.markProcessed(event.eventId, now)) {
            log.info("Skipping duplicate event {}", event.eventId)
            return
        }

        val amount = formatMoney(event.amount, event.currency)
        when (event.type) {
            TransferEventType.TRANSFER_COMPLETED -> {
                addNotification(event, event.fromAccountId, "You sent $amount", now)
                addNotification(event, event.toAccountId, "You received $amount", now)
            }
            TransferEventType.TRANSFER_REJECTED ->
                addNotification(event, event.fromAccountId, "Your transfer of $amount was rejected (${event.failureReason})", now)
        }
    }

    @Transactional(readOnly = true)
    fun forAccount(accountId: UUID): List<Notification> = repository.findByAccount(accountId)

    private fun addNotification(event: TransferEvent, accountId: UUID, message: String, now: Instant) =
        repository.insert(Notification(UUID.randomUUID(), event.eventId, accountId, message, now))

    private fun formatMoney(amount: Long, currency: String) = String.format(Locale.US, "%s %,d", currency, amount)
}

@Component
class TransferEventListener(private val service: NotificationService) {

    @KafkaListener(topics = ["\${kirim.outbox.topic:transfer-events}"], groupId = "kirim-notifications")
    fun onTransferEvent(record: ConsumerRecord<String, String>) {
        service.handle(record.value())
    }
}

data class NotificationResponse(val id: UUID, val message: String, val createdAt: Instant)

@RestController
class NotificationController(
    private val service: NotificationService,
    private val access: AccountAccess,
) {

    @GetMapping("/api/v1/accounts/{id}/notifications")
    fun list(@PathVariable id: UUID): List<NotificationResponse> {
        access.requireReadable(id)
        return service.forAccount(id).map { NotificationResponse(it.id, it.message, it.createdAt) }
    }
}
