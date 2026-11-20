package dev.rendy.kirim.risk;

import dev.rendy.kirim.risk.event.TransferEvent;
import dev.rendy.kirim.risk.rules.DailyActivity;
import dev.rendy.kirim.risk.rules.RiskProperties;
import dev.rendy.kirim.risk.rules.RiskRules;
import dev.rendy.kirim.risk.store.Alert;
import dev.rendy.kirim.risk.store.RiskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RiskRepository repository;
    private final RiskProperties props;
    private final ZoneId zone;
    private final Clock clock;
    private final MeterRegistry meters;

    public RiskService(RiskRepository repository, RiskProperties props, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.props = props;
        this.zone = ZoneId.of(props.zone());
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * One transaction: dedupe on eventId, update the daily totals, store any alerts.
     * If anything fails, all of it rolls back and Kafka redelivers the message, so
     * a total is never counted twice or half-updated.
     */
    @Transactional
    public void handle(TransferEvent event) {
        if (!event.isCompleted()) {
            return; // rejected transfers moved no money
        }
        Instant now = clock.instant();
        if (!repository.markProcessed(event.eventId(), now)) {
            log.info("Skipping duplicate event {}", event.eventId());
            meters.counter("kirim.risk.events", "result", "duplicate").increment();
            return;
        }

        LocalDate day = RiskRules.businessDay(event.occurredAt(), zone);
        DailyActivity activity = repository.addOutgoing(event.fromAccountId(), day, event.amount());

        for (RiskRules.Finding finding : RiskRules.evaluate(event.amount(), activity, props)) {
            repository.insertAlert(new Alert(
                    UUID.randomUUID(), event.eventId(), event.transferId(), event.fromAccountId(),
                    finding.rule(), event.amount(), finding.details(), now));
            meters.counter("kirim.risk.alerts", "rule", finding.rule().name()).increment();
            log.warn("Risk alert {} for account {} on transfer {}: {}",
                    finding.rule(), event.fromAccountId(), event.transferId(), finding.details());
        }
        meters.counter("kirim.risk.events", "result", "processed").increment();
    }
}
