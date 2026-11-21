package dev.rendy.kirim.reconciliation

import dev.rendy.kirim.common.ApiException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ConfigurationProperties("kirim.reconciliation")
data class ReconciliationProperties(
    val schedulerEnabled: Boolean = true,
    /** Every day at 01:30 in [zone], reconcile the day before. */
    val cron: String = "0 30 1 * * *",
    /** Business days are counted in this zone: a transfer at 23:30 WIB belongs to that WIB day. */
    val zone: String = "Asia/Jakarta",
    /** A PENDING outbox event younger than this is still on its way to Kafka, not a mismatch. */
    val pendingGrace: Duration = Duration.ofMinutes(5),
    /** At most this many mismatches are stored per run; the count is always exact. */
    val maxStoredMismatches: Int = 1000,
)

enum class RunType { SCHEDULED, MANUAL }

enum class RunStatus { RUNNING, MATCHED, MISMATCH, FAILED }

enum class MismatchKind {
    /** accounts.balance differs from the sum of the account's ledger entries. */
    BALANCE_MISMATCH,

    /** Per currency, all balances together differ from all opening deposits: money was created or lost. */
    MONEY_NOT_CONSERVED,

    /** A COMPLETED transfer does not have exactly one DEBIT on the sender and one CREDIT on the receiver, both for its amount. */
    TRANSFER_LEGS_MISMATCH,

    /** A REJECTED transfer moved money: it has ledger entries. */
    REJECTED_WITH_LEDGER_ENTRIES,

    /** A transfer has no outbox event, so nothing downstream (notifications, risk) heard about it. */
    EVENT_MISSING,

    /** A transfer has more than one outbox event. */
    EVENT_DUPLICATED,

    /** A transfer's event is FAILED, or still PENDING after the grace period. */
    EVENT_NOT_PUBLISHED,
}

data class Mismatch(
    val kind: MismatchKind,
    val accountId: UUID? = null,
    val transferId: UUID? = null,
    val currency: String? = null,
    val expected: Long? = null,
    val actual: Long? = null,
    val details: String,
)

data class ReconciliationRun(
    val id: UUID,
    val businessDate: LocalDate,
    val runType: RunType,
    val status: RunStatus,
    val accountsChecked: Int?,
    val transfersChecked: Int?,
    val mismatchCount: Int?,
    val error: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

data class ReconciliationReport(val run: ReconciliationRun, val mismatches: List<Mismatch>)

/** What one pass over the books found, before it is stored. */
data class ReconciliationResult(
    val accountsChecked: Int,
    val transfersChecked: Int,
    val mismatches: List<Mismatch>,
) {
    val status: RunStatus get() = if (mismatches.isEmpty()) RunStatus.MATCHED else RunStatus.MISMATCH
}

class ReconciliationRunNotFoundException(id: UUID) :
    ApiException(
        HttpStatus.NOT_FOUND,
        "RECONCILIATION_RUN_NOT_FOUND",
        "Reconciliation run $id was not found",
    )
