package dev.rendy.kirim.account

import java.time.Instant
import java.util.UUID

data class Account(
    val id: UUID,
    val ownerName: String,
    val currency: String,
    /** Minor units (whole Rupiah for IDR). */
    val balance: Long,
    val createdAt: Instant,
    /** Keycloak user id (`sub`) of the owner; null for accounts opened before authentication existed. */
    val ownerId: String? = null,
)

enum class EntryType { DEBIT, CREDIT }

data class LedgerEntry(
    val id: UUID,
    val accountId: UUID,
    /** Null for the opening deposit. */
    val transferId: UUID?,
    val type: EntryType,
    val amount: Long,
    val balanceAfter: Long,
    val createdAt: Instant,
)
