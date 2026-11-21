package dev.rendy.kirim.reconciliation

import dev.rendy.kirim.common.InvalidRequestException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * Operations endpoints. In a real bank these sit behind an internal gateway
 * with ops-only access; Kirim has no authentication anywhere yet.
 */
@RestController
@RequestMapping("/api/v1/reconciliations")
class ReconciliationController(private val service: ReconciliationService) {

    /** Runs a reconciliation now. `date` = the business day (Asia/Jakarta) whose transfers are checked; default today. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun run(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): ReconciliationReport {
        val day = date ?: service.today()
        if (day.isAfter(service.today())) throw InvalidRequestException("date must not be in the future")
        return service.runManual(day)
    }

    @GetMapping
    fun recent(): List<ReconciliationRun> = service.recentRuns()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ReconciliationReport = service.report(id)
}

@Component
@ConditionalOnProperty(prefix = "kirim.reconciliation", name = ["scheduler-enabled"], havingValue = "true", matchIfMissing = true)
class ReconciliationScheduler(private val service: ReconciliationService) {

    /** Nightly, after the business day has closed: reconcile yesterday. */
    @Scheduled(cron = "\${kirim.reconciliation.cron:0 30 1 * * *}", zone = "\${kirim.reconciliation.zone:Asia/Jakarta}")
    fun reconcileYesterday() {
        service.runScheduled(service.today().minusDays(1))
    }
}
