package dev.rendy.kirim.transfer

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum class TransferStatus { COMPLETED, REJECTED }

data class Transfer(
    val id: UUID,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Long,
    val currency: String,
    val description: String?,
    val status: TransferStatus,
    val failureReason: RejectionReason?,
    val createdAt: Instant,
)

data class TransferCommand(
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Long,
    val currency: String,
    val description: String?,
)

/** [replayed] is true when the response came from an earlier request with the same Idempotency-Key. */
data class TransferResult(val transfer: Transfer, val replayed: Boolean)

/**
 * Fingerprint of a request body. Stored next to the Idempotency-Key so that
 * reusing a key with a *different* body is caught (422) instead of silently
 * returning the wrong transfer.
 */
object RequestFingerprint {
    fun of(command: TransferCommand): String {
        val canonical = listOf(
            command.fromAccountId,
            command.toAccountId,
            command.amount,
            command.currency,
            command.description.orEmpty(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
