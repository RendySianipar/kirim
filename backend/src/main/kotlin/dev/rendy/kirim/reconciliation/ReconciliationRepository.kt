package dev.rendy.kirim.reconciliation

import dev.rendy.kirim.common.instant
import dev.rendy.kirim.common.instantOrNull
import dev.rendy.kirim.common.toTimestamp
import dev.rendy.kirim.common.uuid
import dev.rendy.kirim.common.uuidOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The checks are plain SQL over whole tables, so the database does the
 * aggregation and only the problems come back. Each query is reviewable on its own.
 */
@Repository
class ReconciliationRepository(private val jdbc: JdbcClient) {

    // ---------------------------------------------------------------- checks

    fun countAccounts(): Int =
        jdbc.sql("SELECT COUNT(*) FROM accounts").query(Long::class.javaObjectType).single().toInt()

    fun countTransfers(from: Instant, to: Instant): Int =
        jdbc.sql("SELECT COUNT(*) FROM transfers WHERE created_at >= :from AND created_at < :to")
            .param("from", from.toTimestamp())
            .param("to", to.toTimestamp())
            .query(Long::class.javaObjectType).single().toInt()

    /** Every account whose stored balance is not the sum of its ledger entries. */
    fun balanceMismatches(): List<Mismatch> =
        jdbc.sql(
            """
            SELECT a.id, a.currency, a.balance AS actual,
                   COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE -l.amount END), 0) AS expected
            FROM accounts a
            LEFT JOIN ledger_entries l ON l.account_id = a.id
            GROUP BY a.id, a.currency, a.balance
            HAVING a.balance <> COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE -l.amount END), 0)
            """,
        ).query(RowMapper { rs, _ ->
            Mismatch(
                kind = MismatchKind.BALANCE_MISMATCH,
                accountId = rs.uuid("id"),
                currency = rs.getString("currency"),
                expected = rs.getLong("expected"),
                actual = rs.getLong("actual"),
                details = "Stored balance differs from the sum of the account's ledger entries",
            )
        }).list()

    /**
     * Transfers only move money between accounts, so per currency the total of
     * all balances must equal the total of all opening deposits.
     */
    fun conservationMismatches(): List<Mismatch> =
        jdbc.sql(
            """
            SELECT a.currency, SUM(a.balance) AS actual,
                   COALESCE((SELECT SUM(l.amount) FROM ledger_entries l JOIN accounts d ON d.id = l.account_id
                             WHERE l.transfer_id IS NULL AND l.entry_type = 'CREDIT' AND d.currency = a.currency), 0) AS expected
            FROM accounts a
            GROUP BY a.currency
            HAVING SUM(a.balance) <> COALESCE((SELECT SUM(l.amount) FROM ledger_entries l JOIN accounts d ON d.id = l.account_id
                             WHERE l.transfer_id IS NULL AND l.entry_type = 'CREDIT' AND d.currency = a.currency), 0)
            """,
        ).query(RowMapper { rs, _ ->
            Mismatch(
                kind = MismatchKind.MONEY_NOT_CONSERVED,
                currency = rs.getString("currency"),
                expected = rs.getLong("expected"),
                actual = rs.getLong("actual"),
                details = "All balances together differ from all opening deposits",
            )
        }).list()

