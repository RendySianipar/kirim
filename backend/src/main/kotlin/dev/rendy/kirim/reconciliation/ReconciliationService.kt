package dev.rendy.kirim.reconciliation

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Checks that the books add up, for one business day:
 *  - every account's balance equals the sum of its ledger entries (all time)
 *  - per currency, no money was created or lost (all time)
 *  - every transfer of the day has the right ledger legs, and exactly one delivered outbox event
 *
 * The checks read one consistent snapshot (REPEATABLE READ), so transfers that
 * commit while the run is going cannot make it see half of a transfer.
 */
@Service
class ReconciliationService(
    private val repository: ReconciliationRepository,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val props: ReconciliationProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone: ZoneId = ZoneId.of(props.zone)

    private val writeTx = TransactionTemplate(transactionManager)

    private val snapshotTx = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /** Today's date in the business time zone. */
    fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    /**
     * The nightly run. With several replicas, only the one that claims the day
     * runs it; the others get null back and do nothing.
     */
    fun runScheduled(businessDate: LocalDate): ReconciliationReport? = run(businessDate, RunType.SCHEDULED)

    /** A run asked for by Operations, e.g. after fixing data. Always runs. */
    fun runManual(businessDate: LocalDate): ReconciliationReport = run(businessDate, RunType.MANUAL)!!

    fun report(id: UUID): ReconciliationReport {
        val run = repository.findRun(id) ?: throw ReconciliationRunNotFoundException(id)
        return ReconciliationReport(run, repository.findMismatches(id))
    }

    fun recentRuns(): List<ReconciliationRun> = repository.findRecentRuns(20)

    private fun run(businessDate: LocalDate, type: RunType): ReconciliationReport? {
        val runId = UUID.randomUUID()
        val claimed = writeTx.execute { repository.tryStartRun(runId, businessDate, type, clock.instant()) } ?: false
        if (!claimed) {
            log.info("Reconciliation for {} already ran (or is running) on another instance, skipping", businessDate)
            return null
        }

        val result = try {
            snapshotTx.execute { check(businessDate) }!!
        } catch (ex: Exception) {
            writeTx.executeWithoutResult { repository.failRun(runId, ex.message ?: ex.javaClass.simpleName, clock.instant()) }
            meters.counter("kirim.reconciliation.runs", "status", RunStatus.FAILED.name).increment()
            log.error("Reconciliation {} for {} failed", runId, businessDate, ex)
            throw ex
        }

        writeTx.executeWithoutResult {
            repository.finishRun(runId, result, clock.instant())
            result.mismatches.take(props.maxStoredMismatches).forEach { repository.insertMismatch(runId, it) }
        }

        meters.counter("kirim.reconciliation.runs", "status", result.status.name).increment()
        result.mismatches.groupingBy { it.kind }.eachCount().forEach { (kind, count) ->
            meters.counter("kirim.reconciliation.mismatches", "kind", kind.name).increment(count.toDouble())
        }
        if (result.status == RunStatus.MATCHED) {
            log.info("Reconciliation {} for {}: MATCHED ({} accounts, {} transfers)",
                runId, businessDate, result.accountsChecked, result.transfersChecked)
        } else {
            log.warn("Reconciliation {} for {}: {} mismatches {}", runId, businessDate, result.mismatches.size,
                result.mismatches.groupingBy { it.kind }.eachCount())
        }
        return report(runId)
    }

    private fun check(businessDate: LocalDate): ReconciliationResult {
        val from = businessDate.atStartOfDay(zone).toInstant()
        val to = businessDate.plusDays(1).atStartOfDay(zone).toInstant()
        val pendingCutoff = clock.instant().minus(props.pendingGrace)

        val mismatches = repository.balanceMismatches() +
            repository.conservationMismatches() +
            repository.legMismatches(from, to) +
            repository.eventMismatches(from, to, pendingCutoff)

        return ReconciliationResult(
            accountsChecked = repository.countAccounts(),
            transfersChecked = repository.countTransfers(from, to),
            mismatches = mismatches,
        )
    }
}
