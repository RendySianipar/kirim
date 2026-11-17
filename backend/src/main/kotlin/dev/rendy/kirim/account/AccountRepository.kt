package dev.rendy.kirim.account

import dev.rendy.kirim.common.instant
import dev.rendy.kirim.common.toTimestamp
import dev.rendy.kirim.common.uuid
import dev.rendy.kirim.common.uuidOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AccountRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        Account(
            id = rs.uuid("id"),
            ownerName = rs.getString("owner_name"),
            currency = rs.getString("currency"),
            balance = rs.getLong("balance"),
            createdAt = rs.instant("created_at"),
        )
    }

    fun insert(account: Account) {
        jdbc.sql(
            """
            INSERT INTO accounts (id, owner_name, currency, balance, created_at)
            VALUES (:id, :ownerName, :currency, :balance, :createdAt)
            """,
        )
            .param("id", account.id)
            .param("ownerName", account.ownerName)
            .param("currency", account.currency)
            .param("balance", account.balance)
            .param("createdAt", account.createdAt.toTimestamp())
            .update()
    }

    fun findById(id: UUID): Account? =
        jdbc.sql("SELECT * FROM accounts WHERE id = :id")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    fun findAll(): List<Account> =
        jdbc.sql("SELECT * FROM accounts ORDER BY created_at")
            .query(mapper)
            .list()

    /**
     * Locks the given rows with SELECT ... FOR UPDATE.
     *
     * ORDER BY id matters: if transfer A->B and B->A run at the same time, both
     * transactions take the locks in the same order, so they queue instead of
     * deadlocking.
     */
    fun findAllForUpdate(ids: Collection<UUID>): Map<UUID, Account> =
        jdbc.sql("SELECT * FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
            .param("ids", ids.toList())
            .query(mapper)
            .list()
            .associateBy { it.id }

    fun updateBalance(id: UUID, newBalance: Long) {
        jdbc.sql("UPDATE accounts SET balance = :balance WHERE id = :id")
            .param("balance", newBalance)
            .param("id", id)
            .update()
    }
}

@Repository
class LedgerRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        LedgerEntry(
            id = rs.uuid("id"),
            accountId = rs.uuid("account_id"),
            transferId = rs.uuidOrNull("transfer_id"),
            type = EntryType.valueOf(rs.getString("entry_type")),
            amount = rs.getLong("amount"),
            balanceAfter = rs.getLong("balance_after"),
            createdAt = rs.instant("created_at"),
        )
    }

    fun insert(entry: LedgerEntry) {
        jdbc.sql(
            """
            INSERT INTO ledger_entries (id, account_id, transfer_id, entry_type, amount, balance_after, created_at)
            VALUES (:id, :accountId, :transferId, :type, :amount, :balanceAfter, :createdAt)
            """,
        )
            .param("id", entry.id)
            .param("accountId", entry.accountId)
            .param("transferId", entry.transferId)
            .param("type", entry.type.name)
            .param("amount", entry.amount)
            .param("balanceAfter", entry.balanceAfter)
            .param("createdAt", entry.createdAt.toTimestamp())
            .update()
    }

    fun findByAccount(accountId: UUID, limit: Int): List<LedgerEntry> =
        jdbc.sql("SELECT * FROM ledger_entries WHERE account_id = :accountId ORDER BY created_at DESC, id LIMIT :limit")
            .param("accountId", accountId)
            .param("limit", limit)
            .query(mapper)
            .list()

    /** Sum of credits minus debits; used by tests to prove balances match the ledger. */
    fun netAmount(accountId: UUID): Long =
        jdbc.sql(
            """
            SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries WHERE account_id = :accountId
            """,
        )
            .param("accountId", accountId)
            .query(Long::class.javaObjectType)
            .single()
}