    /** Transfers of the day whose ledger legs are wrong: missing, extra, wrong account or wrong amount. */
    fun legMismatches(from: Instant, to: Instant): List<Mismatch> =
        jdbc.sql(
            """
            SELECT t.id, t.status, t.amount, t.currency, t.from_account_id,
                   COUNT(l.id) AS legs,
                   COUNT(*) FILTER (WHERE l.entry_type = 'DEBIT'  AND l.account_id = t.from_account_id AND l.amount = t.amount) AS good_debits,
                   COUNT(*) FILTER (WHERE l.entry_type = 'CREDIT' AND l.account_id = t.to_account_id   AND l.amount = t.amount) AS good_credits
            FROM transfers t
            LEFT JOIN ledger_entries l ON l.transfer_id = t.id
            WHERE t.created_at >= :from AND t.created_at < :to
            GROUP BY t.id, t.status, t.amount, t.currency, t.from_account_id
            HAVING (t.status = 'COMPLETED' AND NOT (
                        COUNT(l.id) = 2
                        AND COUNT(*) FILTER (WHERE l.entry_type = 'DEBIT'  AND l.account_id = t.from_account_id AND l.amount = t.amount) = 1
                        AND COUNT(*) FILTER (WHERE l.entry_type = 'CREDIT' AND l.account_id = t.to_account_id   AND l.amount = t.amount) = 1))
                OR (t.status = 'REJECTED' AND COUNT(l.id) > 0)
            """,
        )
            .param("from", from.toTimestamp())
            .param("to", to.toTimestamp())
            .query(RowMapper { rs, _ ->
                val completed = rs.getString("status") == "COMPLETED"
                val legs = rs.getInt("legs")
                Mismatch(
                    kind = if (completed) MismatchKind.TRANSFER_LEGS_MISMATCH else MismatchKind.REJECTED_WITH_LEDGER_ENTRIES,
                    accountId = rs.uuid("from_account_id"),
                    transferId = rs.uuid("id"),
                    currency = rs.getString("currency"),
                    expected = if (completed) 2L else 0L,
                    actual = legs.toLong(),
                    details = if (completed) {
                        "Expected 1 DEBIT and 1 CREDIT of ${rs.getLong("amount")}; found $legs entries " +
                            "(${rs.getInt("good_debits")} matching debit, ${rs.getInt("good_credits")} matching credit)"
                    } else {
                        "Rejected transfer has $legs ledger entries"
                    },
                )
            }).list()

    /**
     * Transfers of the day without exactly one delivered outbox event. A PENDING
     * event created after [pendingCutoff] is still in flight and counts as fine.
     */
    fun eventMismatches(from: Instant, to: Instant, pendingCutoff: Instant): List<Mismatch> =
        jdbc.sql(
            """
            SELECT t.id, t.currency, t.from_account_id,
                   COUNT(o.id) AS events,
                   COUNT(*) FILTER (WHERE o.status = 'PUBLISHED' OR (o.status = 'PENDING' AND o.created_at > :cutoff)) AS ok_events,
                   MAX(o.status) AS some_status
            FROM transfers t
            LEFT JOIN outbox_events o ON o.aggregate_id = t.id
            WHERE t.created_at >= :from AND t.created_at < :to
            GROUP BY t.id, t.currency, t.from_account_id
            HAVING COUNT(o.id) <> 1
                OR COUNT(*) FILTER (WHERE o.status = 'PUBLISHED' OR (o.status = 'PENDING' AND o.created_at > :cutoff)) <> 1
            """,
        )
            .param("from", from.toTimestamp())
            .param("to", to.toTimestamp())
            .param("cutoff", pendingCutoff.toTimestamp())
            .query(RowMapper { rs, _ ->
                val events = rs.getInt("events")
                val kind = when {
                    events == 0 -> MismatchKind.EVENT_MISSING
                    events > 1 -> MismatchKind.EVENT_DUPLICATED
                    else -> MismatchKind.EVENT_NOT_PUBLISHED
                }
                Mismatch(
                    kind = kind,
                    accountId = rs.uuid("from_account_id"),
                    transferId = rs.uuid("id"),
                    currency = rs.getString("currency"),
                    expected = 1L,
                    actual = events.toLong(),
                    details = when (kind) {
                        MismatchKind.EVENT_MISSING -> "No outbox event for this transfer"
                        MismatchKind.EVENT_DUPLICATED -> "$events outbox events for this transfer"
                        else -> "Outbox event is ${rs.getString("some_status")}, not PUBLISHED"
                    },
                )
            }).list()

    // ---------------------------------------------------------------- runs

