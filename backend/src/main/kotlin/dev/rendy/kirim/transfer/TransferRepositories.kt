package dev.rendy.kirim.transfer

import dev.rendy.kirim.common.instant
import dev.rendy.kirim.common.toTimestamp
import dev.rendy.kirim.common.uuid
import dev.rendy.kirim.common.uuidOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TransferRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        Transfer(
            id = rs.uuid("id"),
            fromAccountId = rs.uuid("from_account_id"),
            toAccountId = rs.uuid("to_account_id"),
            amount = rs.getLong("amount"),
            currency = rs.getString("currency"),
            description = rs.getString("description"),
            status = TransferStatus.valueOf(rs.getString("status")),
            failureReason = rs.getString("failure_reason")?.let { RejectionReason.valueOf(it) },
            createdAt = rs.instant("created_at"),
        )
    }

    fun insert(t: Transfer) {
        jdbc.sql(
            """
            INSERT INTO transfers (id, from_account_id, to_account_id, amount, currency, description,
                                   status, failure_reason, created_at)
            VALUES (:id, :fromAccountId, :toAccountId, :amount, :currency, :description,
                    :status, :failureReason, :createdAt)
            """,
        )
            .param("id", t.id)
            .param("fromAccountId", t.fromAccountId)
            .param("toAccountId", t.toAccountId)
            .param("amount", t.amount)
            .param("currency", t.currency)
            .param("description", t.description)
            .param("status", t.status.name)
            .param("failureReason", t.failureReason?.name)
            .param("createdAt", t.createdAt.toTimestamp())
            .update()
    }

    fun findById(id: UUID): Transfer? =
        jdbc.sql("SELECT * FROM transfers WHERE id = :id")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    fun count(): Long =
        jdbc.sql("SELECT COUNT(*) FROM transfers").query(Long::class.javaObjectType).single()
}

data class IdempotencyRecord(val key: String, val requestHash: String, val transferId: UUID?)

@Repository
class IdempotencyRepository(private val jdbc: JdbcClient) {

    /**
     * Returns true if this call claimed the key.
     *
     * If another transaction inserted the same key but hasn't committed yet,
     * PostgreSQL makes this INSERT wait until that transaction finishes. When it
     * commits we get "conflict, do nothing" (false) and can replay its result.
     * When it rolls back, our INSERT succeeds and we do the work ourselves.
     */
    fun tryReserve(key: String, requestHash: String, now: Instant): Boolean =
        jdbc.sql(
            """
            INSERT INTO idempotency_keys (idempotency_key, request_hash, created_at)
            VALUES (:key, :hash, :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
        )
            .param("key", key)
            .param("hash", requestHash)
            .param("createdAt", now.toTimestamp())
            .update() == 1

    fun find(key: String): IdempotencyRecord? =
        jdbc.sql("SELECT idempotency_key, request_hash, transfer_id FROM idempotency_keys WHERE idempotency_key = :key")
            .param("key", key)
            .query(
                RowMapper { rs, _ ->
                    IdempotencyRecord(rs.getString("idempotency_key"), rs.getString("request_hash"), rs.uuidOrNull("transfer_id"))
                },
            )
            .optional()
            .orElse(null)

    fun attachTransfer(key: String, transferId: UUID) {
        jdbc.sql("UPDATE idempotency_keys SET transfer_id = :transferId WHERE idempotency_key = :key")
            .param("transferId", transferId)
            .param("key", key)
            .update()
    }
}
