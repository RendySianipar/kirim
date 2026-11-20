package dev.rendy.kirim.transfer

import com.fasterxml.jackson.databind.ObjectMapper
import dev.rendy.kirim.account.AccountRepository
import dev.rendy.kirim.account.EntryType
import dev.rendy.kirim.account.LedgerEntry
import dev.rendy.kirim.account.LedgerRepository
import dev.rendy.kirim.common.AccountNotFoundException
import dev.rendy.kirim.common.IdempotencyKeyReusedException
import dev.rendy.kirim.common.IdempotentRequestInProgressException
import dev.rendy.kirim.common.InvalidRequestException
import dev.rendy.kirim.common.TransferNotFoundException
import dev.rendy.kirim.outbox.OutboxEvent
import dev.rendy.kirim.outbox.OutboxRepository
import dev.rendy.kirim.outbox.TransferEvent
import dev.rendy.kirim.outbox.TransferEventType
import dev.rendy.kirim.ratelimit.TransferRateLimiter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ConfigurationProperties("kirim.transfer")
data class TransferProperties(val maxAmount: Long = 50_000_000)

@Service
class TransferService(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val transfers: TransferRepository,
    private val idempotency: IdempotencyRepository,
    private val outbox: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val props: TransferProperties,
    private val meters: MeterRegistry,
    private val rateLimiter: TransferRateLimiter,
    private val cache: TransferCache,
) {

    /**
     * Everything below runs in ONE database transaction:
     * reserve the idempotency key -> lock both accounts -> apply the decision ->
     * write ledger entries -> write the outbox event -> link the key to the transfer.
     *
     * Either all of it commits or none of it does. Validation errors (unknown
     * account, same account) roll back the key too, so the client can fix the
     * request and retry with the same key.
     */
    @Transactional
    fun transfer(idempotencyKey: String, command: TransferCommand): TransferResult {
        val fingerprint = RequestFingerprint.of(command)
        val now = clock.instant()

        if (!idempotency.tryReserve(idempotencyKey, fingerprint, now)) {
            return replay(idempotencyKey, fingerprint)
        }

        // Checked after the key is reserved, so replays of an earlier request never
        // use up quota. If the limit is hit, the exception rolls back the key and the
        // client can retry later with the same key.
        rateLimiter.acquire(command.fromAccountId)

        val transfer = execute(command, now)
        idempotency.attachTransfer(idempotencyKey, transfer.id)
        meters.counter("kirim.transfers", "status", transfer.status.name).increment()
        return TransferResult(transfer, replayed = false)
    }

    /** Cache-aside: Redis first, then PostgreSQL (and fill the cache). Safe because transfers never change. */
    @Transactional(readOnly = true)
    fun get(id: UUID): Transfer =
        cache.get(id)
            ?: transfers.findById(id)?.also { cache.put(it) }
            ?: throw TransferNotFoundException(id)

    private fun replay(key: String, fingerprint: String): TransferResult {
        val record = idempotency.find(key) ?: throw IdempotentRequestInProgressException(key)
        if (record.requestHash != fingerprint) throw IdempotencyKeyReusedException(key)
        val transferId = record.transferId ?: throw IdempotentRequestInProgressException(key)
        meters.counter("kirim.transfers.replayed").increment()
        return TransferResult(get(transferId), replayed = true)
    }

    private fun execute(command: TransferCommand, now: Instant): Transfer {
        if (command.fromAccountId == command.toAccountId) {
            throw InvalidRequestException("fromAccountId and toAccountId must be different")
        }

        val locked = accounts.findAllForUpdate(listOf(command.fromAccountId, command.toAccountId))
        val from = locked[command.fromAccountId] ?: throw AccountNotFoundException(command.fromAccountId)
        val to = locked[command.toAccountId] ?: throw AccountNotFoundException(command.toAccountId)

        val decision = TransferDecision.decide(from, to, command.amount, command.currency, props.maxAmount)
        val transfer = Transfer(
            id = UUID.randomUUID(),
            fromAccountId = from.id,
            toAccountId = to.id,
            amount = command.amount,
            currency = command.currency,
            description = command.description,
            status = if (decision is TransferDecision.Approved) TransferStatus.COMPLETED else TransferStatus.REJECTED,
            failureReason = (decision as? TransferDecision.Rejected)?.reason,
            createdAt = now,
        )
        transfers.insert(transfer)

        if (decision is TransferDecision.Approved) {
            accounts.updateBalance(from.id, decision.fromBalanceAfter)
            accounts.updateBalance(to.id, decision.toBalanceAfter)
            ledger.insert(LedgerEntry(UUID.randomUUID(), from.id, transfer.id, EntryType.DEBIT, transfer.amount, decision.fromBalanceAfter, now))
            ledger.insert(LedgerEntry(UUID.randomUUID(), to.id, transfer.id, EntryType.CREDIT, transfer.amount, decision.toBalanceAfter, now))
        }

        writeOutboxEvent(transfer, now)
        return transfer
    }

    private fun writeOutboxEvent(transfer: Transfer, now: Instant) {
        val eventId = UUID.randomUUID()
        val event = TransferEvent(
            eventId = eventId,
            type = if (transfer.status == TransferStatus.COMPLETED) TransferEventType.TRANSFER_COMPLETED else TransferEventType.TRANSFER_REJECTED,
            transferId = transfer.id,
            fromAccountId = transfer.fromAccountId,
            toAccountId = transfer.toAccountId,
            amount = transfer.amount,
            currency = transfer.currency,
            status = transfer.status,
            failureReason = transfer.failureReason,
            occurredAt = now,
        )
        outbox.insert(OutboxEvent.pending(eventId, transfer.id, event.type.name, objectMapper.writeValueAsString(event), now))
    }
}