    /**
     * Inserts a RUNNING run. For SCHEDULED runs the partial unique index allows
     * one per business date: returns false when another replica already claimed it.
     */
    fun tryStartRun(id: UUID, businessDate: LocalDate, type: RunType, now: Instant): Boolean =
        jdbc.sql(
            """
            INSERT INTO reconciliation_runs (id, business_date, run_type, status, started_at)
            VALUES (:id, :businessDate, :type, 'RUNNING', :now)
            ON CONFLICT (business_date) WHERE run_type = 'SCHEDULED' DO NOTHING
            """,
        )
            .param("id", id)
            .param("businessDate", businessDate)
            .param("type", type.name)
            .param("now", now.toTimestamp())
            .update() == 1

    fun finishRun(id: UUID, result: ReconciliationResult, now: Instant) {
        jdbc.sql(
            """
            UPDATE reconciliation_runs
               SET status = :status, accounts_checked = :accounts, transfers_checked = :transfers,
                   mismatch_count = :mismatches, finished_at = :now
             WHERE id = :id
            """,
        )
            .param("id", id)
            .param("status", result.status.name)
            .param("accounts", result.accountsChecked)
            .param("transfers", result.transfersChecked)
            .param("mismatches", result.mismatches.size)
            .param("now", now.toTimestamp())
            .update()
    }

    fun failRun(id: UUID, error: String, now: Instant) {
        jdbc.sql("UPDATE reconciliation_runs SET status = 'FAILED', error = :error, finished_at = :now WHERE id = :id")
            .param("id", id)
            .param("error", error.take(2000))
            .param("now", now.toTimestamp())
            .update()
    }

    fun insertMismatch(runId: UUID, m: Mismatch) {
        jdbc.sql(
            """
            INSERT INTO reconciliation_mismatches (id, run_id, kind, account_id, transfer_id, currency, expected, actual, details)
            VALUES (:id, :runId, :kind, :accountId, :transferId, :currency, :expected, :actual, :details)
            """,
        )
            .param("id", UUID.randomUUID())
            .param("runId", runId)
            .param("kind", m.kind.name)
            .param("accountId", m.accountId)
            .param("transferId", m.transferId)
            .param("currency", m.currency)
            .param("expected", m.expected)
            .param("actual", m.actual)
            .param("details", m.details.take(300))
            .update()
    }

    private val runMapper = RowMapper { rs, _ ->
        ReconciliationRun(
            id = rs.uuid("id"),
            businessDate = rs.getObject("business_date", LocalDate::class.java),
            runType = RunType.valueOf(rs.getString("run_type")),
            status = RunStatus.valueOf(rs.getString("status")),
            accountsChecked = rs.getObject("accounts_checked") as Int?,
            transfersChecked = rs.getObject("transfers_checked") as Int?,
            mismatchCount = rs.getObject("mismatch_count") as Int?,
            error = rs.getString("error"),
            startedAt = rs.instant("started_at"),
            finishedAt = rs.instantOrNull("finished_at"),
        )
    }

    fun findRun(id: UUID): ReconciliationRun? =
        jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id").param("id", id).query(runMapper).optional().orElse(null)

    fun findRecentRuns(limit: Int): List<ReconciliationRun> =
        jdbc.sql("SELECT * FROM reconciliation_runs ORDER BY started_at DESC LIMIT :limit")
            .param("limit", limit)
            .query(runMapper).list()

    fun findMismatches(runId: UUID): List<Mismatch> =
        jdbc.sql("SELECT * FROM reconciliation_mismatches WHERE run_id = :runId ORDER BY kind, account_id, transfer_id")
            .param("runId", runId)
            .query(RowMapper { rs, _ ->
                Mismatch(
                    kind = MismatchKind.valueOf(rs.getString("kind")),
                    accountId = rs.uuidOrNull("account_id"),
                    transferId = rs.uuidOrNull("transfer_id"),
                    currency = rs.getString("currency"),
                    expected = rs.getObject("expected") as Long?,
                    actual = rs.getObject("actual") as Long?,
                    details = rs.getString("details"),
                )
            }).list()
}
